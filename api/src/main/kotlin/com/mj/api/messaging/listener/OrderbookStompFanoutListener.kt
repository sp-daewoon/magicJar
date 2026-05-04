package com.mj.api.messaging.listener

import com.mj.domain.market.event.OrderbookEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * `market.orderbook.v1` → STOMP `/topic/market.orderbook.{code}` fan-out.
 *
 * SymbolDetail 페이지 Orderbook 컴포넌트가 1초 간격 KIS WS 호가 frame으로 갱신.
 *
 * Refs: plan T2-R2-A2 §T15.
 */
@Component
class OrderbookStompFanoutListener(
    private val stomp: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(OrderbookStompFanoutListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.marketOrderbook:market.orderbook.v1}"],
        groupId = "magicjar-api-orderbook-stomp",
    )
    fun onOrderbook(event: OrderbookEvent) {
        val destination = "/topic/market.orderbook.${event.code}"
        stomp.convertAndSend(destination, event)
        log.debug(
            "OrderbookStompFanout → {} (ask1={} bid1={})",
            destination,
            event.asks.firstOrNull()?.price,
            event.bids.firstOrNull()?.price,
        )
    }
}
