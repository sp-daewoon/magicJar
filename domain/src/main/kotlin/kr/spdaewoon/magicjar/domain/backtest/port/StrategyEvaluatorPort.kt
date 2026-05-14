package kr.spdaewoon.magicjar.domain.backtest.port

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode

/**
 * 전략 평가기 포트 — 백테스트 + 실시간 평가 (ADR 0056 §2-4 + ADR 0004 모듈 경계 정합).
 *
 * ### 모듈 경계 (ADR 0004)
 * - api, batch, consumer는 서로 직접 의존하지 않음
 * - 따라서 api 모듈은 consumer의 JvmStrategyEvaluator를 직접 import 불가
 * - domain port 인터페이스를 통한 의존성 역전 (헥사고날 정합)
 *
 * ### 구현
 * - consumer 모듈: JvmStrategyEvaluatorAdapter (JvmStrategyEvaluator 위임)
 * - api 모듈 1차: HoldStrategyEvaluatorStub (stub — 백테스트 없이 HOLD 반환)
 *   → 1차 stateless REST run은 직접 실행 불가 (consumer Bean 없음)
 *   → 실제 백테스트는 consumer 모듈이 Bean으로 등록 후 실행
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 */
interface StrategyEvaluatorPort {
    /**
     * 백테스트 평가를 수행한다.
     *
     * @param strategyId 전략 ID
     * @param ast 평가할 DSL AST
     * @param slice 룩어헤드 가드를 통과한 캔들 슬라이스 (t 시점까지만)
     * @return StrategyEvalResult (signal=BUY·SELL·HOLD + reason)
     * @throws kr.spdaewoon.magicjar.domain.strategy.StrategyException.StrategyTimeoutException timeout 초과 시
     */
    fun evaluateBacktest(
        strategyId: Long,
        ast: DslNode,
        slice: List<Candle>,
    ): StrategyEvalResult

    /**
     * 실시간 평가를 수행한다 (universe screener + 시그널 생성 공용).
     *
     * timeout 100ms sandbox 정책은 구현체(consumer JvmStrategyEvaluatorAdapter) 내부 책임.
     *
     * @param strategyId 전략 ID
     * @param ast 평가할 DSL AST (buyCondition)
     * @param candles 평가 시점까지의 캔들 (시간순 오름차순)
     * @return StrategyEvalResult (signal=BUY·SELL·HOLD + reason)
     * @throws kr.spdaewoon.magicjar.domain.strategy.StrategyException.StrategyTimeoutException timeout 초과 시
     * @throws kr.spdaewoon.magicjar.domain.strategy.StrategyException DSL 평가 오류 시
     */
    fun evaluate(
        strategyId: Long,
        ast: DslNode,
        candles: List<Candle>,
    ): StrategyEvalResult
}
