package com.mj.consumer.messaging.listener

import com.mj.consumer.application.journal.TradeJournalRecorder
import com.mj.consumer.messaging.producer.JournalEntryPublisher
import com.mj.domain.common.Mode
import com.mj.domain.common.OrderStatus
import com.mj.domain.journal.event.JournalEntryEvent
import com.mj.domain.order.event.OrderExecutedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * order.executed.v1 → TradeJournal 영속 → JournalEntryPublisher.
 *
 * TradeJournalRecorder를 통해 도메인 포트(TradeJournalRepository) 호출.
 * FILLED 상태 체결만 기록한다.
 */
@Component
class JournalRecorderListener(
    private val journalRecorder: TradeJournalRecorder,
    private val journalEntryPublisher: JournalEntryPublisher,
) {
    private val log = LoggerFactory.getLogger(JournalRecorderListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.orderExecuted:order.executed.v1}"],
        groupId = "\${spring.kafka.consumer.group-id:magicjar-consumer}",
    )
    fun onOrderExecuted(event: OrderExecutedEvent) {
        log.debug("JournalRecorderListener received (orderId={})", event.orderId)
        if (event.status != OrderStatus.FILLED) {
            log.debug("JournalRecorderListener skip non-FILLED (orderId={}, status={})", event.orderId, event.status)
            return
        }
        runCatching {
            val mode = runCatching { Mode.valueOf(event.mode) }.getOrDefault(Mode.MOCK)
            val tradeId = UUID.randomUUID()
            val entryId =
                journalRecorder.recordEntry(
                    tradeId = tradeId,
                    mode = mode,
                    code = event.code,
                    name = event.code,
                    qty = event.executedQty,
                    price = event.executedPrice,
                    at = event.executedAt,
                    strategyId = "order-dispatch",
                    entryReason = "Kafka OrderExecutedEvent orderId=${event.orderId}",
                )
            val journalEvent =
                JournalEntryEvent(
                    entryId = entryId,
                    tradeId = tradeId,
                    code = event.code,
                    mode = event.mode,
                    pnl = null,
                    pnlRate = null,
                    strategyId = "order-dispatch",
                    createdAt = Instant.now(),
                )
            journalEntryPublisher.publish(journalEvent)
        }.onFailure { ex ->
            log.error("JournalRecorderListener failed (orderId={}): {}", event.orderId, ex.message)
        }
    }
}
