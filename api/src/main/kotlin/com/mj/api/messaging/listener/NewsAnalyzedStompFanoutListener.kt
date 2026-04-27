package com.mj.api.messaging.listener

import com.mj.domain.news.event.NewsAnalyzedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * news.analyzed.v1 → STOMP /topic/news fan-out.
 */
@Component
class NewsAnalyzedStompFanoutListener(
    private val stomp: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(NewsAnalyzedStompFanoutListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.newsAnalyzed:news.analyzed.v1}"],
        groupId = "magicjar-api-stomp",
    )
    fun onNewsAnalyzed(event: NewsAnalyzedEvent) {
        stomp.convertAndSend("/topic/news", event)
        log.debug("NewsAnalyzedStompFanout → /topic/news (newsId={})", event.newsId)
    }
}
