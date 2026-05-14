package kr.spdaewoon.magicjar.api.service.performance

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.dto.performance.EquityCurveDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.EquityPointDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceSummaryDto
import kr.spdaewoon.magicjar.api.infrastructure.cache.RedisPerformanceCacheAdapter
import kr.spdaewoon.magicjar.domain.performance.port.PerformanceCalculator
import kr.spdaewoon.magicjar.domain.performance.port.PerformanceRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Performance REST 서비스 — ADR 0072 §9-3 Phase 2 산출물.
 *
 * 캐시 흐름 (ADR 0072 §5):
 * 1. Redis HIT → cacheHit=true 즉시 반환
 * 2. Redis MISS → pnl_daily aggregate + inline 계산 → Redis SET + 반환
 *
 * period 분기 (ADR 0072 §2-3 C-1 LOCK):
 * - DAY: 오늘 (pnl_daily 부재 시 inline aggregate)
 * - WEEK: 이번 주 월~오늘
 * - MONTH: 이번 달 1일~오늘
 * - YEAR: 올해 1월 1일~오늘
 * - ALL: 최초 거래일~오늘
 *
 * userId: String — user_account.user_id VARCHAR(40) 정합.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 * // KIS 호출 0 — 모든 데이터 영속 reuse (ADR 0072 §1-4 LOCK)
 */
@Service
class PerformanceService(
    private val performanceRepository: PerformanceRepository,
    private val performanceCalculator: PerformanceCalculator,
    private val cache: RedisPerformanceCacheAdapter,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(PerformanceService::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /**
     * 성과 통계 요약 조회.
     * Redis HIT → 즉시 반환. MISS → pnl_daily 집계 + 계산.
     */
    fun getSummary(
        userId: String,
        period: String,
        periodStart: LocalDate?,
        periodEnd: LocalDate?,
    ): PerformanceSummaryDto {
        val validPeriod = validatePeriod(period)
        val (from, to) = resolvePeriodRange(userId, validPeriod, periodStart, periodEnd)

        // Redis HIT check
        val cached = cache.getSummaryJson(userId, validPeriod, from, to)
        if (cached != null) {
            log.debug("perf:summary 캐시 HIT userId={} period={} [{},{}]", userId, validPeriod, from, to)
            val dto = objectMapper.readValue(cached, PerformanceSummaryDto::class.java)
            return dto.copy(cacheHit = true)
        }

        // MISS → 계산
        val pnlDailyList = performanceRepository.findPnlDailyRange(userId, from, to)
        val equityCurve = performanceCalculator.buildEquityCurve(pnlDailyList, from, to)

        val totalPnl = performanceCalculator.totalPnl(pnlDailyList)
        val mddPct = performanceCalculator.maxDrawdown(equityCurve)
        val winRatePct = performanceCalculator.winRate(pnlDailyList)
        val plRatio = performanceCalculator.profitLossRatio(pnlDailyList)
        val tradeCount = pnlDailyList.sumOf { it.tradeCount }

        val dto =
            PerformanceSummaryDto(
                period = validPeriod,
                periodStart = from,
                periodEnd = to,
                totalPnl = totalPnl,
                mddPct = mddPct,
                winRatePct = winRatePct,
                profitLossRatio = plRatio,
                tradeCount = tradeCount,
                computedAt = OffsetDateTime.ofInstant(Instant.now(), kst),
                cacheHit = false,
            )

        // Redis SET
        cache.putSummary(userId, validPeriod, from, to, dto)
        return dto
    }

    /**
     * Equity Curve 시계열 조회.
     * Redis HIT → 즉시 반환. MISS → pnl_daily aggregate.
     * default: 최근 30일.
     */
    fun getEquityCurve(
        userId: String,
        from: LocalDate?,
        to: LocalDate?,
    ): EquityCurveDto {
        val today = LocalDate.now(kst)
        val resolvedTo = to ?: today
        val resolvedFrom = from ?: resolvedTo.minusDays(30)

        // Redis HIT check
        val cached = cache.getEquityCurveJson(userId, resolvedFrom, resolvedTo)
        if (cached != null) {
            log.debug("perf:equity 캐시 HIT userId={} [{},{}]", userId, resolvedFrom, resolvedTo)
            return objectMapper.readValue(cached, EquityCurveDto::class.java)
        }

        // MISS → 집계
        val pnlDailyList = performanceRepository.findPnlDailyRange(userId, resolvedFrom, resolvedTo)
        val equityPoints = performanceCalculator.buildEquityCurve(pnlDailyList, resolvedFrom, resolvedTo)

        val dto =
            EquityCurveDto(
                from = resolvedFrom,
                to = resolvedTo,
                points =
                    equityPoints.map { point ->
                        EquityPointDto(
                            tradeDate = point.tradeDate,
                            dailyPnl = point.dailyPnl,
                            cumulativePnl = point.cumulativePnl,
                            tradeCount = point.tradeCount,
                        )
                    },
            )

        // Redis SET
        cache.putEquityCurve(userId, resolvedFrom, resolvedTo, dto)
        return dto
    }

    // --- private helpers ---

    private fun validatePeriod(period: String): String {
        val valid = setOf("DAY", "WEEK", "MONTH", "YEAR", "ALL")
        val upper = period.uppercase()
        if (upper !in valid) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 period입니다: $period. 허용값: $valid")
        }
        return upper
    }

    private fun resolvePeriodRange(
        userId: String,
        period: String,
        periodStart: LocalDate?,
        periodEnd: LocalDate?,
    ): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now(kst)
        // 명시적 from·to 우선
        if (periodStart != null && periodEnd != null) return Pair(periodStart, periodEnd)

        return when (period) {
            "DAY" -> Pair(today, today)
            "WEEK" -> {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Pair(monday, today)
            }
            "MONTH" -> Pair(today.withDayOfMonth(1), today)
            "YEAR" -> Pair(today.withDayOfYear(1), today)
            "ALL" -> {
                val earliest = performanceRepository.findEarliestTradeDate(userId) ?: today
                Pair(earliest, today)
            }
            else -> Pair(today, today)
        }
    }
}
