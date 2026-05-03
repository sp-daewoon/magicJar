package com.mj.consumer.application.signal

import com.mj.domain.strategy.dsl.Condition
import com.mj.domain.strategy.dsl.ConditionOperator
import com.mj.domain.strategy.dsl.IndicatorOperand
import com.mj.domain.strategy.dsl.IndicatorRef
import com.mj.domain.strategy.dsl.LogicalGroup
import com.mj.domain.strategy.dsl.LogicalOp
import com.mj.domain.strategy.dsl.StrategyBody
import com.mj.domain.strategy.dsl.StrategyDsl
import com.mj.domain.strategy.dsl.StrategyMetadata

/**
 * 활성 전략 + 사용자 구독 (R1, hard-code 1전략).
 *
 * R1 한정 — 단일 전략 `golden_cross_5_20`을 모든 종목·anonymous 사용자에 적용.
 * R2에서 `UserStrategySubscription` 영속 모델 + DB 조회로 교체 예정.
 *
 * 결정 사유:
 *   - R1은 Kafka 흐름·DSL 평가 결과를 BUY/SELL/HOLD로 변환하는 파이프라인을 검증하는 단계.
 *   - 다중 전략·다중 사용자는 R2에서 본격.
 */
class ActiveStrategyRegistry(
    private val initialSubscriptions: List<StrategySubscription> = listOf(defaultSubscription()),
) {
    /**
     * symbol에 적용할 전략 구독 목록.
     *
     * R1은 모든 종목 동일 → initialSubscriptions 그대로.
     * R2에서 종목별 구독 / sector별 구독 / 사용자별 구독 도입.
     */
    fun subscriptionsFor(symbol: String): List<StrategySubscription> = initialSubscriptions

    companion object {
        /**
         * R1 default — 골든크로스 5/20 전략 (KIS 정합 preset 정합).
         *
         * - entry: MA(5) cross_above MA(20)
         * - exit:  MA(5) cross_below MA(20)
         */
        fun defaultSubscription(): StrategySubscription =
            StrategySubscription(
                userId = null,
                strategyDsl = goldenCrossFiveTwenty(),
            )

        private fun goldenCrossFiveTwenty(): StrategyDsl =
            StrategyDsl(
                version = "1.0",
                metadata =
                    StrategyMetadata(
                        name = "골든크로스 5/20",
                        description = "5일·20일 이동평균 골든크로스 진입 + 데드크로스 청산",
                        tags = listOf("trend_following", "ma_cross"),
                    ),
                strategy =
                    StrategyBody(
                        id = "golden_cross_5_20",
                        category = "trend_following",
                        indicators =
                            listOf(
                                IndicatorRef(id = "ma", alias = "ma5", params = mapOf("period" to 5)),
                                IndicatorRef(id = "ma", alias = "ma20", params = mapOf("period" to 20)),
                            ),
                        entry =
                            LogicalGroup(
                                logic = LogicalOp.AND,
                                nodes =
                                    listOf(
                                        Condition(
                                            lhs = IndicatorOperand(alias = "ma5"),
                                            operator = ConditionOperator.CROSS_ABOVE,
                                            rhs = IndicatorOperand(alias = "ma20"),
                                        ),
                                    ),
                            ),
                        exit =
                            LogicalGroup(
                                logic = LogicalOp.AND,
                                nodes =
                                    listOf(
                                        Condition(
                                            lhs = IndicatorOperand(alias = "ma5"),
                                            operator = ConditionOperator.CROSS_BELOW,
                                            rhs = IndicatorOperand(alias = "ma20"),
                                        ),
                                    ),
                            ),
                    ),
            )
    }
}

/**
 * 단일 전략 구독.
 *
 * @property userId      사용자 식별자 (옵션, anonymous=null)
 * @property strategyDsl 평가할 전략
 */
data class StrategySubscription(
    val userId: String?,
    val strategyDsl: StrategyDsl,
)
