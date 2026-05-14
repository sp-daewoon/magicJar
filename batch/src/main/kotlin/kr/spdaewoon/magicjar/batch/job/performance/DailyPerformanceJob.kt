package kr.spdaewoon.magicjar.batch.job.performance

import kr.spdaewoon.magicjar.batch.persistence.performance.BatchOrderExecutionJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchOrderRequestJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchPerformanceSummaryJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchPerformanceSummaryJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchPnlDailyJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchPnlDailyJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * DailyPerformanceJob — ADR 0072 §2-2 B-1 LOCK + spec §3-2 정합.
 *
 * cron: 매일 00:30 KST (장 마감 후 T-1 영업일 집계).
 * spec 본문: `0 30 0 * * *` zone=Asia/Seoul.
 *
 * 처리 흐름:
 * 1. order_request distinct user_id 목록 조회
 * 2. 각 userId별로 어제 trade_date 기준 order_execution realized_pnl 집계
 * 3. pnl_daily UPSERT (UQ user_id + trade_date — 멱등성)
 * 4. performance_summary 5종 UPSERT (DAY·WEEK·MONTH·YEAR·ALL)
 * 5. Redis perf:* 캐시 invalidate
 *
 * 순수 수학 계산 (AI/KIS 호출 0).
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 * // KIS 호출 0 — 영속 데이터 reuse (ADR 0072 §1-4 LOCK)
 */
