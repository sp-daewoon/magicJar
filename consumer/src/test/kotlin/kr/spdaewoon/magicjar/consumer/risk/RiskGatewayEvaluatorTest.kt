package kr.spdaewoon.magicjar.consumer.risk

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.position.Position
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.domain.risk.RiskContext
import kr.spdaewoon.magicjar.domain.risk.RiskGatewayResult
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * RiskGatewayEvaluator 6 룰 short-circuit 단위 테스트 (ADR 0067 §7-2 정합).
 *
 * 매트릭스 (테스트 7건):
 *  1. KILL_SWITCH_ON BLOCKED (1순위 검증)
 *  2. DAILY_LOSS_LIMIT percent BLOCKED
 *  3. MAX_CONCURRENT_POSITIONS BLOCKED (BUY)
 *  4. TRADE_FREQUENCY_MINUTE BLOCKED
 *  5. TRADE_FREQUENCY_DAY BLOCKED
 *  6. POSITION_LIMIT BLOCKED (unit price > budget)
 *  7. 6 룰 모두 통과 ALLOWED (happy path)
 *
 * + 보조 검증: SELL 시그널은 MAX_CONCURRENT·POSITION_LIMIT 무관 (BUY만 검사)
 *
 * AI 호출 0 — T-RISK 트랙 정책.
 */
class RiskGatewayEvaluatorTest {
    private val evaluator = RiskGatewayEvaluator()
    private val now = Instant.fromEpochSeconds(1_736_640_000L) // 2026-01-12 KST 정도

