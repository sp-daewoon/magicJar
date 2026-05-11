package kr.spdaewoon.magicjar.consumer.application.strategy

import kr.spdaewoon.magicjar.domain.backtest.port.StrategyEvaluatorPort
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import org.springframework.stereotype.Component

/**
 * StrategyEvaluatorPort 구현체 — consumer 모듈 (ADR 0056 §2-4-A + ADR 0004 정합).
 *
 * consumer 모듈에서 domain port를 구현하여 api 모듈에 Bean으로 제공.
 * (api ↔ consumer 직접 의존 금지 — ADR 0004 모듈 경계 정합)
 *
 * ### 의무 사항 (ADR 0047 + ADR 0056)
 * - [JvmStrategyEvaluator.evaluateBacktest] 경유 — 직접 walkRoot 호출 절대 금지
 * - timeout 1000ms + auto-disable=false LOCK은 JvmStrategyEvaluator 내부에서 처리
 * - strategy-eval-pool 4 fixed thread 경유는 JvmStrategyEvaluator 내부에서 처리
 *
 * ### PR-39a ConsecutiveDirectionCalculator 패턴 정합
 * strategy-engine sub-agent 영역 파일(JvmStrategyEvaluator.kt) 수정 0건 — 본 파일만 신규 추가.
 */
@Component("strategyEvaluatorPortImpl")
class JvmStrategyEvaluatorAdapter(
    private val evaluator: JvmStrategyEvaluator,
) : StrategyEvaluatorPort {
    /**
     * 백테스트 평가를 JvmStrategyEvaluator.evaluateBacktest에 위임한다.
     */
    override fun evaluateBacktest(
        strategyId: Long,
        ast: DslNode,
        slice: List<Candle>,
    ): StrategyEvalResult = evaluator.evaluateBacktest(strategyId, ast, slice)
}