@Component
class DailyPerformanceJob(
    private val orderRequestJpaRepository: BatchOrderRequestJpaRepository,
    private val orderExecutionJpaRepository: BatchOrderExecutionJpaRepository,
    private val pnlDailyJpaRepository: BatchPnlDailyJpaRepository,
    private val summaryJpaRepository: BatchPerformanceSummaryJpaRepository,
    private val redis: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(DailyPerformanceJob::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /**
     * 매일 00:30 KST cron — T-1 영업일 집계 (ADR 0072 §2-2 B-1 LOCK).
     */
    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Seoul")
    fun runDaily() {
        val targetDate = LocalDate.now(kst).minusDays(1)
        log.info("[DailyPerformanceJob] 시작 — targetDate={}", targetDate)
        run(targetDate)
    }

    /**
     * 특정 날짜 집계 실행 (manual trigger + test 용).
     */
    @Transactional
    fun run(targetDate: LocalDate) {
        val userIds = orderRequestJpaRepository.findDistinctUserIds()
        if (userIds.isEmpty()) {
            log.info("[DailyPerformanceJob] 활성 사용자 없음 — skip")
            return
        }

        log.info("[DailyPerformanceJob] 사용자 {}명 집계 시작 — targetDate={}", userIds.size, targetDate)

        for (userId in userIds) {
            processUser(userId, targetDate)
        }

        log.info("[DailyPerformanceJob] 완료 — targetDate={}", targetDate)
    }

    // --- private helpers (헌법 §6 — 이 클래스 private fun 위치) ---

    private fun processUser(
        userId: String,
        targetDate: LocalDate,
    ) {
        runCatching {
            // 1. targetDate 하루 체결 조회
            val fromInstant = targetDate.atStartOfDay(kst).toInstant()
            val toInstant = targetDate.plusDays(1).atStartOfDay(kst).toInstant()

            val executions = orderExecutionJpaRepository.findByUserIdAndExecutedAtRange(userId, fromInstant, toInstant)

            if (executions.isEmpty()) {
                log.debug("[DailyPerformanceJob] userId={} targetDate={} 체결 0건 — skip", userId, targetDate)
                return@runCatching
            }

            // 2. pnl_daily 집계
            val pnlRow = aggregatePnlDaily(userId, targetDate, executions.map { it.realizedPnl })
            upsertPnlDaily(pnlRow)

            // 3. performance_summary 5종 UPSERT
            updatePerformanceSummaries(userId, targetDate)

            // 4. Redis cache invalidate
            invalidateRedisCache(userId)

            log.info(
                "[DailyPerformanceJob] userId={} targetDate={} realizedPnl={} tradeCount={}",
                userId,
                targetDate,
                pnlRow.realizedPnl,
                pnlRow.tradeCount,
            )
        }.onFailure {
            log.error("[DailyPerformanceJob] userId={} targetDate={} 처리 실패: {}", userId, targetDate, it.message, it)
        }
    }

    private fun aggregatePnlDaily(
        userId: String,
        tradeDate: LocalDate,
        pnlList: List<BigDecimal?>,
    ): BatchPnlDailyJpaEntity {
        val nonNullPnl = pnlList.filterNotNull()
        val realizedPnl = nonNullPnl.fold(BigDecimal.ZERO) { acc, v -> acc + v }

        val winPnl = nonNullPnl.filter { it > BigDecimal.ZERO }
        val lossPnl = nonNullPnl.filter { it < BigDecimal.ZERO }

        val winCount = winPnl.size
        val lossCount = lossPnl.size
        val tradeCount = nonNullPnl.size

        val avgProfit =
            if (winPnl.isNotEmpty()) {
                winPnl
                    .fold(BigDecimal.ZERO) { acc, v -> acc + v }
                    .divide(BigDecimal(winPnl.size), 2, RoundingMode.HALF_UP)
            } else {
                null
            }

        val avgLoss =
            if (lossPnl.isNotEmpty()) {
                lossPnl
                    .fold(BigDecimal.ZERO) { acc, v -> acc + v }
                    .divide(BigDecimal(lossPnl.size), 2, RoundingMode.HALF_UP)
            } else {
                null
            }

        return BatchPnlDailyJpaEntity(
            userId = userId,
            tradeDate = tradeDate,
            realizedPnl = realizedPnl,
            winCount = winCount,
            lossCount = lossCount,
            tradeCount = tradeCount,
            avgProfit = avgProfit,
            avgLoss = avgLoss,
            equityAtClose = null, // inline cron 집계 시 equity는 pnl_daily 누적 합산으로 계산
            createdAt = Instant.now(),
        )
    }

    private fun upsertPnlDaily(pnl: BatchPnlDailyJpaEntity) {
        val existing = pnlDailyJpaRepository.findByUserIdAndTradeDate(pnl.userId, pnl.tradeDate)
        if (existing != null) {
            pnlDailyJpaRepository.delete(existing)
            pnlDailyJpaRepository.flush()
        }
        pnlDailyJpaRepository.save(pnl)
    }

    private fun updatePerformanceSummaries(
        userId: String,
        targetDate: LocalDate,
    ) {
        val today = targetDate
        val periods =
            listOf(
                Triple("DAY", today, today),
                Triple("WEEK", today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today),
                Triple("MONTH", today.withDayOfMonth(1), today),
                Triple("YEAR", today.withDayOfYear(1), today),
                Triple("ALL", findEarliestTradeDate(userId) ?: today, today),
            )

        for ((period, from, to) in periods) {
            runCatching {
                val pnlRows = pnlDailyJpaRepository.findByUserIdAndTradeDateBetweenOrderByTradeDateAsc(userId, from, to)
                val summary = calculateSummary(userId, period, from, to, pnlRows)
                upsertSummary(summary)
            }.onFailure {
                log.warn("[DailyPerformanceJob] userId={} period={} summary 갱신 실패: {}", userId, period, it.message)
            }
        }
    }

    private fun calculateSummary(
        userId: String,
        period: String,
        from: LocalDate,
        to: LocalDate,
        pnlRows: List<BatchPnlDailyJpaEntity>,
    ): BatchPerformanceSummaryJpaEntity {
        val totalPnl = pnlRows.fold(BigDecimal.ZERO) { acc, r -> acc + r.realizedPnl }
        val tradeCount = pnlRows.sumOf { it.tradeCount }
        val totalWin = pnlRows.sumOf { it.winCount }

        val winRatePct =
            if (tradeCount > 0) {
                BigDecimal(totalWin)
                    .divide(BigDecimal(tradeCount), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100))
                    .setScale(2, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }

        // MDD: equity curve peak-trough
        val mddPct = calculateMdd(pnlRows)

        // PLR
        val profitLossRatio = calculatePlr(pnlRows)

        return BatchPerformanceSummaryJpaEntity(
            userId = userId,
            period = period,
            periodStart = from,
            periodEnd = to,
            totalPnl = totalPnl,
            mddPct = mddPct,
            winRatePct = winRatePct,
            profitLossRatio = profitLossRatio,
            tradeCount = tradeCount,
            metrics = "{}",
            computedAt = Instant.now(),
        )
    }

    private fun calculateMdd(pnlRows: List<BatchPnlDailyJpaEntity>): BigDecimal {
        if (pnlRows.isEmpty()) return BigDecimal.ZERO
        var cumulative = BigDecimal.ZERO
        var peak = BigDecimal.ZERO
        var maxDd = BigDecimal.ZERO
        for (row in pnlRows) {
            cumulative += row.realizedPnl
            if (cumulative > peak) peak = cumulative
            if (peak.signum() > 0) {
                val dd =
                    (peak - cumulative)
                        .divide(peak, 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal(100))
                if (dd > maxDd) maxDd = dd
            }
        }
        return maxDd.setScale(4, RoundingMode.HALF_UP)
    }

    private fun calculatePlr(pnlRows: List<BatchPnlDailyJpaEntity>): BigDecimal? {
        val profitDays = pnlRows.filter { it.winCount > 0 && it.avgProfit != null && it.avgProfit > BigDecimal.ZERO }
        val lossDays = pnlRows.filter { it.lossCount > 0 && it.avgLoss != null && it.avgLoss < BigDecimal.ZERO }

        if (lossDays.isEmpty()) return null

        val avgProfit =
            if (profitDays.isEmpty()) {
                BigDecimal.ZERO
            } else {
                val totalWinCount = profitDays.sumOf { it.winCount }
                val weightedSum = profitDays.fold(BigDecimal.ZERO) { acc, r -> acc + (r.avgProfit!! * BigDecimal(r.winCount)) }
                if (totalWinCount > 0) weightedSum.divide(BigDecimal(totalWinCount), 8, RoundingMode.HALF_UP) else BigDecimal.ZERO
            }

        val totalLossCount = lossDays.sumOf { it.lossCount }
        val weightedLossSum = lossDays.fold(BigDecimal.ZERO) { acc, r -> acc + (r.avgLoss!! * BigDecimal(r.lossCount)) }
        val avgLoss = weightedLossSum.divide(BigDecimal(totalLossCount), 8, RoundingMode.HALF_UP).abs()

        if (avgLoss.signum() == 0) return null
        return avgProfit.divide(avgLoss, 4, RoundingMode.HALF_UP)
    }

    private fun upsertSummary(summary: BatchPerformanceSummaryJpaEntity) {
        val existing =
            summaryJpaRepository.findByUserIdAndPeriodAndPeriodStartAndPeriodEnd(
                summary.userId,
                summary.period,
                summary.periodStart,
                summary.periodEnd,
            )
        if (existing != null) {
            summaryJpaRepository.delete(existing)
            summaryJpaRepository.flush()
        }
        summaryJpaRepository.save(summary)
    }

    private fun findEarliestTradeDate(userId: String): LocalDate? =
        runCatching {
            pnlDailyJpaRepository
                .findByUserIdAndTradeDateBetweenOrderByTradeDateAsc(
                    userId,
                    LocalDate.of(2020, 1, 1),
                    LocalDate.now(kst),
                ).firstOrNull()
                ?.tradeDate
        }.getOrNull()

    private fun invalidateRedisCache(userId: String) {
        runCatching {
            listOf("perf:summary:$userId:*", "perf:equity:$userId:*").forEach { pattern ->
                val keys = redis.keys(pattern)
                if (!keys.isNullOrEmpty()) redis.delete(keys)
            }
        }.onFailure { log.warn("[DailyPerformanceJob] Redis cache invalidate 실패: {}", it.message) }
    }
}
