package com.mj.consumer.messaging.producer

import com.mj.domain.journal.event.JournalEntryEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/** journal.entry.v1 토픽으로 매매일지 영속 완료를 발행한다. */
@Component
class JournalEntryPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.journalEntry:journal.entry.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(JournalEntryPublisher::class.java)

    fun publish(event: JournalEntryEvent) {
        kafka
            .send(topic, event.code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("JournalEntryPublisher send failed (entryId={}): {}", event.entryId, ex.message)
                } else {
                    log.debug("JournalEntryPublisher sent (entryId={})", event.entryId)
                }
            }
    }
}
