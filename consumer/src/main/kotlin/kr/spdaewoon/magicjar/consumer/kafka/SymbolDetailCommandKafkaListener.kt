package kr.spdaewoon.magicjar.consumer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.ingestion.OrderbookSubscriptionRouter
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * symbol.detail.command.v1 Kafka consumer — 호가 slot 라이프사이클 (ADR 0048 §2-1).
 *
 * payload: {"action": "ENTER"|"EXIT", "code": "005930", "userId": "...", "ts": "..."}
 * - ENTER → OrderbookSubscriptionRouter.onSymbolDetailEntered(code)
 * - EXIT  → OrderbookSubscriptionRouter.onSymbolDetailExited(code)
 *
 * groupId: magicjar-consumer-symbol-detail
 * manual ack (MANUAL_IMMEDIATE)
 *
 * KIS-first 의무 — 호가 slot 실제 subscribeOrderbook/unsubscribeOrderbook은
 * KisWebSocketBridge(H0STASP0) 통과.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class SymbolDetailCommandKafkaListener(
    private val objectMapper: ObjectMapper,
    private val orderbookSubscriptionRouter: OrderbookSubscriptionRouter,
) {
    private val log = LoggerFactory.getLogger(SymbolDetailCommandKafkaListener::class.java)

    @KafkaListener(
        topics = ["symbol.detail.command.v1"],
        groupId = "magicjar-consumer-symbol-detail",
    )
    fun onMessage(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val action =
                payload["action"]?.toString() ?: run {
                    ack.acknowledge()
                    return
                }
            val code =
                payload["code"]?.toString() ?: run {
                    ack.acknowledge()
                    return
                }

            log.info("symbol.detail.command 수신: action={} code={}", action, code)

            when (action) {
                "ENTER" -> orderbookSubscriptionRouter.onSymbolDetailEntered(code)
                "EXIT" -> orderbookSubscriptionRouter.onSymbolDetailExited(code)
                else -> log.warn("알 수 없는 action: {} — 무시", action)
            }
            ack.acknowledge()
        } catch (e: Exception) {
            log.error(
                "symbol.detail.command.v1 처리 실패 — offset={} err={}",
                record.offset(),
                e.message,
                e,
            )
            // 파싱/처리 오류 시 ack (orderbook 명령은 재처리 의미 없음 — 이미 페이지 전환됨)
            ack.acknowledge()
        }
    }
}
