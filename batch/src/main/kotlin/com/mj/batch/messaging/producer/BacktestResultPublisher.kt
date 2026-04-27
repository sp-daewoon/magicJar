package com.mj.batch.messaging.producer

import com.mj.domain.common.BacktestResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/** 백테스트 종합 결과를 backtest.result.v1 토픽으로 발행한다. */
@Component
class BacktestResultPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.backtestResult:backtest.result.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(BacktestResultPublisher::class.java)

    fun publish(result: BacktestResult) {
        kafka
            .send(topic, result.strategy.strategyId, result)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn(
                        "BacktestResultPublisher send failed (strategy={}): {}",
                        result.strategy.strategyId,
                        ex.message,
                    )
                } else {
                    log.debug("BacktestResultPublisher sent (strategy={})", result.strategy.strategyId)
                }
            }
    }
}
