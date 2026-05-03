package com.mj.domain.strategy.signal

import com.mj.domain.common.Candle
import com.mj.domain.strategy.dsl.EvalReason
import com.mj.domain.strategy.dsl.StrategyDsl
import com.mj.domain.strategy.dsl.StrategyDslEvaluator
import java.time.Instant
import java.util.UUID

/**
 * 실시간 Signal Engine — DSL + 캔들 시계열 → BUY/SELL/HOLD (트랙11 R1, ADR 0041).
 *
 * 룰 매칭 only. AI 호출 0. T7 [StrategyDslEvaluator]를 그대로 위임 받아
 * entry/exit 평가 결과를 [SignalAction]으로 분류 + 강도·이유를 [Signal]에 포장.
 *
 * 룩어헤드 차단:
 *   - candles[0..asOfBar] 만 evaluator에 넘긴다.
 *   - asOfBar 이후 봉이 caller가 실수로 포함시켜도 sub-list로 잘라내 안전.
 *
 * 결정론:
 *   - 같은 dsl + 같은 candles + 같은 asOfBar → 같은 Signal (eventId/generatedAt 제외).
 *   - 난수 사용 0.
 *
 * 의존성:
 *   - StrategyDslEvaluator (T7) — 단일 책임. SignalEngine은 액션 분류만.
 *   - Clock 주입(테스트 결정론) — default Clock.systemUTC() 등가.
 */
class SignalEngine(
    private val dslEvaluator: StrategyDslEvaluator,
    private val clock: () -> Instant = { Instant.now() },
    private val idGenerator: () -> UUID = { UUID.randomUUID() },
) {
    /**
     * 시그널 생성.
     *
     * @param request 평가 요청 — strategyDsl·symbol·candles·asOfBar
     * @return [Signal] 객체 (BUY/SELL/HOLD + strength + reasons)
     */
    fun generate(request: SignalEvalRequest): Signal {
        require(request.candles.isNotEmpty()) { "candles must not be empty" }
        require(request.asOfBar in request.candles.indices) {
            "asOfBar ${request.asOfBar} out of bounds [0, ${request.candles.lastIndex}]"
        }

        // 룩어헤드 차단 — asOfBar 이후 봉 절단.
        val viewable = request.candles.subList(0, request.asOfBar + 1)
        val currentIndex = viewable.lastIndex

        val evalResult = dslEvaluator.evaluate(request.strategyDsl, viewable, currentIndex)

        val action = classifyAction(entry = evalResult.entry, exit = evalResult.exit)
        val triggeredReasons =
            when (action) {
                is SignalAction.Sell -> evalResult.exitReasons
                is SignalAction.Buy -> evalResult.entryReasons
                SignalAction.Hold -> evalResult.entryReasons + evalResult.exitReasons
            }

        return Signal(
            eventId = idGenerator(),
            strategyId = request.strategyDsl.strategy.id,
            symbol = request.symbol,
            action = action,
            generatedAt = clock(),
            evaluatedAt = evalResult.evaluatedAt,
            reasons = triggeredReasons,
            userId = request.userId,
        )
    }

    /**
     * Entry/Exit boolean → SignalAction.
     *
     * SELL이 BUY보다 우선 — 청산은 진입에 앞서야 한다 (포지션 위험 관리).
     * 동시 entry+exit true 케이스에서도 SELL.
     */
    private fun classifyAction(
        entry: Boolean,
        exit: Boolean,
    ): SignalAction =
        when {
            exit -> SignalAction.Sell(strength = 1.0)
            entry -> SignalAction.Buy(strength = 1.0)
            else -> SignalAction.Hold
        }
}

/**
 * 시그널 평가 요청.
 *
 * @property strategyDsl 평가할 전략
 * @property symbol      종목코드 (예: "005930")
 * @property candles     시간 오름차순 캔들 시계열 — asOfBar 이후 봉이 포함되어도 SignalEngine이 잘라낸다
 * @property asOfBar     평가 기준 봉 인덱스. 보통 candles.lastIndex
 * @property userId      사용자 식별자 (옵션, 멀티테넌트 fanout용). default null
 */
data class SignalEvalRequest(
    val strategyDsl: StrategyDsl,
    val symbol: String,
    val candles: List<Candle>,
    val asOfBar: Int,
    val userId: String? = null,
)

/**
 * 시그널 평가 결과.
 *
 * @property eventId     발행 단위 ID (Kafka 멱등 dedup·UI tracking용)
 * @property strategyId  발생시킨 전략 id
 * @property symbol      대상 종목
 * @property action      BUY / SELL / HOLD
 * @property generatedAt SignalEngine이 시그널을 만든 wall clock (KST/UTC 무관, Instant)
 * @property evaluatedAt 평가 기준 봉의 openTime
 * @property reasons     해당 action을 트리거한 조건들의 trace
 * @property userId      사용자 식별자 (옵션)
 */
data class Signal(
    val eventId: UUID,
    val strategyId: String,
    val symbol: String,
    val action: SignalAction,
    val generatedAt: Instant,
    val evaluatedAt: Instant,
    val reasons: List<EvalReason>,
    val userId: String? = null,
) {
    /** strength shortcut. */
    val strength: Double get() = action.strengthValue()
}
