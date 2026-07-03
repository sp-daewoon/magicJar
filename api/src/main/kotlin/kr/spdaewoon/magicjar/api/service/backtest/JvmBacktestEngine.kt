package kr.spdaewoon.magicjar.api.service.backtest

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.api.config.IntradayRealisticBacktestProperties
import kr.spdaewoon.magicjar.api.config.LegacyBacktestProperties
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.domain.backtest.BacktestEntrySkip
import kr.spdaewoon.magicjar.domain.backtest.BacktestException
import kr.spdaewoon.magicjar.domain.backtest.BacktestExecutionMode
import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestPosition
import kr.spdaewoon.magicjar.domain.backtest.BacktestRequest
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.BacktestTrade
import kr.spdaewoon.magicjar.domain.backtest.ExitParam
import kr.spdaewoon.magicjar.domain.backtest.ExitParamOverride
import kr.spdaewoon.magicjar.domain.backtest.LookaheadGuard
import kr.spdaewoon.magicjar.domain.backtest.PriceAdjustmentMode
import kr.spdaewoon.magicjar.domain.backtest.SizingPolicy
import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestCandlePreparePort
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestCommissionModel
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestEngine
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestSlippageModel
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestTaxModel
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleConfidence
import kr.spdaewoon.magicjar.domain.market.candle.CandleIntegrityReport
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategy.ResolvedEntryPolicy
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import kr.spdaewoon.magicjar.strategy.IntradayExitDecision
import kr.spdaewoon.magicjar.strategy.IntradayExitReason
import kr.spdaewoon.magicjar.strategy.IntradayExitSimulation
import kr.spdaewoon.magicjar.strategy.IntradayTradingClock
import kr.spdaewoon.magicjar.strategy.MinHoldGuard
import kr.spdaewoon.magicjar.strategy.TradePlanEvaluator
import kr.spdaewoon.magicjar.strategy.TradePlanFactory
import kr.spdaewoon.magicjar.strategy.entry.EntryPriceDecision
import kr.spdaewoon.magicjar.strategy.entry.EntryPriceInput
import kr.spdaewoon.magicjar.strategy.entry.EntryPricePolicy
import kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitDecision
import kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitSimulation
import kr.spdaewoon.magicjar.strategy.scalein.VirtualStrategyPosition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * JVM 기반 백테스트 엔진 구현체 (ADR 0056 §2-1).
 *
 * ### 의무 사항 (ADR 0047 + ADR 0056)
 * 1. 반드시 [BacktestEvaluatorBridge.evaluateBacktest] 경유 — 직접 walkRoot 호출 0건
 * 2. 캔들 전체 조회 1회 — 매 t마다 DB 재조회 절대 금지 (성능 보호)
 * 3. 룩어헤드 가드 — [LookaheadGuard.safeSlice]로 t 시점 슬라이스 생성
 * 4. timeout 1000ms: evaluatorBridge 내부 처리. StrategyTimeoutException 시 timeoutCount++
 * 5. abort quota 50: timeoutCount >= 50 시 BacktestResult.Aborted 반환
 * 6. 1차 stateless — DB 영속 0 (backlog §27-2 합류)
 * 7. candle 0건 시 BacktestResult.Aborted(reason="NO_CANDLE_DATA") 반환 (feedback_no_mock_data.md 정합)
 *
 * ### 시뮬레이션 정책 (1차)
 * - SizingPolicy.AllCapital: 매수 시 보유 현금 전액으로 1종목 매수 (최대 수량 · default).
 * - SizingPolicy.FixedFractional (ADR 0121 §2-A): 1슬롯 = min(initialCapital × positionPercent/100, maxPositionAmount ?: ∞)
 *   고정 슬롯으로 매수 — 1회 진입 자본 노출을 positionPercent로 제한해 MDD를 분산 포트폴리오에 근사 환산.
 *   슬롯 base는 initialCapital 고정(복리 부풀림 차단). 잔여 cash 부족 시 가용 cash로 클램프(skip 아님).
 *   maxConcurrent는 1차에서 메타 echo만(동시보유 시뮬은 post-1차 — §2-2 LOCK).
 * - 1 universe × 1 종목 순차 진행 (병렬은 post-MVP)
 * - 포지션 중복 불가 — 보유 중 BUY 신호 무시
 * - KOSPI 가정 (세금 모델 market 파라미터)
 *
 * ### equity curve = mark-to-market (ADR 0056 §2-7 amend — MDD 결함 fix)
 * equity(t) = cash(t) + Σ(보유 포지션 수량 × 그 시점 종가)로 **매 캔들마다 1점**씩 기록한다.
 * 종전 cash-only 곡선(매 체결 후 cash만 append)은 AllCapital 매수 직후 cash≈0 → equity≈0이 되어
 * 보유 구간 내내 drawdown≈100% 아티팩트를 만들어 MDD 게이트(G3)를 무력화했다(V76 검증 #860 실측).
 * MTM 곡선은 미실현 손익을 포함하므로 MDD·Sharpe가 실제 자본 변동을 반영한다.
 * totalReturn·CAGR·winRate·profitFactor 등 cash·trades 기반 지표는 무영향(MDD·Sharpe만 정정).
 */
@Component
class JvmBacktestEngine(
    private val evaluatorBridge: BacktestEvaluatorBridge,
    private val strategyRepository: StrategyRepositoryPort,
    private val candleJpaRepository: CandleJpaRepository,
    private val modelRegistry: BacktestModelRegistry,
    private val performanceCalculator: BacktestPerformanceCalculator,
    /** 초단타 정합 백테스트 게이트(ADR 0132 §2-B · R2b) — default off 시 INTRADAY_REALISTIC 요청은 LEGACY 강등. */
    private val intradayProperties: IntradayRealisticBacktestProperties = IntradayRealisticBacktestProperties(),
    /** LEGACY 정합 옵션(2026-07-03 감사 §6 P2-1·P2-2) — 다음 봉 open 체결(opt-in) + 정규장 필터(default on). */
    private val legacyProperties: LegacyBacktestProperties = LegacyBacktestProperties(),
    /**
     * 분봉 선수집 + 정합성 보고 포트(ADR 0132 §1-E · R2a market-data 구현).
     *
     * R2a 머지 전에는 빈(null)일 수 있으므로 [Autowired] required=false 로 선택 주입한다(엔진은 인터페이스만 의존 ·
     * R2b/R2a 병렬 LOCK). 포트가 없으면 INTRADAY_REALISTIC 은 DB 기존 봉만으로 정합성 평가(backfill 0).
     */
    @Autowired(required = false)
    private val candlePreparePort: BacktestCandlePreparePort? = null,
) : BacktestEngine {
    private val log = LoggerFactory.getLogger(JvmBacktestEngine::class.java)

    companion object {
        /** abort quota: 누적 timeout 50회 시 백테스트 중단 (ADR 0047 §2-5 + ADR 0056 §2-5) */
        private const val ABORT_TIMEOUT_QUOTA = 50

        /** 기본 시장 구분 — 1차는 KOSPI 가정 */
        private const val DEFAULT_MARKET = "KOSPI"

        private val ZERO = BigDecimal.ZERO

        /** 봉 ts(KST) 산출 타임존 — EOD/blackout 시간 판정(ADR 0132 §D1-3). */
        private val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        /** 정규장 시작/종료 KST — MarketSession.Regular 창(ADR 0013) echo. LEGACY 정규장 필터(감사 §6 P2-2). */
        private val REGULAR_SESSION_OPEN_KST: LocalTime = LocalTime.of(9, 0)
        private val REGULAR_SESSION_CLOSE_KST: LocalTime = LocalTime.of(15, 30)
    }

    /** LEGACY 다음 봉 open 체결 대기 신호 (감사 §6 P2-1 · opt-in fill-at-next-open). */
    private data class PendingLegacyFill(
        val side: TradeSide,
        val signalReason: Map<String, Any>,
    )

    override fun run(request: BacktestRequest): BacktestResult {
        // ── 모드 분기 (ADR 0132 §4-A R2b) ──────────────────────────────────────
        // INTRADAY_REALISTIC 은 ENV 게이트(magicjar.backtest.intraday-realistic.enabled)가 ON 일 때만 발화한다.
        // 게이트 OFF(default) 면 요청 모드와 무관하게 LEGACY 로 강등 — 신규 경로 차단(opt-in · zero-regression LOCK).
        val intradayActive =
            request.executionMode == BacktestExecutionMode.INTRADAY_REALISTIC && intradayProperties.enabled
        if (request.executionMode == BacktestExecutionMode.INTRADAY_REALISTIC && !intradayProperties.enabled) {
            log.info(
                "백테스트 INTRADAY_REALISTIC 요청이나 게이트 OFF(magicjar.backtest.intraday-realistic.enabled=false) — LEGACY 로 강등: strategyId={}",
                request.strategyId,
            )
        }
        return if (intradayActive) runIntradayRealistic(request) else runLegacy(request)
    }

    /**
     * LEGACY 백테스트 (ADR 0056 — 기존 동작 byte-동일 · zero-regression LOCK).
     *
     * DSL sell + scale-in/exit 경로 그대로. 본 메서드는 [run] 분기 도입(ADR 0132 §4-A R2b) 전 원본 본문을 그대로 옮긴 것이다.
     */
    private fun runLegacy(request: BacktestRequest): BacktestResult {
        val startMs = System.currentTimeMillis()
        val executedAt = Clock.System.now()

        log.info(
            "백테스트 시작: strategyId={}, universe={}, interval={}, from={}, to={}, initialCapital={}",
            request.strategyId,
            request.universe,
            request.interval,
            request.from,
            request.to,
            request.initialCapital,
        )

        // 1. 전략 조회
        val strategy =
            strategyRepository.findById(request.strategyId)
                ?: throw BacktestException.StrategyNotFoundException(request.strategyId)

        // 민감도 sweep override(spec §3-R3) — 청산 knob 1개를 메모리에서만 치환(DB write 0).
        // null(기본·단일 run)이면 전략 정의 그대로. buy/sell DSL AST는 불변 — 청산 시뮬만 영향.
        val definition = applyExitParamOverride(strategy.definition, request.exitParamOverride)
        val dslDef: DslStrategyDefinition = definition.dsl

        val buyAst: DslNode =
            dslDef.buyCondition
                ?: throw BacktestException.InvalidStrategyDslException(request.strategyId, "buyCondition (진입 조건)이 없습니다.")
        val sellAst: DslNode? = dslDef.sellCondition

        // scale-in/exit 활성 전략(spec §6-3) — live·리플레이와 동일하게 ScaleInExitCalculator 단일 함수 경유.
        val scaleInExitActive =
            ScaleInExitSimulation.isActive(definition.scaleIn, definition.partialExits)

        // 진입 정책(ADR 0114 R3) — 전략에 entryPolicy가 있으면 백테스트 진입도 live와 동일한
        // EntryPricePolicy.evaluate 순수 함수로 캡 판정한다(호가 부재로 캡 게이트만 의미 있음 — 근사 한계 LOCK).
        // 미설정 전략은 null → 기존 즉시 체결 모델 그대로(하위호환 LOCK, 스킵 0).
        val resolvedEntryPolicy: ResolvedEntryPolicy? =
            definition.entryPolicy?.resolved(ResolvedEntryPolicy.DEFAULTS)

        // 2. 모델 lookup
        val taxModel: BacktestTaxModel = modelRegistry.taxModel(request.taxModel)
        val commissionModel: BacktestCommissionModel = modelRegistry.commissionModel(request.commissionModel)
        val slippageModel: BacktestSlippageModel = modelRegistry.slippageModel(request.slippageModel)

        // 3. 캔들 조회 (전체 universe × range 1회 — 매 t마다 재조회 금지)
        val interval = CandleInterval.fromCode(request.interval)
        val seoulTz = TimeZone.of("Asia/Seoul")
        val fromInstant: Instant =
            LocalDateTime(request.from.year, request.from.monthNumber, request.from.dayOfMonth, 0, 0)
                .toInstant(seoulTz)
                .toJavaInstant()
        val toInstant: Instant =
            LocalDateTime(request.to.year, request.to.monthNumber, request.to.dayOfMonth, 23, 59, 59)
                .toInstant(seoulTz)
                .toJavaInstant()

        // 4. 시뮬레이션 상태 초기화
        val trades = mutableListOf<BacktestTrade>()
        val positions = mutableListOf<BacktestPosition>()
        val equityCurve = mutableListOf<BigDecimal>(request.initialCapital)
        var cash = request.initialCapital
        var timeoutCount = 0
        // 진입 정책 스킵 집계(ADR 0114 R3) — 사유별 누적. add-only 결과 노출.
        var entrySkip = BacktestEntrySkip.NONE

        // 포지션 상태 (code → BacktestPosition, 1차: 단일 포지션)
        val openPositions = mutableMapOf<String, BacktestPosition>()

        // scale-in/exit 가상 lot 상태 (code → filledLots·executedExitStages·직전 회차 체결가) — 분할매수 R3.
        // ScaleInExitCalculator가 읽는 평단/진행도/발화단계를 백테스트 동안 추적한다(BacktestPosition은 단일 평단만 보유).
        val lotStates = mutableMapOf<String, ScaleInLotState>()

        // 5. universe 순회 시뮬레이션
        for (code in request.universe) {
            val entities: List<CandleJpaEntity> =
                candleJpaRepository.findByCodeAndIntervalAndTsBetween(
                    code = code,
                    interval = interval.code,
                    fromTs = fromInstant,
                    toTs = toInstant,
                )

            if (entities.isEmpty()) {
                log.warn("캔들 데이터 없음: code={}, interval={}, from={}, to={}", code, interval.code, request.from, request.to)
                val durationMs = System.currentTimeMillis() - startMs
                return BacktestResult.Aborted(
                    request = request,
                    partialTrades = trades.toList(),
                    reason = "NO_CANDLE_DATA",
                    timeoutCount = timeoutCount,
                    executedAt = executedAt,
                    durationMs = durationMs,
                )
            }

            val allCandles: List<Candle> = entities.map { toDomain(it, request.priceAdjustmentMode) }

            // LEGACY 정합 보강 상태(감사 §2-② · §6 P2-1) — 진입 봉 index(minHold 봉 수 산정) + 다음 봉 open 체결 pending.
            var entryBarIndex: Int? = null
            var pendingNextOpenFill: PendingLegacyFill? = null

            // 6. 시간 슬라이스 진행 (t = 0 .. allCandles.size-1)
            for (t in allCandles.indices) {
                // 룩어헤드 가드 — t 시점까지 슬라이스만 전달
                val slice = LookaheadGuard.safeSlice(allCandles, t)
                val currentCandle = allCandles[t]
                val rawPrice = currentCandle.closeRaw
                val evalPrice =
                    if (request.priceAdjustmentMode == PriceAdjustmentMode.ADJUSTED) {
                        currentCandle.closeAdj ?: rawPrice
                    } else {
                        rawPrice
                    }

                // ── 정규장 필터 (감사 §6 P2-2) — 분·시 봉(≤60m)의 정규장(09:00~15:30 KST) 밖 봉은 신호 평가/체결 skip.
                //    라이브 market-hours gate default(BUY·SELL 모두 정규장만)와 정합. 일봉 이상은 ts 가 장중 시각
                //    의미가 없어 필터 미적용(오적용 방지). off = magicjar.backtest.legacy.regular-session-only=false.
                if (legacyProperties.regularSessionOnly && isIntradayInterval(interval)) {
                    val barTimeKst =
                        currentCandle.ts
                            .toJavaInstant()
                            .atZone(SEOUL_ZONE)
                            .toLocalTime()
                    if (!isRegularSessionKst(barTimeKst)) {
                        // 장외 봉으로 이월 체결 금지 — pending 은 폐기(다음 정규장 봉에서 재신호에 위임 · 보수).
                        pendingNextOpenFill = null
                        equityCurve.add(markToMarketEquity(cash, openPositions, evalPrice))
                        continue
                    }
                }

                // ── 다음 봉 open 체결 (감사 §6 P2-1 · opt-in fill-at-next-open) — 직전 봉 신호를 이 봉 open 으로 체결.
                pendingNextOpenFill?.let { pending ->
                    pendingNextOpenFill = null
                    val openRaw = currentCandle.openRaw
                    val openEval =
                        if (request.priceAdjustmentMode == PriceAdjustmentMode.ADJUSTED) {
                            currentCandle.openAdj ?: openRaw
                        } else {
                            openRaw
                        }
                    when (pending.side) {
                        TradeSide.BUY ->
                            if (!openPositions.containsKey(code) && cash > ZERO && openRaw > ZERO) {
                                val skipReason = entryPolicySkip(resolvedEntryPolicy, openEval, currentCandle.ts, slippageModel)
                                if (skipReason != null) {
                                    entrySkip = entrySkip.accumulate(skipReason)
                                } else {
                                    executeBuy(
                                        code = code,
                                        buyBudget = buyBudget(request.sizingPolicy, request.initialCapital, cash),
                                        rawPrice = openRaw,
                                        evalPrice = openEval,
                                        candleTs = currentCandle.ts,
                                        signalReason = pending.signalReason,
                                        commissionModel = commissionModel,
                                        slippageModel = slippageModel,
                                    )?.let { result ->
                                        trades.add(result.trade)
                                        cash = cash.subtract(result.cost)
                                        openPositions[code] = result.position
                                        entryBarIndex = t
                                    }
                                }
                            }
                        TradeSide.SELL ->
                            if (openPositions.containsKey(code)) {
                                val pendingPos = openPositions.getValue(code)
                                val result =
                                    executeSell(
                                        code = code,
                                        pos = pendingPos,
                                        rawPrice = openRaw,
                                        evalPrice = openEval,
                                        candleTs = currentCandle.ts,
                                        signalReason = pending.signalReason,
                                        taxModel = taxModel,
                                        commissionModel = commissionModel,
                                        slippageModel = slippageModel,
                                    )
                                trades.add(result.trade)
                                cash = cash.add(result.proceeds)
                                positions.add(pendingPos.copy(closedAt = currentCandle.ts, realizedPnl = result.realizedPnl))
                                openPositions.remove(code)
                                entryBarIndex = null
                            }
                    }
                }

                // BUY 신호 평가
                val buyResult =
                    try {
                        evaluatorBridge.evaluateBacktest(request.strategyId, buyAst, slice)
                    } catch (e: StrategyException.StrategyTimeoutException) {
                        timeoutCount++
                        if (timeoutCount >= ABORT_TIMEOUT_QUOTA) {
                            val durationMs = System.currentTimeMillis() - startMs
                            log.warn("백테스트 abort: timeout 누적 {}회, strategyId={}", timeoutCount, request.strategyId)
                            return BacktestResult.Aborted(
                                request = request,
                                partialTrades = trades.toList(),
                                reason = "TIMEOUT_QUOTA_EXCEEDED",
                                timeoutCount = timeoutCount,
                                executedAt = executedAt,
                                durationMs = durationMs,
                            )
                        }
                        null // timeout 1회 — HOLD 간주하고 계속
                    }

                // SELL 신호 평가
                val sellResult =
                    if (sellAst != null && openPositions.containsKey(code)) {
                        try {
                            evaluatorBridge.evaluateBacktest(request.strategyId, sellAst, slice)
                        } catch (e: StrategyException.StrategyTimeoutException) {
                            timeoutCount++
                            if (timeoutCount >= ABORT_TIMEOUT_QUOTA) {
                                val durationMs = System.currentTimeMillis() - startMs
                                return BacktestResult.Aborted(
                                    request = request,
                                    partialTrades = trades.toList(),
                                    reason = "TIMEOUT_QUOTA_EXCEEDED",
                                    timeoutCount = timeoutCount,
                                    executedAt = executedAt,
                                    durationMs = durationMs,
                                )
                            }
                            null
                        }
                    } else {
                        null
                    }

                val candleTs = currentCandle.ts

                // LEGACY SL/TP 평가 (감사 §2-② — 비-scale-in 전략도 라이브 evaluatePlainPositionExit 와 동일 라인 평가).
                //   우선순위·가격 규약은 INTRADAY_REALISTIC(IntradayExitSimulation 1·2단계)과 동일 — SL(봉 low) 우선, TP(봉 high) 후행.
                val plainExit =
                    if (!scaleInExitActive && openPositions.containsKey(code)) {
                        evaluateLegacyPlainExit(
                            request = request,
                            definition = definition,
                            pos = openPositions.getValue(code),
                            bar = currentCandle,
                        )
                    } else {
                        null
                    }

                if (scaleInExitActive) {
                    // ── scale-in/exit 경로 (spec §6-3) — ScaleInExitCalculator 단일 함수로 진입/청산 평가 ──
                    val step =
                        evaluateScaleInExitSlice(
                            request = request,
                            definition = definition,
                            code = code,
                            buyFired = buyResult?.signal == Signal.BUY,
                            buyReason = buyResult?.reason ?: emptyMap(),
                            evalPrice = evalPrice,
                            rawPrice = rawPrice,
                            candleTs = candleTs,
                            cash = cash,
                            openPositions = openPositions,
                            lotStates = lotStates,
                            resolvedEntryPolicy = resolvedEntryPolicy,
                            taxModel = taxModel,
                            commissionModel = commissionModel,
                            slippageModel = slippageModel,
                        )
                    step?.entrySkip?.let { entrySkip = entrySkip.accumulate(it) }
                    step?.let {
                        it.trade?.let { trade -> trades.add(trade) }
                        cash = it.cash
                        it.closedPosition?.let { closed -> positions.add(closed) }
                    }
                } else if (plainExit != null) {
                    // ── SL/TP 라인 체결 (감사 §2-② — 라인 가격 그대로 · 슬리피지 미적용은 INTRADAY_REALISTIC 규약 정합) ──
                    val pos = openPositions.getValue(code)
                    val result =
                        executeIntradayExit(
                            code = code,
                            pos = pos,
                            rawPrice = rawPrice,
                            exitPrice = plainExit.triggerPrice,
                            candleTs = candleTs,
                            reason = plainExit.reason,
                            signalTrigger = if (plainExit.reason == IntradayExitReason.SL) "STOP_LOSS" else "TAKE_PROFIT",
                            taxModel = taxModel,
                            commissionModel = commissionModel,
                        )
                    trades.add(result.trade)
                    cash = cash.add(result.proceeds)
                    positions.add(pos.copy(closedAt = candleTs, realizedPnl = result.realizedPnl))
                    openPositions.remove(code)
                    entryBarIndex = null
                } else if (sellResult?.signal == Signal.SELL && openPositions.containsKey(code)) {
                    // minHold 봉 미충족 DSL 매도 skip (감사 §2-② — 라이브 RealtimeSignalGenerator minHold 게이트 미러.
                    //   SL/TP 는 위 plainExit 에서 항상 먼저 평가됨 — 안전망 우선 규약 동일).
                    val minHoldBars = definition.minHoldBars ?: 0
                    val heldBars = entryBarIndex?.let { t - it } ?: Int.MAX_VALUE
                    if (minHoldBars > 0 && heldBars < minHoldBars) {
                        // skip — 라이브 SIGNAL_SKIPPED_MIN_HOLD 동등(HOLD 취급).
                    } else if (legacyProperties.fillAtNextOpen) {
                        pendingNextOpenFill = PendingLegacyFill(TradeSide.SELL, sellResult.reason)
                    } else {
                        // SELL 처리 (sellCondition 있을 때)
                        val pos = openPositions.getValue(code)
                        val result =
                            executeSell(
                                code = code,
                                pos = pos,
                                rawPrice = rawPrice,
                                evalPrice = evalPrice,
                                candleTs = candleTs,
                                signalReason = sellResult.reason,
                                taxModel = taxModel,
                                commissionModel = commissionModel,
                                slippageModel = slippageModel,
                            )
                        trades.add(result.trade)
                        cash = cash.add(result.proceeds)
                        val closedPos = pos.copy(closedAt = candleTs, realizedPnl = result.realizedPnl)
                        positions.add(closedPos)
                        openPositions.remove(code)
                        entryBarIndex = null
                    }
                } else if (buyResult?.signal == Signal.BUY && !openPositions.containsKey(code) && cash > ZERO) {
                    // 진입 정책 게이트(ADR 0114 R3) — 스킵이면 이 봉의 진입 기회 소멸로 기록, 매수 0.
                    val skipReason = entryPolicySkip(resolvedEntryPolicy, evalPrice, candleTs, slippageModel)
                    if (skipReason != null) {
                        entrySkip = entrySkip.accumulate(skipReason)
                    } else if (legacyProperties.fillAtNextOpen) {
                        // 감사 §6 P2-1 (opt-in) — 신호봉 close 체결 대신 다음 봉 open 체결로 이월(낙관 편향 완화).
                        pendingNextOpenFill = PendingLegacyFill(TradeSide.BUY, buyResult.reason)
                    } else {
                        // BUY 처리 — 사이징 정책에 따라 매수 예산 산정(AllCapital=전액 / FixedFractional=고정 슬롯).
                        val result =
                            executeBuy(
                                code = code,
                                buyBudget = buyBudget(request.sizingPolicy, request.initialCapital, cash),
                                rawPrice = rawPrice,
                                evalPrice = evalPrice,
                                candleTs = candleTs,
                                signalReason = buyResult.reason,
                                commissionModel = commissionModel,
                                slippageModel = slippageModel,
                            )
                        if (result != null) {
                            trades.add(result.trade)
                            cash = cash.subtract(result.cost)
                            openPositions[code] = result.position
                            entryBarIndex = t
                        }
                    }
                }

                // 매 캔들 mark-to-market equity 기록 (ADR 0056 §2-7 amend — MDD 결함 fix).
                // equity(t) = cash + 보유 포지션 수량 × 현재 캔들 평가가(미실현 포함). 체결 발생 여부와 무관하게
                // 모든 t에서 1점. 보유 구간에서도 cash≈0이 아니라 실제 평가자본을 반영 → MDD·Sharpe 정상화.
                equityCurve.add(markToMarketEquity(cash, openPositions, evalPrice))
            }

            // 시뮬 종료 — 미종료 포지션을 마지막 캔들 가격으로 강제 청산
            if (openPositions.containsKey(code)) {
                val pos = openPositions.getValue(code)
                val lastCandle = allCandles.last()
                val lastRaw = lastCandle.closeRaw
                val lastEval = if (request.priceAdjustmentMode == PriceAdjustmentMode.ADJUSTED) lastCandle.closeAdj ?: lastRaw else lastRaw
                val result =
                    executeSell(
                        code = code,
                        pos = pos,
                        rawPrice = lastRaw,
                        evalPrice = lastEval,
                        candleTs = lastCandle.ts,
                        signalReason = mapOf("trigger" to "FORCE_CLOSE_END_OF_BACKTEST"),
                        taxModel = taxModel,
                        commissionModel = commissionModel,
                        slippageModel = slippageModel,
                    )
                trades.add(result.trade)
                cash = cash.add(result.proceeds)
                val closedPos = pos.copy(closedAt = lastCandle.ts, realizedPnl = result.realizedPnl)
                positions.add(closedPos)
                openPositions.remove(code)
                // 강제 청산 후 실현 cash로 마지막 equity 점을 갱신(보유 0 → MTM = cash). 직전 t-루프가 기록한
                // 마지막 MTM(보유 평가가 기준)을 청산 비용 반영 후 실현값으로 대체해 종가 정합.
                if (equityCurve.isNotEmpty()) {
                    equityCurve[equityCurve.size - 1] = cash
                } else {
                    equityCurve.add(cash)
                }
            }
        }

        // 7. 성과 지표 계산
        val periodDays =
            ChronoUnit.DAYS.between(
                fromInstant,
                toInstant,
            )
        val businessDays = calculateBusinessDays(request)
        val performance: BacktestPerformance =
            performanceCalculator.calculate(
                trades = trades,
                initialCapital = request.initialCapital,
                finalCapital = cash,
                equityCurve = equityCurve,
                periodDays = periodDays,
                businessDays = businessDays,
            )

        val durationMs = System.currentTimeMillis() - startMs
        log.info(
            "백테스트 완료: strategyId={}, trades={}, finalCapital={}, durationMs={}",
            request.strategyId,
            trades.size,
            cash,
            durationMs,
        )

        if (resolvedEntryPolicy != null && entrySkip.total > 0) {
            log.info(
                "백테스트 진입 정책 스킵: strategyId={} total={} priceCapExceeded={} (백테스트 근사 — 캡 게이트만 평가)",
                request.strategyId,
                entrySkip.total,
                entrySkip.priceCapExceeded,
            )
        }

        return BacktestResult.Success(
            request = request,
            trades = trades.toList(),
            positions = positions.toList(),
            finalCapital = cash,
            performance = performance,
            executedAt = executedAt,
            durationMs = durationMs,
            entrySkip = entrySkip,
        )
    }

    // =====================================================================
    // INTRADAY_REALISTIC 백테스트 (ADR 0132 §3 기둥1·2·3 — 라이브 parity 청산 + backfill 선수집 + 정합성 가드)
    // =====================================================================

    /**
     * 초단타 정합 백테스트 (ADR 0132 §4-A R2b).
     *
     * LEGACY 와 다른 점은 **청산 두뇌**뿐이다:
     *  - ① 분봉 선수집 + 정합성 보고([BacktestCandlePreparePort.prepareAndReport]) — 기둥2·3. 포트 부재(R2a 미머지) 시 backfill 0.
     *  - ② 진입은 기존 DSL entry 유지(LEGACY 와 동일 BUY 신호). 개장 blackout(09:00~09:15)·EOD 후 신규 진입은 억제.
     *  - ③ 청산은 [IntradayExitSimulation.evaluateBar](라이브 [kr.spdaewoon.magicjar.strategy.TradePlanEvaluator] 재사용 +
     *       intrabar SL 우선 + EOD/min_hold) — 청산 재구현 0(parity LOCK).
     *  - ④ 정합성 등급 분기: OK 진행 · LOW_CONFIDENCE 진행+배지 · INSUFFICIENT_DATA 종목 skip · 전부 skip 이면 Aborted.
     *
     * 세금·수수료·슬리피지·MTM equity·진입 정책 게이트·사이징은 LEGACY 헬퍼를 그대로 재사용한다(코드 중복 0 — DRY LOCK).
     */
    private fun runIntradayRealistic(request: BacktestRequest): BacktestResult {
        val startMs = System.currentTimeMillis()
        val executedAt = Clock.System.now()

        log.info(
            "초단타 정합 백테스트 시작(INTRADAY_REALISTIC): strategyId={}, universe={}, interval={}, from={}, to={}, backfillIfMissing={}",
            request.strategyId,
            request.universe,
            request.interval,
            request.from,
            request.to,
            request.backfillIfMissing,
        )

        // 1. 전략 조회 (LEGACY 와 동일).
        val strategy =
            strategyRepository.findById(request.strategyId)
                ?: throw BacktestException.StrategyNotFoundException(request.strategyId)
        val definition = applyExitParamOverride(strategy.definition, request.exitParamOverride)
        val dslDef: DslStrategyDefinition = definition.dsl
        val buyAst: DslNode =
            dslDef.buyCondition
                ?: throw BacktestException.InvalidStrategyDslException(request.strategyId, "buyCondition (진입 조건)이 없습니다.")

        // 2. 분봉 선수집 + 정합성 보고 (기둥2·3). 게이트(ENV) ∧ 요청 플래그 ∧ 포트 존재 셋 모두 충족 시 backfill 발화.
        val backfillEnabled = intradayProperties.resolveBackfillEnabled(request.backfillIfMissing)
        val integrity: Map<String, CandleIntegrityReport> = prepareCandles(request, backfillEnabled)

        // 3. 정합성 등급 분기 — INSUFFICIENT_DATA 종목 skip, 전부 skip 이면 Aborted (mock 보간 0 — 등급으로만).
        val tradableUniverse =
            request.universe.filter { code ->
                val report = integrity[code]
                val skip = report?.confidence == CandleConfidence.INSUFFICIENT_DATA
                if (skip) {
                    log.warn(
                        "초단타 백테스트 종목 skip(INSUFFICIENT_DATA): code={} missingRatio={} (mock 보간 0 — 등급으로만 노출)",
                        code,
                        report?.missingRatio,
                    )
                }
                !skip
            }
        if (tradableUniverse.isEmpty()) {
            val durationMs = System.currentTimeMillis() - startMs
            log.warn("초단타 백테스트 abort: universe 전부 INSUFFICIENT_DATA — strategyId={}", request.strategyId)
            return BacktestResult.Aborted(
                request = request,
                partialTrades = emptyList(),
                reason = "INSUFFICIENT_CANDLE_INTEGRITY",
                timeoutCount = 0,
                executedAt = executedAt,
                durationMs = durationMs,
            )
        }

        // 4. 모델 lookup (LEGACY 와 동일).
        val taxModel: BacktestTaxModel = modelRegistry.taxModel(request.taxModel)
        val commissionModel: BacktestCommissionModel = modelRegistry.commissionModel(request.commissionModel)
        val slippageModel: BacktestSlippageModel = modelRegistry.slippageModel(request.slippageModel)
        val resolvedEntryPolicy: ResolvedEntryPolicy? =
            definition.entryPolicy?.resolved(ResolvedEntryPolicy.DEFAULTS)

        // 5. 캔들 조회 범위 (LEGACY 와 동일 — KST 경계 instant).
        val interval = CandleInterval.fromCode(request.interval)
        val seoulTz = TimeZone.of("Asia/Seoul")
        val fromInstant: Instant =
            LocalDateTime(request.from.year, request.from.monthNumber, request.from.dayOfMonth, 0, 0)
                .toInstant(seoulTz)
                .toJavaInstant()
        val toInstant: Instant =
            LocalDateTime(request.to.year, request.to.monthNumber, request.to.dayOfMonth, 23, 59, 59)
                .toInstant(seoulTz)
                .toJavaInstant()

        // 6. 시뮬 상태.
        val trades = mutableListOf<BacktestTrade>()
        val positions = mutableListOf<BacktestPosition>()
        val equityCurve = mutableListOf<BigDecimal>(request.initialCapital)
        var cash = request.initialCapital
        var entrySkip = BacktestEntrySkip.NONE
        val openPositions = mutableMapOf<String, BacktestPosition>()
        // 청산 두뇌 입력 추적 — 진입 봉 index(elapsedBars 산출) + 봉 high 누적(트레일링 highWatermark).
        val entryBarIndex = mutableMapOf<String, Int>()
        val highWatermark = mutableMapOf<String, BigDecimal>()

        // 7. universe 순회 시뮬 (skip 제외).
        for (code in tradableUniverse) {
            val entities: List<CandleJpaEntity> =
                candleJpaRepository.findByCodeAndIntervalAndTsBetween(
                    code = code,
                    interval = interval.code,
                    fromTs = fromInstant,
                    toTs = toInstant,
                )
            if (entities.isEmpty()) {
                // 정합성 보고는 INSUFFICIENT 가 아니었으나 실제 조회 0 — DB-only 모드(포트 부재)에서 발생 가능. 종목 skip(부분 결과).
                log.warn("초단타 백테스트 캔들 0건 — 종목 skip: code={}, interval={}", code, interval.code)
                continue
            }
            val allCandles: List<Candle> = entities.map { toDomain(it, request.priceAdjustmentMode) }

            for (t in allCandles.indices) {
                val slice = LookaheadGuard.safeSlice(allCandles, t)
                val currentCandle = allCandles[t]
                val rawPrice = currentCandle.closeRaw
                val evalPrice =
                    if (request.priceAdjustmentMode == PriceAdjustmentMode.ADJUSTED) {
                        currentCandle.closeAdj ?: rawPrice
                    } else {
                        rawPrice
                    }
                val candleTs = currentCandle.ts
                val barTimeKst: LocalTime =
                    currentCandle.ts
                        .toJavaInstant()
                        .atZone(SEOUL_ZONE)
                        .toLocalTime()

                if (openPositions.containsKey(code)) {
                    // ── 보유 중 — 청산 평가(라이브 두뇌 parity) ──
                    val pos = openPositions.getValue(code)
                    highWatermark[code] = maxHigh(highWatermark[code], currentCandle, request.priceAdjustmentMode)
                    val plan =
                        TradePlanFactory.build(
                            strategyId = request.strategyId,
                            definition = definition,
                            entryPrice = pos.avgEntryPrice,
                            bar = interval.code,
                            openedAt = pos.openedAt.toString(),
                        )
                    val elapsedBars = (t - (entryBarIndex[code] ?: t)).coerceAtLeast(0)
                    val decision =
                        IntradayExitSimulation.evaluateBar(
                            plan = plan,
                            bar = currentCandle,
                            avgPrice = pos.avgEntryPrice,
                            elapsedBars = elapsedBars,
                            highWatermark = highWatermark[code],
                            minHoldBars = definition.minHoldBars,
                            allowOvernight = definition.allowOvernight,
                            barTimeKst = barTimeKst,
                            adjusted = request.priceAdjustmentMode == PriceAdjustmentMode.ADJUSTED,
                        )
                    if (decision != null) {
                        // 청산 — IntradayExitSimulation 가 산정한 triggerPrice 를 체결가로 사용(SL/TP 라인 · EOD/MAX_HOLD/TRAIL close).
                        val result =
                            executeIntradayExit(
                                code = code,
                                pos = pos,
                                rawPrice = rawPrice,
                                exitPrice = decision.triggerPrice,
                                candleTs = candleTs,
                                reason = decision.reason,
                                taxModel = taxModel,
                                commissionModel = commissionModel,
                            )
                        trades.add(result.trade)
                        cash = cash.add(result.proceeds)
                        positions.add(pos.copy(closedAt = candleTs, realizedPnl = result.realizedPnl))
                        openPositions.remove(code)
                        entryBarIndex.remove(code)
                        highWatermark.remove(code)
                    }
                } else {
                    // ── 미보유 — DSL 진입 평가(LEGACY 와 동일 BUY 신호) + 개장 blackout/late-entry 억제 ──
                    val buyResult = evaluateBuySignal(request.strategyId, buyAst, slice)
                    val blackout = IntradayTradingClock.isOpenBlackout(barTimeKst)
                    // allow_overnight=false 전략은 late-entry cutoff(14:30↑) 이후 신규 진입 금지(라이브 parity).
                    val lateBlocked = !definition.allowOvernight && IntradayTradingClock.isLateEntryBlocked(barTimeKst)
                    if (buyResult?.signal == Signal.BUY && !blackout && !lateBlocked && cash > ZERO) {
                        val skipReason = entryPolicySkip(resolvedEntryPolicy, evalPrice, candleTs, slippageModel)
                        if (skipReason != null) {
                            entrySkip = entrySkip.accumulate(skipReason)
                        } else {
                            val result =
                                executeBuy(
                                    code = code,
                                    buyBudget = buyBudget(request.sizingPolicy, request.initialCapital, cash),
                                    rawPrice = rawPrice,
                                    evalPrice = evalPrice,
                                    candleTs = candleTs,
                                    signalReason = buyResult.reason,
                                    commissionModel = commissionModel,
                                    slippageModel = slippageModel,
                                )
                            if (result != null) {
                                trades.add(result.trade)
                                cash = cash.subtract(result.cost)
                                openPositions[code] = result.position
                                entryBarIndex[code] = t
                                highWatermark[code] = maxHigh(null, currentCandle, request.priceAdjustmentMode)
                            }
                        }
                    }
                }

                equityCurve.add(markToMarketEquity(cash, openPositions, evalPrice))
            }

            // 시뮬 종료 — 미종료 포지션을 마지막 봉 close 로 강제 청산(LEGACY 와 동일 정합).
            if (openPositions.containsKey(code)) {
                val pos = openPositions.getValue(code)
                val lastCandle = allCandles.last()
                val lastRaw = lastCandle.closeRaw
                val lastEval =
                    if (request.priceAdjustmentMode == PriceAdjustmentMode.ADJUSTED) lastCandle.closeAdj ?: lastRaw else lastRaw
                val result =
                    executeIntradayExit(
                        code = code,
                        pos = pos,
                        rawPrice = lastRaw,
                        exitPrice = lastEval,
                        candleTs = lastCandle.ts,
                        reason = IntradayExitReason.EOD,
                        signalTrigger = "FORCE_CLOSE_END_OF_BACKTEST",
                        taxModel = taxModel,
                        commissionModel = commissionModel,
                    )
                trades.add(result.trade)
                cash = cash.add(result.proceeds)
                positions.add(pos.copy(closedAt = lastCandle.ts, realizedPnl = result.realizedPnl))
                openPositions.remove(code)
                entryBarIndex.remove(code)
                highWatermark.remove(code)
                if (equityCurve.isNotEmpty()) equityCurve[equityCurve.size - 1] = cash else equityCurve.add(cash)
            }
        }

        // 8. 성과 지표.
        val periodDays = ChronoUnit.DAYS.between(fromInstant, toInstant)
        val businessDays = calculateBusinessDays(request)
        val performance: BacktestPerformance =
            performanceCalculator.calculate(
                trades = trades,
                initialCapital = request.initialCapital,
                finalCapital = cash,
                equityCurve = equityCurve,
                periodDays = periodDays,
                businessDays = businessDays,
            )

        // universe 최저 신뢰 등급(OK < LOW_CONFIDENCE < INSUFFICIENT_DATA) — UI 배지 단일 진실원.
        val confidence = worstConfidence(integrity.values.map { it.confidence })
        val durationMs = System.currentTimeMillis() - startMs
        log.info(
            "초단타 정합 백테스트 완료: strategyId={}, trades={}, finalCapital={}, confidence={}, skipped={}/{}, durationMs={}",
            request.strategyId,
            trades.size,
            cash,
            confidence,
            request.universe.size - tradableUniverse.size,
            request.universe.size,
            durationMs,
        )

        return BacktestResult.Success(
            request = request,
            trades = trades.toList(),
            positions = positions.toList(),
            finalCapital = cash,
            performance = performance,
            executedAt = executedAt,
            durationMs = durationMs,
            entrySkip = entrySkip,
            candleIntegrity = integrity,
            confidence = confidence,
        )
    }

    /**
     * 분봉 선수집 + 정합성 보고 — 포트([BacktestCandlePreparePort]) 위임(ADR 0132 §1-E · 기둥2·3).
     *
     * 포트가 주입돼 있으면(R2a 머지) universe 전 종목에 대해 정합성 보고를 받는다(backfill 은 [backfillEnabled] AND 결과).
     * 포트 부재(R2a 미머지) 면 DB 기존 봉만으로 시뮬하며 정합성 메트릭은 비운다(빈 Map → confidence=OK · skip 0).
     */
    private fun prepareCandles(
        request: BacktestRequest,
        backfillEnabled: Boolean,
    ): Map<String, CandleIntegrityReport> {
        val port =
            candlePreparePort ?: run {
                log.info("BacktestCandlePreparePort 미주입(R2a 미머지) — 정합성 보고 생략, DB 기존 봉만으로 시뮬: strategyId={}", request.strategyId)
                return emptyMap()
            }
        return try {
            port.prepareAndReport(
                universe = request.universe,
                interval = request.interval,
                from = request.from,
                to = request.to,
                backfillEnabled = backfillEnabled,
            )
        } catch (e: RuntimeException) {
            // 정합성/backfill 실패는 백테스트 자체를 막지 않는다 — 정합성 미상(빈 Map)으로 DB 기존 봉 시뮬 진행.
            log.warn("초단타 백테스트 분봉 선수집 실패 — 정합성 미상으로 진행: strategyId={}, cause={}", request.strategyId, e.message)
            emptyMap()
        }
    }

    /** universe 정합성 등급 중 최저(OK < LOW_CONFIDENCE < INSUFFICIENT_DATA). 비어 있으면 OK. */
    private fun worstConfidence(confidences: Collection<CandleConfidence>): CandleConfidence =
        confidences.maxByOrNull { it.ordinal } ?: CandleConfidence.OK

    /** 봉 high(가격 모드 반영)와 직전 watermark 중 최대 — 트레일링 highWatermark 누적. */
    private fun maxHigh(
        prev: BigDecimal?,
        bar: Candle,
        mode: PriceAdjustmentMode,
    ): BigDecimal {
        val high = if (mode == PriceAdjustmentMode.ADJUSTED) bar.highAdj ?: bar.highRaw else bar.highRaw
        return if (prev == null) high else prev.max(high)
    }

    /** BUY 신호 1회 평가 — timeout 1회는 HOLD 간주(null). 초단타 진입은 LEGACY 와 동일 DSL entry. */
    private fun evaluateBuySignal(
        strategyId: Long,
        buyAst: DslNode,
        slice: List<Candle>,
    ): kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult? =
        try {
            evaluatorBridge.evaluateBacktest(strategyId, buyAst, slice)
        } catch (e: StrategyException.StrategyTimeoutException) {
            null
        }

    /**
     * 초단타 청산 체결 — IntradayExitSimulation 가 산정한 [exitPrice] 로 보유분 전량 SELL(세금·수수료 반영).
     *
     * SL/TP 는 라인 가격, MAX_HOLD/EOD/TRAIL/EXIT_COND 는 봉 close 가 [exitPrice] 로 들어온다(parity 체결가).
     * 슬리피지는 적용하지 않는다 — IntradayExitSimulation 의 청산가는 이미 KRX tick 라인(라이브 PlanExecutor 산정가)이며,
     * 라인 위에 슬리피지를 또 얹으면 라이브 parity 가 깨진다(보수적 — 청산 라인 그대로 체결).
     */
    private fun executeIntradayExit(
        code: String,
        pos: BacktestPosition,
        rawPrice: BigDecimal,
        exitPrice: BigDecimal,
        candleTs: kotlinx.datetime.Instant,
        reason: IntradayExitReason,
        signalTrigger: String? = null,
        taxModel: BacktestTaxModel,
        commissionModel: BacktestCommissionModel,
    ): SellResult {
        val quantity = pos.quantity
        val notional = exitPrice.multiply(BigDecimal(quantity))
        val commission = commissionModel.calculate(TradeSide.SELL, notional)
        val tax = taxModel.calculate(TradeSide.SELL, exitPrice, quantity, DEFAULT_MARKET)
        val proceeds = notional.subtract(commission).subtract(tax)
        val realizedPnl = proceeds.subtract(pos.avgEntryPrice.multiply(BigDecimal(quantity)))
        val trade =
            BacktestTrade(
                code = code,
                side = TradeSide.SELL,
                quantity = quantity,
                executedPrice = exitPrice,
                rawPrice = rawPrice,
                executedAt = candleTs,
                commission = commission,
                tax = tax,
                slippage = ZERO,
                signalReason =
                    mapOf(
                        "trigger" to (signalTrigger ?: reason.name),
                        "exitReason" to reason.name,
                        "realizedPnl" to realizedPnl,
                    ),
            )
        return SellResult(trade = trade, proceeds = proceeds, realizedPnl = realizedPnl)
    }

    /**
     * LEGACY 비-scale-in 전략의 SL/TP 봉 평가 (감사 §2-② — LEGACY 백테스트가 fixedStopLoss/takeProfitPct 를
     * 평가하지 않아 라이브([RealtimeSignalGenerator] evaluatePlainPositionExit + tick 손절 watcher)와 청산 분포가
     * 딴판이던 결함 교정).
     *
     * 우선순위·가격 규약은 INTRADAY_REALISTIC([IntradayExitSimulation.evaluateBar] 1·2단계)과 동일하다:
     *  1. intrabar SL 우선 — 봉 low ≤ SL 라인이면 SL 체결(가격=라인 · 같은 봉 TP 도달 무관 — 보수).
     *  2. intrabar TP — 봉 high ≥ TP 라인 ∧ SL 미도달이면 TP 체결(가격=라인).
     * 라인 산식은 라이브·INTRADAY_REALISTIC 과 단일 출처([TradePlanFactory.build] +
     * [TradePlanEvaluator.stopPriceOf]/[TradePlanEvaluator.targetPriceOf] — 평단 연동 + KRX tick 라운딩).
     * 민감도 sweep 의 FIXED_STOP_LOSS/TAKE_PROFIT override 는 definition copy 로 이미 반영된다(silent no-op 해소).
     * 트레일링·MAX_HOLD 는 본 보강 범위 밖(INTRADAY_REALISTIC 전용 — 감사 §2-② 중기 트랙 · TRAILING_STOP sweep 은
     * LEGACY 에서 여전히 no-op).
     */
    private fun evaluateLegacyPlainExit(
        request: BacktestRequest,
        definition: StrategyDefinition,
        pos: BacktestPosition,
        bar: Candle,
    ): IntradayExitDecision? {
        if (definition.fixedStopLoss == null && definition.takeProfitPct == null) return null
        if (pos.avgEntryPrice <= ZERO) return null
        val adjusted = request.priceAdjustmentMode == PriceAdjustmentMode.ADJUSTED
        val high = if (adjusted) bar.highAdj ?: bar.highRaw else bar.highRaw
        val low = if (adjusted) bar.lowAdj ?: bar.lowRaw else bar.lowRaw
        if (high <= ZERO || low <= ZERO) return null
        val plan =
            TradePlanFactory.build(
                strategyId = request.strategyId,
                definition = definition,
                entryPrice = pos.avgEntryPrice,
                bar = request.interval,
                openedAt = pos.openedAt.toString(),
            )
        TradePlanEvaluator.stopPriceOf(plan, pos.avgEntryPrice)?.let { sl ->
            if (sl > ZERO && low <= sl) return IntradayExitDecision(IntradayExitReason.SL, sl)
        }
        TradePlanEvaluator.targetPriceOf(plan, pos.avgEntryPrice)?.let { tp ->
            if (high >= tp) return IntradayExitDecision(IntradayExitReason.TP, tp)
        }
        return null
    }

    /** 정규장(09:00~15:30 KST) 안 봉 시각인지 — MarketSession.Regular 창(ADR 0013) echo (감사 §6 P2-2). */
    private fun isRegularSessionKst(barTimeKst: LocalTime): Boolean =
        !barTimeKst.isBefore(REGULAR_SESSION_OPEN_KST) && barTimeKst.isBefore(REGULAR_SESSION_CLOSE_KST)

    /** 정규장 필터 적용 대상 interval — 60분 이하 분·시 봉만(일봉·주봉 등 장중 시각 의미 없는 봉 오적용 방지). */
    private fun isIntradayInterval(interval: CandleInterval): Boolean = MinHoldGuard.intervalSeconds(interval) in 1L..3600L

    // =====================================================================
    // 진입 정책 게이트 (ADR 0114 R3 — live EntryPricePolicy 단일 함수 공유)
    // =====================================================================

    /**
     * 백테스트 진입 정책 판정 — live·리플레이와 **동일한** [EntryPricePolicy.evaluate] 순수 함수 호출.
     *
     * ## 호가 부재 근사 (정직 한계 — ADR 0114 R3 LOCK)
     * 백테스트에는 호가(ask1/bid1) 시계열이 없다. 보수 근사로 ask1 ≈ **슬리피지 적용 BUY 체결가**(시장 진입 시 실제
     * 지불가에 가장 가까움), bid1 ≈ ask1(spread 0 — 스프레드 게이트 사실상 no-op), quoteAsOf=evalAt(stale 0)로 주입한다.
     * 따라서 백테스트는 진입 정책 중 **슬리피지 캡 게이트만** 의미 있게 재현하고, 스프레드/stale 스킵은 0이다(live 전용).
     *
     * @return 스킵 사유([EntryPriceDecision.Skip]) 또는 null(진입 허용 — Enter).
     */
    private fun entryPolicySkip(
        resolvedEntryPolicy: ResolvedEntryPolicy?,
        evalPrice: BigDecimal,
        candleTs: kotlinx.datetime.Instant,
        slippageModel: BacktestSlippageModel,
    ): EntryPriceDecision.Skip? {
        val policy = resolvedEntryPolicy ?: return null
        if (evalPrice <= ZERO) return null // 가격 결측은 executeBuy가 별도 가드(스킵 집계 대상 아님).
        // ask1 ≈ 슬리피지 적용 체결가(시장 진입 실제 지불가 근사). bid1 ≈ ask1(spread 0 — 게이트 no-op).
        val askApprox = slippageModel.apply(TradeSide.BUY, evalPrice)
        val decision =
            EntryPricePolicy.evaluate(
                EntryPriceInput(
                    priceAtSignal = evalPrice,
                    ask1 = askApprox,
                    bid1 = askApprox,
                    maxEntrySlippagePct = policy.maxEntrySlippagePct,
                    maxSpreadPct = policy.maxSpreadPct,
                    quoteAsOf = candleTs, // stale 게이트 no-op(호가 신선도 시계열 부재 — evalAt=quoteAsOf 근사).
                    evalAt = candleTs,
                ),
            )
        return decision as? EntryPriceDecision.Skip
    }

    /** 스킵 사유를 [BacktestEntrySkip]에 누적한다(사유별 카운트 — add-only 결과 노출). */
    private fun BacktestEntrySkip.accumulate(skip: EntryPriceDecision.Skip): BacktestEntrySkip =
        when (skip) {
            EntryPriceDecision.Skip.NoQuote -> copy(noQuote = noQuote + 1)
            EntryPriceDecision.Skip.QuoteStale -> copy(quoteStale = quoteStale + 1)
            EntryPriceDecision.Skip.SpreadTooWide -> copy(spreadTooWide = spreadTooWide + 1)
            EntryPriceDecision.Skip.PriceCapExceeded -> copy(priceCapExceeded = priceCapExceeded + 1)
            EntryPriceDecision.Skip.ThinLiquidity -> this // R2 예약 — 백테스트 미발화.
        }

    // =====================================================================
    // 매수 실행
    // =====================================================================

    private data class BuyResult(
        val trade: BacktestTrade,
        val cost: BigDecimal,
        val position: BacktestPosition,
    )

    /**
     * 매수 예산 산정 — 사이징 정책별 1회 진입 자본 노출 (ADR 0121 §2-A · §2-2 LOCK).
     *
     * - [SizingPolicy.AllCapital]: 보유 현금 전액(`cash`) — 기존 동작 무변경(회귀 0).
     * - [SizingPolicy.FixedFractional]: 고정 슬롯 = `min(initialCapital × positionPercent/100, maxPositionAmount ?: ∞)`.
     *   슬롯 base는 **initialCapital 고정**(현재 cash 아님) — 복리 누적으로 슬롯이 부풀어 노출이 100%로 수렴하는 것을 막아
     *   일정 비중 분산을 근사한다. 산정 슬롯이 보유 cash를 초과하면 가용 cash로 **클램프**(진입 skip 아님 — §2-2).
     *   `maxConcurrent`는 1차에서 매수 예산에 영향 없음(메타 echo만 — 동시보유 시뮬은 post-1차).
     * - [SizingPolicy.Kelly]: post-MVP 미지원 — AllCapital과 동일 fallback(보유 cash 전액).
     */
    private fun buyBudget(
        policy: SizingPolicy,
        initialCapital: BigDecimal,
        cash: BigDecimal,
    ): BigDecimal =
        when (policy) {
            is SizingPolicy.FixedFractional ->
                fractionalSlot(policy, initialCapital)
                    .min(cash) // 잔여 cash 부족 시 가용 cash로 클램프(진입 skip 아님).
            // AllCapital · Kelly(post-MVP fallback) — 보유 현금 전액.
            else -> cash
        }

    /**
     * 사이징 정책별 1슬롯 자본 노출 base — `min(initialCapital × positionPercent/100, maxPositionAmount ?: ∞)`.
     *
     * `buyBudget`(비-scale-in)과 `evaluateScaleInExitSlice`(scale-in 회차 budget)가 **동일 규칙**을 쓰도록 단일 함수로 추출
     * (ADR 0121 §2-A · §2-2 LOCK — scale-in 경로 정합 보강). 슬롯 base는 **initialCapital 고정**(현재 cash 아님)이라
     * 복리 누적으로 슬롯이 부풀어 노출이 100%로 수렴하는 것을 막는다(일정 비중 분산 근사). cash clamp는 호출부 책임.
     *
     * scale-in totalSlices=1(weight=100%)이면 회차 budget = 슬롯 × 100% = 본 슬롯과 **등가** → executeBuy 경로와 비트 동일 보장.
     */
    private fun fractionalSlot(
        policy: SizingPolicy.FixedFractional,
        initialCapital: BigDecimal,
    ): BigDecimal {
        val percentSlot =
            initialCapital
                .multiply(policy.positionPercent)
                .divide(BigDecimal(100), 0, RoundingMode.DOWN)
        return policy.maxPositionAmount?.let { percentSlot.min(it) } ?: percentSlot
    }

    private fun executeBuy(
        code: String,
        buyBudget: BigDecimal,
        rawPrice: BigDecimal,
        evalPrice: BigDecimal,
        candleTs: kotlinx.datetime.Instant,
        signalReason: Map<String, Any>,
        commissionModel: BacktestCommissionModel,
        slippageModel: BacktestSlippageModel,
    ): BuyResult? {
        if (rawPrice.compareTo(ZERO) == 0) return null

        val executedPrice = slippageModel.apply(TradeSide.BUY, evalPrice)
        if (executedPrice.compareTo(ZERO) == 0) return null

        // 매수 예산으로 최대 수량 매수 (AllCapital=보유 현금 전액 / FixedFractional=고정 슬롯).
        val quantity = buyBudget.divide(executedPrice, 0, RoundingMode.FLOOR).toLong()
        if (quantity <= 0) return null

        val notional = executedPrice.multiply(BigDecimal(quantity))
        val commission = commissionModel.calculate(TradeSide.BUY, notional)
        val slippageAmt = executedPrice.subtract(evalPrice).abs().multiply(BigDecimal(quantity))

        val totalCost = notional.add(commission)

        val trade =
            BacktestTrade(
                code = code,
                side = TradeSide.BUY,
                quantity = quantity,
                executedPrice = executedPrice,
                rawPrice = rawPrice,
                executedAt = candleTs,
                commission = commission,
                tax = ZERO,
                slippage = slippageAmt,
                signalReason = signalReason,
            )

        val position =
            BacktestPosition(
                code = code,
                quantity = quantity,
                avgEntryPrice = executedPrice,
                openedAt = candleTs,
            )

        return BuyResult(trade = trade, cost = totalCost, position = position)
    }

    // =====================================================================
    // 매도 실행
    // =====================================================================

    private data class SellResult(
        val trade: BacktestTrade,
        val proceeds: BigDecimal,
        val realizedPnl: BigDecimal,
    )

    private fun executeSell(
        code: String,
        pos: BacktestPosition,
        rawPrice: BigDecimal,
        evalPrice: BigDecimal,
        candleTs: kotlinx.datetime.Instant,
        signalReason: Map<String, Any>,
        taxModel: BacktestTaxModel,
        commissionModel: BacktestCommissionModel,
        slippageModel: BacktestSlippageModel,
    ): SellResult {
        val executedPrice = slippageModel.apply(TradeSide.SELL, evalPrice)
        val quantity = pos.quantity
        val notional = executedPrice.multiply(BigDecimal(quantity))
        val commission = commissionModel.calculate(TradeSide.SELL, notional)
        val tax = taxModel.calculate(TradeSide.SELL, executedPrice, quantity, DEFAULT_MARKET)
        val slippageAmt = evalPrice.subtract(executedPrice).abs().multiply(BigDecimal(quantity))

        val proceeds = notional.subtract(commission).subtract(tax)

        val buyNotional = pos.avgEntryPrice.multiply(BigDecimal(quantity))
        val realizedPnl = proceeds.subtract(buyNotional)

        val reason = signalReason + mapOf("realizedPnl" to realizedPnl)

        val trade =
            BacktestTrade(
                code = code,
                side = TradeSide.SELL,
                quantity = quantity,
                executedPrice = executedPrice,
                rawPrice = rawPrice,
                executedAt = candleTs,
                commission = commission,
                tax = tax,
                slippage = slippageAmt,
                signalReason = reason,
            )

        return SellResult(trade = trade, proceeds = proceeds, realizedPnl = realizedPnl)
    }

    // =====================================================================
    // scale-in/exit 슬라이스 평가 (spec §6-3 — ScaleInExitCalculator 단일 함수 경유)
    // =====================================================================

    /** scale-in/exit 가상 lot 상태 — calculator가 읽는 진행도/발화단계 + PRICE_DROP_PCT용 직전 회차 체결가. */
    private data class ScaleInLotState(
        val filledLots: Int = 0,
        val executedExitStages: Set<Int> = emptySet(),
        val lastSliceFillPrice: BigDecimal? = null,
    )

    /** 한 t 시점 scale-in/exit 평가 결과 — 호출부가 trades/cash/positions/equityCurve에 반영. */
    private data class ScaleInExitStep(
        val trade: BacktestTrade?,
        val cash: BigDecimal,
        val closedPosition: BacktestPosition?,
        val entrySkip: EntryPriceDecision.Skip? = null,
    )

    /**
     * scale-in/exit 활성 전략의 백테스트 한 t 시점 평가 (분할매수 R3 · spec §6-3 LOCK).
     *
     * live·리플레이와 **동일한 [ScaleInExitSimulation.evaluate]→[ScaleInExitCalculator]** 를 호출해 다음 lot 액션을
     * 산출하고, EnterSlice→가상 분할 매수, StopLoss/PartialTP/TakeProfit→가상 청산으로 평단·진행도·발화단계를 갱신한다.
     * 룩어헤드 가드는 호출부의 slice-by-time 평가가 보장한다(여기선 t 시점 평가가만 사용).
     *
     * 회차 수량: `floor(초기자본 × sliceWeight%/100 / executedPrice)`(spec §3-2 — 비중은 종목 예산 분배).
     * 백테스트는 1 universe × 1 종목 가정이라 초기자본을 종목 예산으로 본다.
     */
    private fun evaluateScaleInExitSlice(
        request: BacktestRequest,
        definition: StrategyDefinition,
        code: String,
        buyFired: Boolean,
        buyReason: Map<String, Any>,
        evalPrice: BigDecimal,
        rawPrice: BigDecimal,
        candleTs: kotlinx.datetime.Instant,
        cash: BigDecimal,
        openPositions: MutableMap<String, BacktestPosition>,
        lotStates: MutableMap<String, ScaleInLotState>,
        resolvedEntryPolicy: ResolvedEntryPolicy?,
        taxModel: BacktestTaxModel,
        commissionModel: BacktestCommissionModel,
        slippageModel: BacktestSlippageModel,
    ): ScaleInExitStep? {
        val pos = openPositions[code]
        val lot = lotStates[code] ?: ScaleInLotState()
        val virtual =
            pos?.takeIf { it.quantity > 0 }?.let {
                VirtualStrategyPosition(
                    code = code,
                    quantity = it.quantity.toInt(),
                    avgPrice = it.avgEntryPrice,
                    filledLots = lot.filledLots,
                    executedExitStages = lot.executedExitStages,
                )
            }

        val decision =
            ScaleInExitSimulation.evaluate(
                position = virtual,
                currentPrice = evalPrice,
                scaleIn = definition.scaleIn,
                fixedStopLossPct = definition.fixedStopLoss,
                takeProfitPct = definition.takeProfitPct,
                partialExits = definition.partialExits,
                buySignalFired = buyFired,
                lastSliceFillPrice = lot.lastSliceFillPrice,
            )

        return when (decision) {
            is ScaleInExitDecision.EnterSlice -> {
                // 진입 정책 게이트(ADR 0114 R3) — 각 분할 회차 진입도 live와 동일 EntryPricePolicy 캡 판정.
                entryPolicySkip(resolvedEntryPolicy, evalPrice, candleTs, slippageModel)?.let {
                    return ScaleInExitStep(trade = null, cash = cash, closedPosition = null, entrySkip = it)
                }
                val weight = decision.weightPct
                val executedPrice = slippageModel.apply(TradeSide.BUY, evalPrice)
                if (executedPrice.compareTo(ZERO) == 0) return null
                // 회차 예산 = 슬롯 base × weight% (종목 예산 분배). 보유 현금으로 clamp.
                // 슬롯 base는 사이징 정책별로 결정(ADR 0121 §2-A — scale-in 경로 정합 보강, #871 누락분 정정):
                //  · AllCapital: base = 초기자본 전액 → 기존 동작 무변경(회귀 0).
                //  · FixedFractional: base = fractionalSlot(executeBuy와 동일 규칙). totalSlices=1·weight=100%이면 슬롯 그대로 → executeBuy와 등가.
                val sliceBase =
                    when (val policy = request.sizingPolicy) {
                        is SizingPolicy.FixedFractional -> fractionalSlot(policy, request.initialCapital)
                        else -> request.initialCapital
                    }
                val sliceBudget =
                    sliceBase
                        .multiply(weight)
                        .divide(BigDecimal(100), 0, RoundingMode.DOWN)
                        .min(cash)
                val quantity = sliceBudget.divide(executedPrice, 0, RoundingMode.FLOOR).toLong()
                if (quantity <= 0) return null

                val notional = executedPrice.multiply(BigDecimal(quantity))
                val commission = commissionModel.calculate(TradeSide.BUY, notional)
                val slippageAmt = executedPrice.subtract(evalPrice).abs().multiply(BigDecimal(quantity))
                val totalCost = notional.add(commission)
                if (totalCost > cash) return null

                val trade =
                    BacktestTrade(
                        code = code,
                        side = TradeSide.BUY,
                        quantity = quantity,
                        executedPrice = executedPrice,
                        rawPrice = rawPrice,
                        executedAt = candleTs,
                        commission = commission,
                        tax = ZERO,
                        slippage = slippageAmt,
                        signalReason =
                            buyReason +
                                mapOf(
                                    "trigger" to "SCALE_IN_SLICE#${decision.sliceIndex + 1}",
                                    "sliceIndex" to decision.sliceIndex,
                                    "sliceWeightPct" to weight.toPlainString(),
                                    "reentryTrigger" to decision.trigger.name,
                                ),
                    )

                // 평단 가중평균 갱신 (StrategyPosition 평단 규칙 정합).
                val nextPosition =
                    if (pos == null) {
                        BacktestPosition(code = code, quantity = quantity, avgEntryPrice = executedPrice, openedAt = candleTs)
                    } else {
                        val prevCost = pos.avgEntryPrice.multiply(BigDecimal(pos.quantity))
                        val nextQty = pos.quantity + quantity
                        val nextAvg = prevCost.add(notional).divide(BigDecimal(nextQty), 6, RoundingMode.HALF_UP)
                        pos.copy(quantity = nextQty, avgEntryPrice = nextAvg)
                    }
                openPositions[code] = nextPosition
                lotStates[code] = lot.copy(filledLots = lot.filledLots + 1, lastSliceFillPrice = executedPrice)
                ScaleInExitStep(trade = trade, cash = cash.subtract(totalCost), closedPosition = null)
            }

            is ScaleInExitDecision.StopLoss ->
                fullExit(
                    code,
                    openPositions,
                    lotStates,
                    rawPrice,
                    evalPrice,
                    candleTs,
                    "STOP_LOSS",
                    cash,
                    taxModel,
                    commissionModel,
                    slippageModel,
                )

            is ScaleInExitDecision.TakeProfit ->
                fullExit(
                    code,
                    openPositions,
                    lotStates,
                    rawPrice,
                    evalPrice,
                    candleTs,
                    "TAKE_PROFIT",
                    cash,
                    taxModel,
                    commissionModel,
                    slippageModel,
                )

            is ScaleInExitDecision.PartialTakeProfit -> {
                val pos0 = openPositions[code] ?: return null
                val sellQty = decision.quantity.toLong().coerceIn(1, pos0.quantity)
                val executedPrice = slippageModel.apply(TradeSide.SELL, evalPrice)
                val notional = executedPrice.multiply(BigDecimal(sellQty))
                val commission = commissionModel.calculate(TradeSide.SELL, notional)
                val tax = taxModel.calculate(TradeSide.SELL, executedPrice, sellQty, DEFAULT_MARKET)
                val slippageAmt = evalPrice.subtract(executedPrice).abs().multiply(BigDecimal(sellQty))
                val proceeds = notional.subtract(commission).subtract(tax)
                val realizedPnl = proceeds.subtract(pos0.avgEntryPrice.multiply(BigDecimal(sellQty)))

                val trade =
                    BacktestTrade(
                        code = code,
                        side = TradeSide.SELL,
                        quantity = sellQty,
                        executedPrice = executedPrice,
                        rawPrice = rawPrice,
                        executedAt = candleTs,
                        commission = commission,
                        tax = tax,
                        slippage = slippageAmt,
                        signalReason =
                            mapOf(
                                "trigger" to "PARTIAL_TAKE_PROFIT#${decision.stageIndex}",
                                "stageIndex" to decision.stageIndex,
                                "exitWeightPct" to decision.weightPct.toPlainString(),
                                "realizedPnl" to realizedPnl,
                            ),
                    )

                val remaining = pos0.quantity - sellQty
                if (remaining == 0L) {
                    openPositions.remove(code)
                    lotStates.remove(code)
                    ScaleInExitStep(
                        trade = trade,
                        cash = cash.add(proceeds),
                        closedPosition = pos0.copy(closedAt = candleTs, realizedPnl = realizedPnl),
                    )
                } else {
                    // 부분 청산 — 평단 보존, 단계 발화 마킹(중복 발화 차단 — 가격이 trigger 위에 머물러도 0건).
                    openPositions[code] = pos0.copy(quantity = remaining)
                    lotStates[code] = lot.copy(executedExitStages = lot.executedExitStages + decision.stageIndex)
                    ScaleInExitStep(trade = trade, cash = cash.add(proceeds), closedPosition = null)
                }
            }

            // ⑦ catastrophic floor 도달은 **알람만**(주문 연동 0 — ADR 0128). 백테스트엔 알람 채널이 없으므로 no-op(HOLD).
            is ScaleInExitDecision.FloorBreachAlert -> null

            ScaleInExitDecision.Hold -> null
        }
    }

    /** 평단 기준 전량 청산(STOP_LOSS·TAKE_PROFIT 공용) — 보유분 전량 SELL + lot 상태 reset. */
    private fun fullExit(
        code: String,
        openPositions: MutableMap<String, BacktestPosition>,
        lotStates: MutableMap<String, ScaleInLotState>,
        rawPrice: BigDecimal,
        evalPrice: BigDecimal,
        candleTs: kotlinx.datetime.Instant,
        trigger: String,
        cash: BigDecimal,
        taxModel: BacktestTaxModel,
        commissionModel: BacktestCommissionModel,
        slippageModel: BacktestSlippageModel,
    ): ScaleInExitStep? {
        val pos = openPositions[code] ?: return null
        val result =
            executeSell(
                code = code,
                pos = pos,
                rawPrice = rawPrice,
                evalPrice = evalPrice,
                candleTs = candleTs,
                signalReason = mapOf("trigger" to trigger),
                taxModel = taxModel,
                commissionModel = commissionModel,
                slippageModel = slippageModel,
            )
        openPositions.remove(code)
        lotStates.remove(code)
        return ScaleInExitStep(
            trade = result.trade,
            cash = cash.add(result.proceeds),
            closedPosition = pos.copy(closedAt = candleTs, realizedPnl = result.realizedPnl),
        )
    }

    // =====================================================================
    // 헬퍼
    // =====================================================================

    /**
     * mark-to-market equity 계산 (ADR 0056 §2-7 amend — MDD 결함 fix).
     *
     * equity = cash + Σ(보유 포지션 수량 × 평가가). 평가가는 현재 캔들의 [evalPrice](가격조정 모드 반영 종가)다.
     * 백테스트는 1 universe × 1 종목을 순차 진행하므로 [openPositions]에는 동일 종목 1건만 존재하지만,
     * 멀티 포지션 확장 대비 전체 합산으로 둔다. 미실현 손익을 포함하므로 보유 구간 drawdown 아티팩트가 사라진다.
     */
    private fun markToMarketEquity(
        cash: BigDecimal,
        openPositions: Map<String, BacktestPosition>,
        evalPrice: BigDecimal,
    ): BigDecimal {
        var equity = cash
        for (pos in openPositions.values) {
            equity = equity.add(evalPrice.multiply(BigDecimal(pos.quantity)))
        }
        return equity
    }

    private fun toDomain(
        entity: CandleJpaEntity,
        mode: PriceAdjustmentMode,
    ): Candle =
        Candle(
            id = entity.id,
            code = entity.code,
            interval = CandleInterval.fromCode(entity.interval),
            ts = entity.ts.toKotlinInstant(),
            openRaw = entity.openRaw ?: ZERO,
            highRaw = entity.highRaw ?: ZERO,
            lowRaw = entity.lowRaw ?: ZERO,
            closeRaw = entity.closeRaw ?: ZERO,
            openAdj = entity.openAdj,
            highAdj = entity.highAdj,
            lowAdj = entity.lowAdj,
            closeAdj = entity.closeAdj,
            volume = entity.volume ?: 0L,
            source = entity.source,
        )

    /**
     * 영업일 수 근사 계산 (주말 제외).
     * 정확한 KRX 공휴일 계산은 post-MVP — MarketCalendar 테이블 연동 예정.
     */
    private fun calculateBusinessDays(request: BacktestRequest): Int {
        var days = 0
        var current = request.from
        while (current <= request.to) {
            val dow = current.dayOfWeek
            if (dow != kotlinx.datetime.DayOfWeek.SATURDAY && dow != kotlinx.datetime.DayOfWeek.SUNDAY) {
                days++
            }
            current = current.plus(1, DateTimeUnit.DAY)
        }
        return days
    }

    /**
     * 민감도 sweep 청산 파라미터 override 적용 (spec §3-R3 — add-only).
     *
     * [override]가 null이면 전략 정의를 그대로 반환(현행 동작 무변경). 지정 시 대응 청산 knob 1개만 copy로 치환한다.
     * DB는 건드리지 않으며(메모리 override), buy/sell DSL은 불변 — 손절·익절·트레일링 시뮬만 영향받는다.
     */
    private fun applyExitParamOverride(
        definition: StrategyDefinition,
        override: ExitParamOverride?,
    ): StrategyDefinition {
        if (override == null) return definition
        return when (override.target) {
            ExitParam.FIXED_STOP_LOSS -> definition.copy(fixedStopLoss = override.value)
            ExitParam.TAKE_PROFIT -> definition.copy(takeProfitPct = override.value)
            ExitParam.TRAILING_STOP -> definition.copy(trailingStop = override.value)
        }
    }
}
