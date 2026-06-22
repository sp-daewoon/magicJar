package kr.spdaewoon.magicjar.api.controller.autopilot

import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceTradeDto
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.service.performance.PerformanceService
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * owner=AUTOPILOT scoped 실현손익 조회 서비스 (개선 F · 이슈 #12).
 *
 * ## 왜 분리했나 (SRP)
 * [PerformanceService] 는 **계좌 전체** 성과 + FIFO replay 엔진(단일 진실원)이다. 본 서비스는 그 위에서
 * **autopilot 전략만 격리**하는 얇은 scoping·집계 레이어다. PerformanceService 의 replay 엔진을
 * [PerformanceService.getTradeBreakdownForStrategies] 로 재사용(DRY)하고, 계좌 전체 API·KIS 정합은 무변경(추가만).
 *
 * ## owner 격리
 * order_request.strategy_id ∈ {owner=AUTOPILOT 전략 id(DELETED 포함)} 인 체결만 집계.
 * 수동 주문·USER 전략·오버나잇 후성(093370 수동청산)은 제외 — autopilot 진짜 성과만 본다.
 *
 * ## KIS 정합 한계 (명시)
 * KIS 계좌 손익은 **계좌 레벨**이라 autopilot/비-autopilot 정확 분리가 불가하다. 본 값은 DB 체결 FIFO 기반
 * owner-scoped 추정치이며, KIS net 에서 후성 등 비-autopilot 을 빼면 정합한다([kisScopingNote] 로 응답에 명시).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 조회 (ADR 0023 §2-4 정합)
 * // KIS 호출 0 — DB 체결 reuse
 */
@Service
class AutopilotPerformanceService(
    private val performanceService: PerformanceService,
    private val strategyRepository: StrategyJpaRepository,
    private val userAccountRepository: UserAccountJpaRepository,
) {
    private val log = LoggerFactory.getLogger(AutopilotPerformanceService::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    companion object {
        private const val OWNER_AUTOPILOT = "AUTOPILOT"
        private val VALID_PERIODS = setOf("DAY", "WEEK", "MONTH", "YEAR", "ALL")

        // 손익비 상한 — 손실 0(전부 이익)일 때 무한대 대신 표기.
        private val PL_RATIO_CAP = BigDecimal("999.99")
        private const val KIS_SCOPING_NOTE =
            "owner-scoped (DB FIFO). KIS 계좌정합은 별도(계좌 레벨) — KIS net 에서 비-autopilot(후성 등) 제외 시 정합."
    }

    /**
     * owner=AUTOPILOT 실현손익 조회.
     *
     * @param period DAY·WEEK·MONTH·YEAR·ALL (default DAY — autopilot 모니터링은 당일이 1차 관심).
     * @param mode 기본 REAL(운영). null 이면 전체 모드.
     */
    @Transactional(readOnly = true)
    fun performance(
        period: String,
        mode: TradingMode? = TradingMode.REAL,
    ): AutopilotPerformanceResponse {
        val validPeriod = validatePeriod(period)
        val userId =
            resolveUserId()
                ?: return emptyResponse(validPeriod, mode, scopedStrategyCount = 0)

        val strategyIds = strategyRepository.findIdsByOwner(OWNER_AUTOPILOT).toSet()
        if (strategyIds.isEmpty()) {
            log.info("autopilot performance — owner=AUTOPILOT 전략 0건. 0 응답.")
            return emptyResponse(validPeriod, mode, scopedStrategyCount = 0)
        }

        val (from, to) = resolvePeriodRange(validPeriod)
        val items = performanceService.getTradeBreakdownForStrategies(userId, from, to, strategyIds, mode)
        return aggregate(validPeriod, from, to, mode, strategyIds.size, items)
    }

    // --- private helpers ---

    private fun aggregate(
        period: String,
        from: LocalDate,
        to: LocalDate,
        mode: TradingMode?,
        scopedStrategyCount: Int,
        items: List<PerformanceTradeDto>,
    ): AutopilotPerformanceResponse {
        val realizedPnl =
            items.fold(BigDecimal.ZERO) { acc, item -> acc + item.realizedPnl }.setScale(2, RoundingMode.HALF_UP)
        // follow-up H: gross = net + fee + tax → net 과 함께 노출(과대표시 방지 + 누적 비용 가시화).
        val grossRealizedPnl =
            items
                .fold(BigDecimal.ZERO) { acc, item -> acc + item.realizedPnl + item.fee + item.tax }
                .setScale(2, RoundingMode.HALF_UP)
        val wins = items.filter { it.realizedPnl > BigDecimal.ZERO }
        val losses = items.filter { it.realizedPnl < BigDecimal.ZERO }

        val byCode =
            items
                .groupBy { it.code }
                .map { (code, rows) ->
                    AutopilotCodePnl(
                        code = code,
                        name = rows.firstNotNullOfOrNull { it.name },
                        realizedPnl =
                            rows
                                .fold(BigDecimal.ZERO) { acc, r -> acc + r.realizedPnl }
                                .setScale(2, RoundingMode.HALF_UP),
                        tradeCount = rows.size,
                    )
                }.sortedByDescending { it.realizedPnl }

        return AutopilotPerformanceResponse(
            period = period,
            periodStart = from,
            periodEnd = to,
            mode = mode?.name,
            realizedPnl = realizedPnl,
            grossRealizedPnl = grossRealizedPnl,
            tradeCount = items.size,
            winCount = wins.size,
            lossCount = losses.size,
            winRatePct = winRate(wins.size, losses.size),
            profitLossRatio = profitLossRatio(wins, losses),
            scopedStrategyCount = scopedStrategyCount,
            byCode = byCode,
            kisScopingNote = KIS_SCOPING_NOTE,
            computedAt = OffsetDateTime.ofInstant(Instant.now(), kst),
        )
    }

    private fun winRate(
        winCount: Int,
        lossCount: Int,
    ): BigDecimal {
        val denominator = winCount + lossCount
        if (denominator == 0) return BigDecimal.ZERO.setScale(2)
        return BigDecimal(winCount * 100)
            .divide(BigDecimal(denominator), 2, RoundingMode.HALF_UP)
    }

    private fun profitLossRatio(
        wins: List<PerformanceTradeDto>,
        losses: List<PerformanceTradeDto>,
    ): BigDecimal {
        if (wins.isEmpty()) return BigDecimal.ZERO.setScale(2)
        val avgProfit = average(wins.map { it.realizedPnl })
        if (losses.isEmpty()) return PL_RATIO_CAP
        val avgLoss = average(losses.map { it.realizedPnl.abs() })
        if (avgLoss.signum() == 0) return PL_RATIO_CAP
        return avgProfit.divide(avgLoss, 2, RoundingMode.HALF_UP).min(PL_RATIO_CAP)
    }

    private fun average(values: List<BigDecimal>): BigDecimal =
        values
            .fold(BigDecimal.ZERO) { acc, v -> acc + v }
            .divide(BigDecimal(values.size), 4, RoundingMode.HALF_UP)

    /** 단일 사용자 시스템 — 가장 먼저 생성된 user_account.user_id. 부재 시 null(0 응답). */
    private fun resolveUserId(): String? =
        userAccountRepository
            .findFirstUserIds(PageRequest.of(0, 1))
            .firstOrNull()
            ?: run {
                log.info("autopilot performance — user_account 부재. 0 응답.")
                null
            }

    private fun emptyResponse(
        period: String,
        mode: TradingMode?,
        scopedStrategyCount: Int,
    ): AutopilotPerformanceResponse {
        val (from, to) = resolvePeriodRange(period)
        return AutopilotPerformanceResponse(
            period = period,
            periodStart = from,
            periodEnd = to,
            mode = mode?.name,
            realizedPnl = BigDecimal.ZERO.setScale(2),
            tradeCount = 0,
            winCount = 0,
            lossCount = 0,
            winRatePct = BigDecimal.ZERO.setScale(2),
            profitLossRatio = BigDecimal.ZERO.setScale(2),
            scopedStrategyCount = scopedStrategyCount,
            byCode = emptyList(),
            kisScopingNote = KIS_SCOPING_NOTE,
            computedAt = OffsetDateTime.ofInstant(Instant.now(), kst),
        )
    }

    private fun validatePeriod(period: String): String {
        val upper = period.uppercase()
        if (upper !in VALID_PERIODS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "지원하지 않는 period 입니다: $period. 허용값: $VALID_PERIODS",
            )
        }
        return upper
    }

    /** PerformanceService 와 동일 분기 규칙(ADR 0072 §2-3 C-1) — autopilot 도 같은 기간 의미를 쓴다. */
    private fun resolvePeriodRange(period: String): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now(kst)
        return when (period) {
            "DAY" -> today to today
            "WEEK" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
            "MONTH" -> today.withDayOfMonth(1) to today
            "YEAR" -> today.withDayOfYear(1) to today
            "ALL" -> LocalDate.of(2020, 1, 1) to today
            else -> today to today
        }
    }
}
