package com.mj.consumer.messaging.producer

import com.mj.domain.news.event.NewsAnalyzedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/** news.analyzed.v1 토픽으로 AI 감성분석 완료 뉴스를 발행한다. */
@Component
class NewsAnalyzedPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.newsAnalyzed:news.analyzed.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(NewsAnalyzedPublisher::class.java)

    fun publish(event: NewsAnalyzedEvent) {
        kafka
            .send(topic, event.newsId.toString(), event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("NewsAnalyzedPublisher send failed (newsId={}): {}", event.newsId, ex.message)
                } else {
                    log.debug("NewsAnalyzedPublisher sent (newsId={})", event.newsId)
                }
            }
    }
}
