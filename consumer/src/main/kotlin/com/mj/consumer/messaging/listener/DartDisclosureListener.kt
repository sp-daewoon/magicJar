package com.mj.consumer.messaging.listener

import com.mj.domain.journal.NewsItem
import com.mj.domain.journal.port.NewsRepository
import com.mj.domain.news.event.DartDisclosureEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * dart.disclosure.v1 → DB 영속.
 *
 * DART 공시를 NewsItem 형태로 저장한다.
 */
@Component
class DartDisclosureListener(
    private val newsRepository: NewsRepository,
) {
    private val log = LoggerFactory.getLogger(DartDisclosureListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.dartDisclosure:dart.disclosure.v1}"],
        groupId = "\${spring.kafka.consumer.group-id:magicjar-consumer}",
    )
    fun onDartDisclosure(event: DartDisclosureEvent) {
        log.debug("DartDisclosureListener received (disclosureId={})", event.disclosureId)
        runCatching {
            val newsItem =
                NewsItem(
                    id = event.disclosureId,
                    source = "DART",
                    publishedAt = event.filedAt,
                    title = "[${event.disclosureType}] ${event.companyName} - ${event.title}",
                    body = event.url ?: "",
                    instruments = listOf(event.code),
                )
            newsRepository.save(listOf(newsItem))
            log.debug("DartDisclosureListener saved disclosure (id={})", event.disclosureId)
        }.onFailure { ex ->
            log.error("DartDisclosureListener failed (disclosureId={}): {}", event.disclosureId, ex.message)
        }
    }
}
