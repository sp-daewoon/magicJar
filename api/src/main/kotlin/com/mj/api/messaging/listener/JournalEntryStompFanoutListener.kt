package com.mj.api.messaging.listener

import com.mj.domain.journal.event.JournalEntryEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * journal.entry.v1 → STOMP /topic/journal fan-out.
 */
@Component
class JournalEntryStompFanoutListener(
    private val stomp: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(JournalEntryStompFanoutListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.journalEntry:journal.entry.v1}"],
        groupId = "magicjar-api-stomp",
    )
    fun onJournalEntry(event: JournalEntryEvent) {
        stomp.convertAndSend("/topic/journal", event)
        log.debug("JournalEntryStompFanout → /topic/journal (entryId={})", event.entryId)
    }
}
