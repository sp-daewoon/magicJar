package kr.spdaewoon.magicjar.consumer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.ingestion.OrderbookSubscriptionRouter
import kr.spdaewoon.magicjar.consumer.ingestion.RealtimeDemandOwner
import kr.spdaewoon.magicjar.consumer.ingestion.RealtimeSubscriptionRouter
import kr.spdaewoon.magicjar.consumer.reconciliation.ManagedCandleUniverseCache
import kr.spdaewoon.magicjar.consumer.reconciliation.SymbolDetailMinuteGapFillService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * symbol.detail.command.v1 Kafka consumer — 종목 상세 실시간 slot 라이프사이클 (ADR 0048 §2-1).
 *
 * payload: {"action": "ENTER"|"EXIT", "code": "005930", "userId": "...", "ts": "..."}
 * - ENTER → RealtimeSubscriptionRouter DETAIL + OrderbookSubscriptionRouter
 * - EXIT  → RealtimeSubscriptionRouter DETAIL release + OrderbookSubscriptionRouter release
 *
 * groupId: magicjar-consumer-symbol-detail
 * manual ack (MANUAL_IMMEDIATE)
 *
 * KIS-first 의무 — 현재가·호가 slot 실제 subscribe/unsubscribe는 KisWebSocketBridge 통과.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class SymbolDetailCommandKafkaListener(
    private val objectMapper: ObjectMapper,
    private val realtimeSubscriptionRouter: RealtimeSubscriptionRouter,
    private val orderbookSubscriptionRouter: OrderbookSubscriptionRouter,
    private val universeCache: ManagedCandleUniverseCache,
    private val minuteGapFillService: SymbolDetailMinuteGapFillService,
) {
    private val log = LoggerFactory.getLogger(SymbolDetailCommandKafkaListener::class.java)

    @KafkaListener(
        topics = ["symbol.detail.command.v1"],
        groupId = "magicjar-consumer-symbol-detail",
        containerFactory = "manualImmediateKafkaListenerContainerFactory",
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
            val userId = payload["userId"]?.toString() ?: "unknown"
            val ownerId = "detail:$userId"

            log.info("symbol.detail.command 수신: action={} code={}", action, code)

            when (action) {
                "ENTER" -> {
                    realtimeSubscriptionRouter.request(
                        owner = RealtimeDemandOwner.DETAIL,
                        ownerId = ownerId,
                        code = code,
                    )
                    orderbookSubscriptionRouter.onSymbolDetailEntered(code)
                    universeCache.recordDetail(code)
                    minuteGapFillService.submitIfNeeded(code)
                }
                "EXIT" -> {
                    orderbookSubscriptionRouter.onSymbolDetailExited(code)
                    realtimeSubscriptionRouter.release(
                        owner = RealtimeDemandOwner.DETAIL,
                        ownerId = ownerId,
                        code = code,
                    )
                }
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
