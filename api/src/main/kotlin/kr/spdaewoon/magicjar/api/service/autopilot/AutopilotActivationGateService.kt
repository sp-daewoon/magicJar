package kr.spdaewoon.magicjar.api.service.autopilot

import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.service.backtest.BacktestPersister
import kr.spdaewoon.magicjar.api.service.backtest.BacktestService
import kr.spdaewoon.magicjar.domain.backtest.BacktestPassCriteria
import kr.spdaewoon.magicjar.domain.backtest.BacktestPassMetrics
import kr.spdaewoon.magicjar.domain.backtest.BacktestPassResult
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.candle.CandleConfidence
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * magicjar-autopilot **전략 ACTIVE 검증 게이트** — ADR 0133 §3-B (#5 · R2b).
 *
 * ## 책임 (전략 단위 게이트 · §3-B2)
 * owner=AUTOPILOT 전략을 ACTIVE 로 승격(자본 노출)하기 전, 그 전략 룰로 INTRADAY_REALISTIC 백테스트(ADR 0132)를
 * 돌려 [BacktestPassCriteria] 전부(AND)를 만족할 때만 ACTIVE 를 유지한다. 사용자 게이트
 * ([kr.spdaewoon.magicjar.api.service.strategy.StrategyService.activationBlockedReason] → `existsAutoActivationReadyRun`)
 * 와 동일 규율을 autopilot 자동 경로에 **매일 자동·반복** 적용한다.
 *
 * ## 판정 → 상태 전이 (§3-B3)
 * - **PASS** → status 유지 ACTIVE + backtest_run **SUCCEEDED 영속**([BacktestPersister.persist]).
 *   영속된 SUCCEEDED run 이 `existsAutoActivationReadyRun` 을 충족 → 두 게이트가 "성공한 백테스트 run" 으로 단일 수렴.
 * - **FAIL** → status **BUY_BLOCKED**(매수만 억제 · 보유 lot 은 hold-until-exit · 강제 청산 0 — ADR 0131 정합·자본 안전).
 *
 * ## fail-safe (§4 · 거짓 PASS 0)
 * 백테스트 [BacktestResult.Aborted](NO_CANDLE_DATA·TIMEOUT·EVALUATION_ERROR) 또는 평가 중 예외 → **보수적 FAIL**
 * ([BacktestPassResult.error]/[BacktestPassResult.insufficientData]) + WARN SystemEvent. 미검증 종목을 ACTIVE 로
 * 방치하지 않는다(silent 진행 0 — 헌법 §11-2).
 *
 * ## 게이트 (default OFF · §3-B5)
 * - `magicjar.autopilot.activation-gate.enabled` default **false**. off → [evaluateStrategy] 호출 0
 *   ([AutopilotUniverseRefreshService] 가 enabled 일 때만 본 서비스를 호출). 게이트 off = ADR 0131 거동 byte-동일.
 * - owner 격리: 본 서비스는 owner=AUTOPILOT 전략만 입력으로 받는다([StrategyJpaRepository.findAutopilotEvaluableStrategies]).
 *   USER/SYSTEM 전략은 본 경로에 진입 0.
 *
 * ## 결정성 (멱등)
 * 같은 codes·같은 lookback·같은 candle DB → 같은 백테스트 → 같은 판정. universe refresh 가 1일 1회 멱등이므로
 * 본 검증도 1일 1회. 임계치는 ENV 단일 출처([criteria])라 drift 0.
 *
 * KIS 호출은 백테스트 backfill(INTRADAY_REALISTIC + BacktestCandlePrepareApiAdapter)의 `BACKFILL` priority 경유 —
 * 라이브 매매 쿼터 침범 0(개장 전 08:35 수행 · 시간 분리).
 */
@Service
class AutopilotActivationGateService(
    private val backtestService: BacktestService,
    private val backtestPersister: BacktestPersister,
    private val strategyJpaRepository: StrategyJpaRepository,
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
    @Value("\${magicjar.autopilot.activation-gate.enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${magicjar.autopilot.activation-gate.min-trades:20}")
    private val minTrades: Int = BacktestPassCriteria.DEFAULT_MIN_TRADES,
    @Value("\${magicjar.autopilot.activation-gate.min-win-rate:0.40}")
    private val minWinRate: BigDecimal = BacktestPassCriteria.DEFAULT_MIN_WIN_RATE,
    @Value("\${magicjar.autopilot.activation-gate.min-expectancy:0}")
    private val minExpectancy: BigDecimal = BacktestPassCriteria.DEFAULT_MIN_EXPECTANCY,
    @Value("\${magicjar.autopilot.activation-gate.max-mdd:0.15}")
    private val maxMdd: BigDecimal = BacktestPassCriteria.DEFAULT_MAX_MDD,
    @Value("\${magicjar.autopilot.activation-gate.require-confidence:OK}")
    private val requireConfidenceRaw: String = "OK",
    @Value("\${magicjar.autopilot.activation-gate.lookback-days:30}")
    private val lookbackDaysRaw: Int = DEFAULT_LOOKBACK_DAYS,
    @Value("\${magicjar.autopilot.activation-gate.interval:1m}")
    private val interval: String = DEFAULT_INTERVAL,
    @Value("\${magicjar.autopilot.activation-gate.initial-capital:10000000}")
    private val initialCapitalRaw: BigDecimal = DEFAULT_INITIAL_CAPITAL,
) {
    private val log = LoggerFactory.getLogger(AutopilotActivationGateService::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    companion object {
        const val DEFAULT_LOOKBACK_DAYS: Int = 30
        const val DEFAULT_INTERVAL: String = "1m"
        val DEFAULT_INITIAL_CAPITAL: BigDecimal = BigDecimal("10000000")
        private const val ACTOR = "AutopilotActivationGateService"
        private const val EXECUTION_MODE = "INTRADAY_REALISTIC"
    }

    /** ENV 임계치를 단일 [BacktestPassCriteria] 값객체로 응집(R1 도메인 위임 — caller boolean 곱 0). */
    private val criteria: BacktestPassCriteria by lazy {
        BacktestPassCriteria(
            minTrades = minTrades.coerceAtLeast(0),
            minWinRate = minWinRate,
            minExpectancy = minExpectancy,
            maxMdd = maxMdd,
            requireConfidence = parseConfidence(requireConfidenceRaw),
        )
    }

    /** 게이트 활성 여부 — universe refresh 가 검증 단계 append 여부를 본 값으로 판단. */
    fun isEnabled(): Boolean = enabled

    /**
     * 게이트가 사용하는 INTRADAY_REALISTIC 백테스트 interval(검증·로그 단언용).
     */
    fun interval(): String = interval

    /**
     * owner=AUTOPILOT 평가 전략 1건을 검증해 PASS/FAIL 판정 후 상태 전이까지 수행한다.
     *
     * 전체가 fail-safe: 백테스트 Aborted·예외 → 보수 FAIL(BUY_BLOCKED). 거짓 PASS 0.
     *
     * @param strategy owner=AUTOPILOT 평가 전략 엔티티(codes 갱신 후). caller 가 owner 격리 보장.
     * @param snapshotDate 검증 기준일(= 오늘 KST · backtest [to]). lookback 만큼 과거가 [from].
     * @return 판정 결과([BacktestPassResult]).
     */
    fun evaluateStrategy(
        strategy: StrategyJpaEntity,
        snapshotDate: LocalDate,
    ): BacktestPassResult {
        val strategyId = strategy.id
        if (strategyId == null) {
            log.warn("autopilot 검증 게이트 skip — 저장되지 않은 전략(id=null)")
            return BacktestPassResult.error()
        }

        val codes = parseCodes(strategy.codes)
        if (codes.isEmpty()) {
            // codes 0 = 평가 대상 없음 → 보수 FAIL(BUY_BLOCKED). universe refresh fail-safe(빈 universe 미갱신)와 결합.
            log.warn("autopilot 검증 게이트 보수 FAIL — codes 0(평가 대상 없음): strategyId={}", strategyId)
            val result = BacktestPassResult.insufficientData()
            applyVerdict(strategy, result, snapshotDate, codesCount = 0)
            return result
        }

        val to = snapshotDate
        val from = to.minusDays(lookbackDaysRaw.coerceAtLeast(1).toLong())

        val backtest: BacktestResult? =
            try {
                backtestService.runBacktest(
                    strategyId = strategyId,
                    universe = codes,
                    interval = interval,
                    fromStr = from.toString(),
                    toStr = to.toString(),
                    initialCapital = initialCapitalRaw,
                    executionMode = EXECUTION_MODE,
                    backfillIfMissing = true,
                )
            } catch (e: Exception) {
                // fail-safe — 백테스트 자체 예외(데이터·엔진 오류) 시 거짓 PASS 0 보수 FAIL.
                log.warn(
                    "autopilot 검증 게이트 백테스트 예외(보수 FAIL): strategyId={} err={}",
                    strategyId,
                    e.message,
                    e,
                )
                null
            }

        val result = if (backtest == null) BacktestPassResult.error() else evaluateResult(strategyId, backtest)

        // PASS 시에만 backtest_run SUCCEEDED 영속 → existsAutoActivationReadyRun 충족(두 게이트 단일 수렴).
        // FAIL 백테스트 결과는 영속하지 않는다(매일 자동 재검증이라 누적 noise·잘못된 게이트 충족 회피).
        if (result.passed && backtest != null) {
            persistRun(strategy, backtest)
        }

        applyVerdict(strategy, result, snapshotDate, codesCount = codes.size)
        return result
    }

    /**
     * [BacktestResult] → [BacktestPassResult] 판정 (PASS/FAIL · fail-safe).
     *
     * Success → [BacktestPassMetrics] 합성 후 [BacktestPassCriteria.evaluate]. Aborted → 보수 FAIL.
     */
    internal fun evaluateResult(
        strategyId: Long,
        backtest: BacktestResult,
    ): BacktestPassResult =
        when (backtest) {
            is BacktestResult.Success -> {
                val perf = backtest.performance
                // net expectancy = winRate·avgWin − (1−winRate)·avgLoss (ADR 0132 비용 모델 반영된 net 평균).
                val metrics =
                    BacktestPassMetrics.fromWinLoss(
                        trades = perf.totalTrades,
                        winRate = perf.winRate,
                        avgWin = perf.avgWin,
                        avgLoss = perf.avgLoss,
                        maxDrawdown = perf.maxDrawdown,
                        confidence = backtest.confidence,
                    )
                criteria.evaluate(metrics)
            }

            is BacktestResult.Aborted -> {
                // NO_CANDLE_DATA·TIMEOUT·EVALUATION_ERROR — 결과 신뢰 불가 → 보수 FAIL(거짓 PASS 0).
                log.warn(
                    "autopilot 검증 게이트 백테스트 Aborted(보수 FAIL): strategyId={} reason={}",
                    strategyId,
                    backtest.reason,
                )
                if (backtest.reason == "NO_CANDLE_DATA") {
                    BacktestPassResult.insufficientData()
                } else {
                    BacktestPassResult.error()
                }
            }
        }

    /**
     * 판정 → 상태 전이 + SystemEvent (backtest_run 영속은 [evaluateStrategy] 가 PASS 시 선행).
     *
     * - PASS → status ACTIVE 보장(BUY_BLOCKED 였으면 복구). INFO event.
     * - FAIL → status BUY_BLOCKED(매수 억제 · 보유 lot hold-until-exit · 강제 청산 0). WARN event.
     *
     * 본 전이는 codes 갱신과 별도(강제 청산 0). 같은 영업일 재실행도 멱등(같은 판정·같은 status).
     */
    private fun applyVerdict(
        strategy: StrategyJpaEntity,
        result: BacktestPassResult,
        snapshotDate: LocalDate,
        codesCount: Int,
    ) {
        val strategyId = requireNotNull(strategy.id)
        val metricsJson = buildMetricsJson(result)
        if (result.passed) {
            if (strategy.status != StrategyStatus.ACTIVE.name) {
                strategyJpaRepository.updateStatus(strategyId, StrategyStatus.ACTIVE.name)
                log.info("autopilot 검증 PASS — BUY_BLOCKED→ACTIVE 복구: strategyId={}", strategyId)
            }
            emit(
                SystemEventSeverity.INFO,
                "ACTIVATION_GATE_PASS",
                """{"snapshotDate":"$snapshotDate","strategyId":$strategyId,"codes":$codesCount,""" +
                    """"verdict":"PASS","metrics":$metricsJson}""",
            )
        } else {
            strategyJpaRepository.markBuyBlocked(strategyId, "ACTIVATION_GATE_FAIL")
            val reasons = result.failReasons.joinToString(",") { it.name }
            log.warn(
                "autopilot 검증 FAIL — BUY_BLOCKED(보유 lot hold-until-exit): strategyId={} reasons={}",
                strategyId,
                reasons,
            )
            emit(
                SystemEventSeverity.WARN,
                "ACTIVATION_GATE_FAIL",
                """{"snapshotDate":"$snapshotDate","strategyId":$strategyId,"codes":$codesCount,""" +
                    """"verdict":"FAIL","failReasons":${result.failReasons.map { "\"${it.name}\"" }},""" +
                    """"metrics":$metricsJson}""",
            )
        }
    }

    /**
     * PASS 시 backtest_run SUCCEEDED 영속 — existsAutoActivationReadyRun 충족(두 게이트 단일 수렴).
     *
     * userId 가 null(이론상 owner=AUTOPILOT 시드) 이면 영속 skip(게이트 충족 불가 — fail-soft). 영속 예외도 fail-soft
     * (검증 자체는 ACTIVE 유지하되 다음 검증에서 재충족 시도 — 자본 안전엔 영향 없음).
     */
    private fun persistRun(
        strategy: StrategyJpaEntity,
        backtest: BacktestResult,
    ): Long? {
        val userId = strategy.userId?.toString()
        if (userId == null) {
            log.warn("autopilot 검증 PASS run 영속 skip — userId null: strategyId={}", strategy.id)
            return null
        }
        return runCatching { backtestPersister.persist(userId, backtest) }
            .onFailure { e -> log.warn("autopilot 검증 PASS run 영속 실패: strategyId={} err={}", strategy.id, e.message) }
            .getOrNull()
    }

    private fun buildMetricsJson(result: BacktestPassResult): String {
        val m = result.metrics ?: return "null"
        return """{"trades":${m.trades},"winRate":${m.winRate},"netExpectancy":${m.netExpectancy},""" +
            """"maxDrawdown":${m.maxDrawdown},"confidence":"${m.confidence.name}"}"""
    }

    private fun parseConfidence(raw: String): CandleConfidence =
        runCatching { CandleConfidence.valueOf(raw.trim().uppercase()) }
            .getOrElse {
                log.warn("activation-gate.require-confidence 값 인식 불가('{}') → OK 보수 적용", raw)
                CandleConfidence.OK
            }

    private fun parseCodes(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            raw
                .trim()
                .removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.trim().trim('"') }
                .filter { it.isNotBlank() }
        }.getOrElse {
            log.warn("autopilot codes 파싱 실패(skip): raw={}", raw)
            emptyList()
        }
    }

    private fun emit(
        severity: SystemEventSeverity,
        action: String,
        detailJson: String,
    ) {
        val logger = systemEventLoggerProvider.getIfAvailable() ?: return
        runCatching {
            logger.emit(
                SystemEvent(
                    category = SystemEventCategory.OTHER,
                    severity = severity,
                    actor = ACTOR,
                    action = action,
                    userId = null,
                    detailJson = detailJson,
                ),
            )
        }.onFailure { e -> log.error("autopilot 검증 게이트 SystemEvent emit 실패: action={} err={}", action, e.message) }
    }
}
