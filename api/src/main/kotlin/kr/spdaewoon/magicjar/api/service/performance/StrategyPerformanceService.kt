package kr.spdaewoon.magicjar.api.service.performance

import kr.spdaewoon.magicjar.api.controller.dto.performance.RegimeWinRateDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyConfidenceDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyNoteDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyNoteTimelineResponse
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyNoteUpsertRequest
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyPerformanceHistoryResponse
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyPerformanceRowDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyPerformanceSummaryResponse
import kr.spdaewoon.magicjar.api.persistence.performance.StrategyDailyNoteJpaEntity
import kr.spdaewoon.magicjar.api.persistence.performance.StrategyDailyNoteJpaRepository
import kr.spdaewoon.magicjar.api.persistence.performance.StrategyDailyPerformanceJpaEntity
import kr.spdaewoon.magicjar.api.persistence.performance.StrategyDailyPerformanceJpaRepository
import kr.spdaewoon.magicjar.domain.performance.ConfidenceLabel
import kr.spdaewoon.magicjar.domain.performance.StrategyConfidence
import kr.spdaewoon.magicjar.domain.performance.StrategyConfidenceCalculator
import kr.spdaewoon.magicjar.domain.performance.StrategyDailyPerformance
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 전략 성과 히스토리 + 메모 REST 서비스 — spec `2026-06-16-strategy-daily-performance.md` §6 정합.
 *
 * 읽기 전용(메모 UPSERT 제외)·새 KIS 호출 0. 영속(strategy_daily_performance/note)만 의존하므로 배치(웨이브 B)
 * 미가동 시에도 빈 응답으로 정상 동작한다(mock 합성 금지 — feedback_no_mock_data).
 *
 * Repository default fun 함정(헌법 §6) 회피: 집계·신뢰도·레짐 그룹핑·UPSERT 로직은 모두 본 서비스 private fun 에.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (spec §6)
 */