    @Test
    fun `1 KILL_SWITCH_ON BLOCKED — 최우선 short-circuit`() {
        val signal = sampleSignal(side = SignalSide.BUY, priceAtSignal = BigDecimal("1000"))
        val context = sampleContext(killSwitchOn = true)

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.KILL_SWITCH_ON)
        assertThat(result.signalId).isEqualTo(signal.id)
    }

    @Test
    fun `1b KILL_SWITCH_ON은 SELL 청산 신호도 차단한다`() {
        val signal = sampleSignal(side = SignalSide.SELL, priceAtSignal = BigDecimal("1000"))
        val context = sampleContext(killSwitchOn = true, currentPositions = listOf(samplePosition("005930")))

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.KILL_SWITCH_ON)
    }

    @Test
    fun `2 DAILY_LOSS_LIMIT percent BLOCKED — 일일 손실 한도 도달`() {
        val signal = sampleSignal(side = SignalSide.BUY)
        // capital 100만, dailyPnl -5만 = -5% (한도 -3%)
        val context =
            sampleContext(
                config = sampleConfig(dailyLossLimitPercent = BigDecimal("3.00")),
                currentCapital = BigDecimal("1000000"),
                dailyRealizedPnl = BigDecimal("-50000"),
            )

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.DAILY_LOSS_LIMIT)
    }

    @Test
    fun `3 MAX_CONCURRENT_POSITIONS BLOCKED — BUY 시 동시 보유 한도 도달`() {
        val signal = sampleSignal(side = SignalSide.BUY)
        // default config maxConcurrentPositions=5, 현재 5종목 보유
        val positions = (1..5).map { samplePosition("00${it}000") }
        val context = sampleContext(currentPositions = positions)

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.MAX_CONCURRENT_POSITIONS)
    }

    @Test
    fun `3b MAX_CONCURRENT_POSITIONS SELL 통과 — 청산은 size 한도 무관`() {
        val signal = sampleSignal(side = SignalSide.SELL)
        val positions = (1..5).map { samplePosition("00${it}000") }
        val context = sampleContext(currentPositions = positions + samplePosition("005930"))

        val result = evaluator.evaluate(signal, context)

        // MAX_CONCURRENT 검사 skip — POSITION_LIMIT도 SELL이라 skip → ALLOWED
        assertThat(result).isInstanceOf(RiskGatewayResult.Allowed::class.java)
    }

    @Test
    fun `SELL_WITHOUT_POSITION BLOCKED — 보유 없는 매도 신호는 주문으로 보내지 않는다`() {
        val signal = sampleSignal(side = SignalSide.SELL)
        val context = sampleContext(currentPositions = emptyList())

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.SELL_WITHOUT_POSITION)
    }

    @Test
    fun `SELL_QUANTITY_UNAVAILABLE BLOCKED — 미체결 매도 주문이 보유 수량을 모두 예약하면 추가 매도 차단`() {
        val signal = sampleSignal(side = SignalSide.SELL)
        val context =
            sampleContext(
                currentPositions = listOf(samplePosition("005930", quantity = 4)),
                openOrders = listOf(sampleOpenOrder(code = "005930", side = OrderSide.SELL, quantity = 4)),
            )

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.SELL_QUANTITY_UNAVAILABLE)
    }

    @Test
    fun `resolveSellQuantity — 미체결 매도 주문 수량을 뺀 가용 수량만 반환한다`() {
        val signal = sampleSignal(side = SignalSide.SELL)
        val context =
            sampleContext(
                currentPositions = listOf(samplePosition("005930", quantity = 4)),
                openOrders = listOf(sampleOpenOrder(code = "005930", side = OrderSide.SELL, quantity = 2)),
            )

        assertThat(evaluator.resolveSellQuantity(signal, context)).isEqualTo(2)
    }

    @Test
    fun `INVALID_SIGNAL_PRICE BLOCKED — 가격 0 신호는 수량 계산 전에 차단`() {
        val signal = sampleSignal(side = SignalSide.BUY, priceAtSignal = BigDecimal.ZERO)
        val context = sampleContext()

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.INVALID_SIGNAL_PRICE)
    }

    @Test
    fun `4 TRADE_FREQUENCY_MINUTE BLOCKED — strategy당 분당 한도 도달`() {
        val signal = sampleSignal(side = SignalSide.BUY)
        val context = sampleContext(recentTradesMinute = 3) // default limit 3

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.TRADE_FREQUENCY_MINUTE)
    }

    @Test
    fun `5 TRADE_FREQUENCY_DAY BLOCKED — strategy당 일별 한도 도달`() {
        val signal = sampleSignal(side = SignalSide.BUY)
        val context = sampleContext(recentTradesDay = 50) // default limit 50

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.TRADE_FREQUENCY_DAY)
    }

    @Test
    fun `6 POSITION_LIMIT BLOCKED — unit price exceeds min(absolute, percent of capital)`() {
        // 자본 1,000만, percent 10% = 100만 budget, abs 1,000,000 → min = 100만
        // unitCost = 200만 > 100만 → BLOCKED
        val signal = sampleSignal(side = SignalSide.BUY, priceAtSignal = BigDecimal("2000000"))
        val context =
            sampleContext(
                config =
                    sampleConfig(
                        maxPositionAmount = BigDecimal("1000000"),
                        maxPositionPercent = BigDecimal("10.00"),
                    ),
                currentCapital = BigDecimal("10000000"),
            )

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.POSITION_LIMIT)
    }

    @Test
    fun `6b POSITION_LIMIT BLOCKED — 같은 종목 기존 보유금액 포함`() {
        // budget 100만, 005930 3주 보유(현재가 30만 기준 90만 노출), 추가 1주 30만은 남은 예산 10만 초과
        val signal = sampleSignal(side = SignalSide.BUY, priceAtSignal = BigDecimal("300000"))
        val context =
            sampleContext(
                config =
                    sampleConfig(
                        maxPositionAmount = BigDecimal("1000000"),
                        maxPositionPercent = BigDecimal("10.00"),
                    ),
                currentCapital = BigDecimal("10000000"),
                currentPositions = listOf(samplePosition("005930", quantity = 3)),
            )

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.POSITION_LIMIT)
        assertThat(result.reason).contains("remaining position budget")
    }

    @Test
    fun `6c resolveBuyQuantity — 같은 종목 남은 예산만 수량 산정`() {
        val signal = sampleSignal(side = SignalSide.BUY, priceAtSignal = BigDecimal("300000"))
        val context =
            sampleContext(
                config =
                    sampleConfig(
                        maxPositionAmount = BigDecimal("1000000"),
                        maxPositionPercent = BigDecimal("10.00"),
                    ),
                currentCapital = BigDecimal("10000000"),
                currentPositions = listOf(samplePosition("005930", quantity = 1)),
            )

        val quantity = evaluator.resolveBuyQuantity(signal, context)

        assertThat(quantity).isEqualTo(2)
    }

    @Test
    fun `7 ALLOWED happy path — 6 룰 모두 통과`() {
        val signal = sampleSignal(side = SignalSide.BUY, priceAtSignal = BigDecimal("50000"))
        // 자본 1,000만, 100만 budget — unitCost 5만 < 100만 → 통과
        val context =
            sampleContext(
                config =
                    sampleConfig(
                        maxPositionAmount = BigDecimal("1000000"),
                        maxPositionPercent = BigDecimal("10.00"),
                    ),
                currentCapital = BigDecimal("10000000"),
            )

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Allowed::class.java)
        assertThat(result.signalId).isEqualTo(signal.id)
    }

    @Test
    fun `short-circuit 순서 — KILL_SWITCH가 다른 모든 위반보다 우선`() {
        // 동시에 DLL · MAX_CONCURRENT · TRADE_FREQ_MIN · POSITION_LIMIT 모두 위반 상태에서도 KILL_SWITCH 반환
        val signal = sampleSignal(side = SignalSide.BUY, priceAtSignal = BigDecimal("2000000"))
        val context =
            sampleContext(
                killSwitchOn = true,
                currentCapital = BigDecimal("1000000"),
                dailyRealizedPnl = BigDecimal("-100000"),
                currentPositions = (1..5).map { samplePosition("00${it}000") },
                recentTradesMinute = 10,
                recentTradesDay = 100,
            )

        val result = evaluator.evaluate(signal, context)

        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.KILL_SWITCH_ON)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0110 — SELL 2룰 전략별 분기 + user+code fallback (분할매수 R2-2)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `canary D2 hotfix #4 — strategyPosition quantity=0(원장 오염)이면 실잔고 fallback이 최후 방어선(청산 마비 방지)`() {
        // ADR 0110 원안은 "lot=0 → 차단(다전략 교차 청산 방지)"이었으나, canary D1·D2 에서 strategy_position 원장이
        // 체결 누락·replay 미스로 quantity=0 으로 오염되면 KIS 실잔고 보유가 있는데도 SELL 이 영구 차단(청산 마비)되는
        // 결함이 드러났다(최고 관리자 결재). holdingQuantity 의 takeIf 에 `quantity > 0` 추가 → 원장 0 이면 user+code
        // 실잔고(50주) fallback 을 타 ALLOWED 로 전환(최후 방어선). 정상 lot(>0)은 영향 0(아래 테스트들이 보장).
        val signal = sampleSignal(side = SignalSide.SELL, priceAtSignal = BigDecimal("10000"))
        val context =
            sampleContext(
                currentPositions = listOf(samplePosition("005930", quantity = 50)),
                strategyPosition = sampleStrategyPosition(quantity = 0),
            )

        val result = evaluator.evaluate(signal, context)

        // 원장 0 → 실잔고 50 fallback → 차단되지 않는다(청산 가능).
        assertThat(result).isInstanceOf(RiskGatewayResult.Allowed::class.java)
    }

    @Test
    fun `SELL_WITHOUT_POSITION — strategyPosition 0 + 실잔고도 0이면 그대로 차단`() {
        // 원장 0 + 실잔고도 0(진짜 미보유) → fallback 도 0 → SELL_WITHOUT_POSITION 정상 차단.
        val signal = sampleSignal(side = SignalSide.SELL, priceAtSignal = BigDecimal("10000"))
        val context =
            sampleContext(
                currentPositions = emptyList(),
                strategyPosition = sampleStrategyPosition(quantity = 0),
            )

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.SELL_WITHOUT_POSITION)
    }

    @Test
    fun `SELL ALLOWED — strategyPosition 보유 있으면 그 전략 물량 기준 통과`() {
        val signal = sampleSignal(side = SignalSide.SELL, priceAtSignal = BigDecimal("10000"))
        val context =
            sampleContext(
                currentPositions = listOf(samplePosition("005930", quantity = 50)),
                strategyPosition = sampleStrategyPosition(quantity = 30),
            )

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Allowed::class.java)
    }

    @Test
    fun `SELL fallback — strategyPosition null(분할매수 미사용)이면 user+code 합산 기준(zero-regression)`() {
        val signal = sampleSignal(side = SignalSide.SELL, priceAtSignal = BigDecimal("10000"))
        // strategyPosition null → 기존 user+code 합산 보유로 평가(하위호환).
        val context =
            sampleContext(
                currentPositions = listOf(samplePosition("005930", quantity = 50)),
                strategyPosition = null,
            )

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Allowed::class.java)
    }

    @Test
    fun `SELL_QUANTITY_UNAVAILABLE — 전략 보유 전량이 그 전략 open SELL로 예약되면 차단`() {
        val signal = sampleSignal(side = SignalSide.SELL, priceAtSignal = BigDecimal("10000"))
        val context =
            sampleContext(
                currentPositions = listOf(samplePosition("005930", quantity = 50)),
                strategyPosition = sampleStrategyPosition(quantity = 10),
                // 같은 전략(strategyId=100) open SELL 10주 → 가용 0.
                openOrders = listOf(sampleOpenOrder("005930", OrderSide.SELL, 10)),
            )

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Blocked::class.java)
        assertThat((result as RiskGatewayResult.Blocked).rule).isEqualTo(RiskRule.SELL_QUANTITY_UNAVAILABLE)
    }

    @Test
    fun `SELL ALLOWED — 다른 전략 open SELL은 이 전략 예약 수량에 합산되지 않는다`() {
        val signal = sampleSignal(side = SignalSide.SELL, priceAtSignal = BigDecimal("10000"))
        val context =
            sampleContext(
                currentPositions = listOf(samplePosition("005930", quantity = 50)),
                strategyPosition = sampleStrategyPosition(strategyId = 100L, quantity = 10),
                // 다른 전략(strategyId=999) open SELL은 strategy 분기에서 차감 제외.
                openOrders = listOf(sampleOpenOrderForStrategy("005930", OrderSide.SELL, 10, strategyId = 999L)),
            )

        val result = evaluator.evaluate(signal, context)

        assertThat(result).isInstanceOf(RiskGatewayResult.Allowed::class.java)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // canary D2 hotfix #5 — preCheckFreeRules (공짜 룰 선차단, evaluate 와 의미 동일)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `preCheckFreeRules — KillSwitch ON 즉시 BLOCKED`() {
        val signal = sampleSignal(side = SignalSide.BUY)
        val result =
            evaluator.preCheckFreeRules(
                signal = signal,
                killSwitchOn = true,
                config = sampleConfig(),
                currentPositionCount = 0,
                currentCodes = emptyList(),
                recentTradesMinute = 0,
                recentTradesDay = 0,
            )
        assertThat(result?.rule).isEqualTo(RiskRule.KILL_SWITCH_ON)
    }

    @Test
    fun `preCheckFreeRules — 거래빈도 일 한도 도달 BLOCKED (KIS 호출 전 차단)`() {
        val signal = sampleSignal(side = SignalSide.BUY)
        val result =
            evaluator.preCheckFreeRules(
                signal = signal,
                killSwitchOn = false,
                config = sampleConfig(maxTradesPerDay = 5),
                currentPositionCount = 0,
                currentCodes = emptyList(),
                recentTradesMinute = 0,
                recentTradesDay = 5,
            )
        assertThat(result?.rule).isEqualTo(RiskRule.TRADE_FREQUENCY_DAY)
    }

    @Test
    fun `preCheckFreeRules — BUY 동시보유 한도 도달 BLOCKED`() {
        val signal = sampleSignal(side = SignalSide.BUY)
        val result =
            evaluator.preCheckFreeRules(
                signal = signal,
                killSwitchOn = false,
                config = sampleConfig(maxConcurrentPositions = 2),
                currentPositionCount = 2,
                currentCodes = listOf("000660", "035720"),
                recentTradesMinute = 0,
                recentTradesDay = 0,
            )
        assertThat(result?.rule).isEqualTo(RiskRule.MAX_CONCURRENT_POSITIONS)
    }

    @Test
    fun `preCheckFreeRules — SELL은 동시보유 한도 무관(통과 — 자본 룰은 evaluate가 판정)`() {
        val signal = sampleSignal(side = SignalSide.SELL)
        val result =
            evaluator.preCheckFreeRules(
                signal = signal,
                killSwitchOn = false,
                config = sampleConfig(maxConcurrentPositions = 1),
                currentPositionCount = 9,
                currentCodes = (1..9).map { "00${it}000" },
                recentTradesMinute = 0,
                recentTradesDay = 0,
            )
        // SELL 은 동시보유·거래빈도만 자유 룰 — 동시보유 초과여도 SELL 은 차단 0(BUY 한정 룰).
        assertThat(result).isNull()
    }

    @Test
    fun `preCheckFreeRules — 공짜 룰 모두 통과 null (KIS 호출 단계로 진행)`() {
        val signal = sampleSignal(side = SignalSide.BUY)
        val result =
            evaluator.preCheckFreeRules(
                signal = signal,
                killSwitchOn = false,
                config = sampleConfig(),
                currentPositionCount = 0,
                currentCodes = emptyList(),
                recentTradesMinute = 0,
                recentTradesDay = 0,
            )
        assertThat(result).isNull()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fixtures
    // ─────────────────────────────────────────────────────────────────────────

    private fun sampleSignal(
        id: Long = 1L,
        side: SignalSide = SignalSide.BUY,
        priceAtSignal: BigDecimal = BigDecimal("10000"),
    ): Signal =
        Signal(
            id = id,
            strategyId = 100L,
            userId = "user-A",
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            ts = now,
            side = side,
            strength = 75,
            reason = "test signal",
            priceAtSignal = priceAtSignal,
            contextJson = null,
            createdAt = now,
        )

    private fun sampleConfig(
        maxPositionAmount: BigDecimal = BigDecimal("1000000"),
        maxPositionPercent: BigDecimal = BigDecimal("10.00"),
        dailyLossLimitAmount: BigDecimal? = null,
        dailyLossLimitPercent: BigDecimal? = BigDecimal("3.00"),
        maxConcurrentPositions: Int = 5,
        maxTradesPerMinute: Int = 3,
        maxTradesPerDay: Int = 50,
        autoPauseThreshold: Int = 10,
    ): RiskConfig =
        RiskConfig(
            userId = "user-A",
            maxPositionAmount = maxPositionAmount,
            maxPositionPercent = maxPositionPercent,
            dailyLossLimitAmount = dailyLossLimitAmount,
            dailyLossLimitPercent = dailyLossLimitPercent,
            maxConcurrentPositions = maxConcurrentPositions,
            maxTradesPerMinute = maxTradesPerMinute,
            maxTradesPerDay = maxTradesPerDay,
            autoPauseThreshold = autoPauseThreshold,
            createdAt = now,
            updatedAt = now,
        )

    private fun samplePosition(
        code: String,
        quantity: Int = 10,
    ): Position =
        Position(
            id = null,
            userId = "user-A",
            code = code,
            quantity = quantity,
            avgPrice = BigDecimal("10000"),
            updatedAt = now,
        )

    private fun sampleContext(
        config: RiskConfig = sampleConfig(),
        currentCapital: BigDecimal = BigDecimal("10000000"),
        currentPositions: List<Position> = emptyList(),
        openOrders: List<OrderRequest> = emptyList(),
        dailyRealizedPnl: BigDecimal = BigDecimal.ZERO,
        recentTradesMinute: Int = 0,
        recentTradesDay: Int = 0,
        killSwitchOn: Boolean = false,
        strategyPosition: kr.spdaewoon.magicjar.domain.position.StrategyPosition? = null,
    ): RiskContext =
        RiskContext(
            userId = "user-A",
            config = config,
            currentCapital = currentCapital,
            currentPositions = currentPositions,
            openOrders = openOrders,
            dailyRealizedPnl = dailyRealizedPnl,
            recentTradesMinute = recentTradesMinute,
            recentTradesDay = recentTradesDay,
            killSwitchOn = killSwitchOn,
            nowKst = now,
            strategyPosition = strategyPosition,
        )

    private fun sampleStrategyPosition(
        code: String = "005930",
        strategyId: Long = 100L,
        quantity: Int,
    ): kr.spdaewoon.magicjar.domain.position.StrategyPosition =
        kr.spdaewoon.magicjar.domain.position.StrategyPosition(
            id = 1L,
            userId = "user-A",
            strategyId = strategyId,
            code = code,
            quantity = quantity,
            avgPrice = BigDecimal("10000"),
            openedAt = now,
            updatedAt = now,
        )

    private fun sampleOpenOrder(
        code: String,
        side: OrderSide,
        quantity: Int,
    ): OrderRequest = sampleOpenOrderForStrategy(code, side, quantity, strategyId = 100L)

    private fun sampleOpenOrderForStrategy(
        code: String,
        side: OrderSide,
        quantity: Int,
        strategyId: Long,
    ): OrderRequest =
        OrderRequest(
            id = 10L,
            userId = "user-A",
            signalId = 1L,
            strategyId = strategyId,
            code = code,
            side = side,
            priceType = OrderPriceType.MARKET,
            quantity = quantity,
            limitPrice = null,
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            idempotencyKey = "test-$code-$side-$quantity-$strategyId",
            requestedAt = java.time.Instant.EPOCH,
        )
}
