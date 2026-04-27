package com.mj.api.messaging.producer

import com.mj.domain.news.event.NewsRawEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * pyworker HTTP 스크래핑 결과를 news.raw.v1 토픽으로 발행한다 (선택).
 *
 * pyworker가 HTTP POST로 api에 결과를 전달하면 api가 Kafka에 릴레이.
 */
@Component
class ScrapeRequestPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.newsRaw:news.raw.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(ScrapeRequestPublisher::class.java)

    fun publish(event: NewsRawEvent) {
        kafka
            .send(topic, event.newsId.toString(), event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("ScrapeRequestPublisher send failed (newsId={}): {}", event.newsId, ex.message)
                } else {
                    log.debug("ScrapeRequestPublisher sent (newsId={})", event.newsId)
                }
            }
    }
}