@Service
class StrategyPerformanceService(
    private val performanceRepository: StrategyDailyPerformanceJpaRepository,
    private val noteRepository: StrategyDailyNoteJpaRepository,
) {
    /**
     * 전략 성과 히스토리 — 일자별 성과 행(최신일 우선).
     *
     * 지수 OHLC 조인(market_index_daily)은 웨이브 B/후속에서 보강 — 본 PR 은 perf 행의 benchmark/alpha/regime
     * 컬럼만 그대로 내린다.
     */
    fun getHistory(
        strategyId: Long,
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): StrategyPerformanceHistoryResponse {
        val rows =
            performanceRepository
                .findByStrategyIdAndTradeDateBetweenOrderByTradeDateDesc(strategyId, from, to)
                .filter { it.userId == userId }
                .map { toRowDto(it) }
        return StrategyPerformanceHistoryResponse(
            strategyId = strategyId,
            from = from,
            to = to,
            rows = rows,
        )
    }

    /**
     * 전략 성과 누적 요약 — 평균 수익률·승률 + 신뢰도(도메인 계산기) + 레짐별 승률 + 평균 α.
     *
     * 데이터 0건이면 hasData=false, 통계는 null/0/빈배열(LOW 신뢰도 0건).
     */
    fun getSummary(
        strategyId: Long,
        userId: String,
    ): StrategyPerformanceSummaryResponse {
        val entities = performanceRepository.findByUserIdAndStrategyId(userId, strategyId)

        val totalRoundTrips = entities.sumOf { it.roundTripCount }
        val tradingDays = entities.count { it.roundTripCount > 0 }
        val totalWins = entities.sumOf { it.winCount }
        val totalLosses = entities.sumOf { it.lossCount }
        val totalRealizedPnl = entities.fold(BigDecimal.ZERO) { acc, e -> acc + e.realizedPnl }

        val confidence = StrategyConfidenceCalculator.compute(entities.map { toDomain(it) })

        return StrategyPerformanceSummaryResponse(
            strategyId = strategyId,
            hasData = entities.isNotEmpty(),
            totalRoundTrips = totalRoundTrips,
            tradingDays = tradingDays,
            totalWins = totalWins,
            totalLosses = totalLosses,
            winRatePct = winRate(totalWins, totalLosses),
            totalRealizedPnl = totalRealizedPnl,
            avgDailyReturnPct = confidence.meanReturnPct,
            avgAlphaPct = avgAlpha(entities),
            confidence = toConfidenceDto(confidence),
            regimeBreakdown = regimeBreakdown(entities),
        )
    }

    /** 전략 메모 타임라인 — 일자별 메모(최신일 우선). */
    fun getNotes(
        strategyId: Long,
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): StrategyNoteTimelineResponse {
        val notes =
            noteRepository
                .findByUserIdAndStrategyIdAndTradeDateBetweenOrderByTradeDateDesc(userId, strategyId, from, to)
                .map { toNoteDto(it) }
        return StrategyNoteTimelineResponse(
            strategyId = strategyId,
            from = from,
            to = to,
            notes = notes,
        )
    }

    /** 전략×일자 메모 UPSERT — 있으면 수정(updated_at 갱신), 없으면 생성. */
    @Transactional
    fun upsertNote(
        strategyId: Long,
        userId: String,
        tradeDate: LocalDate,
        request: StrategyNoteUpsertRequest,
    ): StrategyNoteDto {
        val tags = request.tags ?: emptyList()
        val existing = noteRepository.findByUserIdAndStrategyIdAndTradeDate(userId, strategyId, tradeDate)
        val now = Instant.now()
        val entity =
            StrategyDailyNoteJpaEntity(
                id = existing?.id,
                userId = userId,
                strategyId = strategyId,
                tradeDate = tradeDate,
                noteText = request.noteText,
                tags = tags,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        return toNoteDto(noteRepository.save(entity))
    }

    // --- private helpers (헌법 §6) ---

    private fun toRowDto(e: StrategyDailyPerformanceJpaEntity): StrategyPerformanceRowDto =
        StrategyPerformanceRowDto(
            tradeDate = e.tradeDate,
            roundTripCount = e.roundTripCount,
            winCount = e.winCount,
            lossCount = e.lossCount,
            winRatePct = winRateOfDay(e.winCount, e.roundTripCount),
            realizedPnl = e.realizedPnl,
            totalCostBasis = e.totalCostBasis,
            returnPct = e.returnPct,
            benchmarkIndexCode = e.benchmarkIndexCode,
            benchmarkChangePct = e.benchmarkChangePct,
            alphaPct = e.alphaPct,
            marketRegime = e.marketRegime,
        )

    private fun toDomain(e: StrategyDailyPerformanceJpaEntity): StrategyDailyPerformance =
        StrategyDailyPerformance(
            strategyId = e.strategyId,
            tradeDate = e.tradeDate,
            roundTripCount = e.roundTripCount,
            winCount = e.winCount,
            lossCount = e.lossCount,
            realizedPnl = e.realizedPnl,
            totalCostBasis = e.totalCostBasis,
            returnPct = e.returnPct,
        )

    private fun toConfidenceDto(c: StrategyConfidence): StrategyConfidenceDto =
        StrategyConfidenceDto(
            label = c.label.name,
            labelText = confidenceLabelText(c.label, c.sampleRoundTrips),
            sampleRoundTrips = c.sampleRoundTrips,
            tradingDays = c.tradingDays,
            meanReturnPct = c.meanReturnPct,
            stdDevReturnPct = c.stdDevReturnPct,
            sharpe = c.sharpe,
        )

    private fun confidenceLabelText(
        label: ConfidenceLabel,
        sampleRoundTrips: Int,
    ): String =
        when (label) {
            ConfidenceLabel.LOW -> "신뢰도 낮음 ${sampleRoundTrips}건"
            ConfidenceLabel.MEDIUM -> "신뢰도 보통 ${sampleRoundTrips}건"
            ConfidenceLabel.HIGH -> "신뢰도 높음 ${sampleRoundTrips}건"
        }

    /** 레짐별 승률 — regime 채워진 행만 GROUP BY (지수 부재 null 레짐 제외). */
    private fun regimeBreakdown(entities: List<StrategyDailyPerformanceJpaEntity>): List<RegimeWinRateDto> =
        entities
            .filter { it.marketRegime != null }
            .groupBy { it.marketRegime!! }
            .map { (regime, rows) ->
                val wins = rows.sumOf { it.winCount }
                val losses = rows.sumOf { it.lossCount }
                RegimeWinRateDto(
                    regime = regime,
                    tradingDays = rows.count { it.roundTripCount > 0 },
                    roundTripCount = rows.sumOf { it.roundTripCount },
                    winCount = wins,
                    lossCount = losses,
                    winRatePct = winRate(wins, losses),
                )
            }.sortedByDescending { it.roundTripCount }

    /** 평균 α — alpha_pct 채워진 행 평균. 없으면 null. */
    private fun avgAlpha(entities: List<StrategyDailyPerformanceJpaEntity>): BigDecimal? {
        val alphas = entities.mapNotNull { it.alphaPct }
        if (alphas.isEmpty()) return null
        val sum = alphas.fold(BigDecimal.ZERO) { acc, v -> acc + v }
        return sum.divide(BigDecimal(alphas.size), 4, RoundingMode.HALF_UP)
    }

    /** 누적 승률 % = wins / (wins + losses) × 100. 승/패 0 이면 null (break-even 만 있을 때). */
    private fun winRate(
        wins: Int,
        losses: Int,
    ): BigDecimal? {
        val decided = wins + losses
        if (decided == 0) return null
        return BigDecimal(wins)
            .multiply(BigDecimal(100))
            .divide(BigDecimal(decided), 2, RoundingMode.HALF_UP)
    }

    /** 그날 승률 % = wins / roundTrips × 100. 왕복 0 이면 null. */
    private fun winRateOfDay(
        wins: Int,
        roundTrips: Int,
    ): BigDecimal? {
        if (roundTrips == 0) return null
        return BigDecimal(wins)
            .multiply(BigDecimal(100))
            .divide(BigDecimal(roundTrips), 2, RoundingMode.HALF_UP)
    }

    private fun toNoteDto(e: StrategyDailyNoteJpaEntity): StrategyNoteDto =
        StrategyNoteDto(
            id = e.id!!,
            strategyId = e.strategyId,
            tradeDate = e.tradeDate,
            noteText = e.noteText,
            tags = e.tags,
            createdAt = OffsetDateTime.ofInstant(e.createdAt, KST),
            updatedAt = OffsetDateTime.ofInstant(e.updatedAt, KST),
        )

    private companion object {
        private val KST = ZoneId.of("Asia/Seoul")
    }
}
