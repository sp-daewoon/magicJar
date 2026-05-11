package kr.spdaewoon.magicjar.api.service.backtest

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.domain.backtest.BacktestException
import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestPosition
import kr.spdaewoon.magicjar.domain.backtest.BacktestRequest
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.BacktestTrade
import kr.spdaewoon.magicjar.domain.backtest.LookaheadGuard
import kr.spdaewoon.magicjar.domain.backtest.PriceAdjustmentMode
import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestCommissionModel
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestEngine
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestSlippageModel
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestTaxModel
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
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
 * - SizingPolicy.AllCapital: 매수 시 보유 현금 전액으로 1종목 매수 (최대 수량)
 * - 1 universe × 1 종목 순차 진행 (병렬은 post-MVP)
 * - 포지션 중복 불가 — 보유 중 BUY 신호 무시
 * - KOSPI 가정 (세금 모델 market 파라미터)
 */
@Component
class JvmBacktestEngine(
    private val evaluatorBridge: BacktestEvaluatorBridge,
    private val strategyRepository: StrategyRepositoryPort,
    private val candleJpaRepository: CandleJpaRepository,
    private val modelRegistry: BacktestModelRegistry,
    private val performanceCalculator: BacktestPerformanceCalculator,
) : BacktestEngine {
    private val log = LoggerFactory.getLogger(JvmBacktestEngine::class.java)

    companion object {
        /** abort quota: 누적 timeout 50회 시 백테스트 중단 (ADR 0047 §2-5 + ADR 0056 §2-5) */
        private const val ABORT_TIMEOUT_QUOTA = 50

        /** 기본 시장 구분 — 1차는 KOSPI 가정 */
        private const val DEFAULT_MARKET = "KOSPI"

        private val ZERO = BigDecimal.ZERO
    }

    override fun run(request: BacktestRequest): BacktestResult {
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

        val definition = strategy.definition
        val dslDef: DslStrategyDefinition = definition.dsl

        val buyAst: DslNode =
            dslDef.buyCondition
                ?: throw BacktestException.InvalidStrategyDslException(request.strategyId, "buyCondition (진입 조건)이 없습니다.")
        val sellAst: DslNode? = dslDef.sellCondition

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

        // 포지션 상태 (code → BacktestPosition, 1차: 단일 포지션)
        val openPositions = mutableMapOf<String, BacktestPosition>()

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

                // SELL 처리 (sellCondition 있을 때)
                if (sellResult?.signal == Signal.SELL && openPositions.containsKey(code)) {
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
                    equityCurve.add(cash)
                    val closedPos = pos.copy(closedAt = candleTs, realizedPnl = result.realizedPnl)
                    positions.add(closedPos)
                    openPositions.remove(code)
                } else if (buyResult?.signal == Signal.BUY && !openPositions.containsKey(code) && cash > ZERO) {
                    // BUY 처리 — AllCapital 기준 전액 투입
                    val result =
                        executeBuy(
                            code = code,
                            cash = cash,
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
                        equityCurve.add(cash)
                        openPositions[code] = result.position
                    }
                }
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
                equityCurve.add(cash)
                val closedPos = pos.copy(closedAt = lastCandle.ts, realizedPnl = result.realizedPnl)
                positions.add(closedPos)
                openPositions.remove(code)
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

        return BacktestResult.Success(
            request = request,
            trades = trades.toList(),
            positions = positions.toList(),
            finalCapital = cash,
            performance = performance,
            executedAt = executedAt,
            durationMs = durationMs,
        )
    }

    // =====================================================================
    // 매수 실행
    // =====================================================================

    private data class BuyResult(
        val trade: BacktestTrade,
        val cost: BigDecimal,
        val position: BacktestPosition,
    )

    private fun executeBuy(
        code: String,
        cash: BigDecimal,
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

        // AllCapital: 보유 현금으로 최대 수량 매수
        val quantity = cash.divide(executedPrice, 0, RoundingMode.FLOOR).toLong()
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
    // 헬퍼
    // =====================================================================

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
}
