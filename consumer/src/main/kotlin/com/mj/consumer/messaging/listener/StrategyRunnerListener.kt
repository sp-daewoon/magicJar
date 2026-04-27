package com.mj.consumer.messaging.listener

import com.mj.consumer.application.strategy.DslStrategyEvaluator
import com.mj.consumer.messaging.producer.SignalPublisher
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.event.CandleEvent
import com.mj.domain.strategy.dsl.BarEvaluationContext
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * market.candle.1m.v1 → DSL 전략 평가 → SignalPublisher.
 *
 * 전략 목록은 P3에서 DB 연동으로 교체. 현재는 placeholder 평가만 수행.
 */
@Component
class StrategyRunnerListener(
    private val evaluator: DslStrategyEvaluator,
    private val signalPublisher: SignalPublisher,
) {
    private val log = LoggerFactory.getLogger(StrategyRunnerListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.candle1m:market.candle.1m.v1}"],
        groupId = "\${spring.kafka.consumer.group-id:magicjar-consumer}",
    )
    fun onCandle(event: CandleEvent) {
        log.debug("StrategyRunnerListener received candle (code={}, openTime={})", event.code, event.openTime)
        // TODO P3: DB에서 활성 전략 목록 로드 후 각 전략 평가
        // 현재는 스켈레톤 — 평가 예시만 남긴다.
    }

    private fun toCandle(event: CandleEvent): Candle =
        Candle(
            code = event.code,
            interval = Interval.M1,
            openTime = event.openTime,
            open = event.open,
            high = event.high,
            low = event.low,
            close = event.close,
            volume = event.volume,
            tradeCount = event.tradeCount,
        )

    private fun buildContext(candle: Candle): BarEvaluationContext =
        BarEvaluationContext(
            candles = listOf(candle),
            currentIndex = 0,
        )
}
