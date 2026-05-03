package com.mj.api.messaging.listener

import com.mj.domain.market.event.TickEvent
import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketSessionResolver
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * market.tick.v1 → STOMP /topic/market.tick.{symbol} fan-out.
 *
 * 프론트엔드 웹소켓 클라이언트에게 실시간 체결 데이터를 푸시한다.
 *
 * stale 마킹 (T2-T15, ADR 0033):
 *   - 장 상태가 POST_MARKET / CLOSED / HOLIDAY 일 때 → stale=true, asOf=tick.at
 *   - 정규장(Open) 또는 PreMarket 일 때 → TickEvent 원본 stale/asOf 유지
 *   - sessionResolver 예외 시 → stale 무변경 (원본 그대로)
 */
@Component
class TickStompFanoutListener(
    private val stomp: SimpMessagingTemplate,
    private val sessionResolver: MarketSessionResolver,
) {
    private val log = LoggerFactory.getLogger(TickStompFanoutListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.tick:market.tick.v1}"],
        groupId = "magicjar-api-stomp",
    )
    fun onTick(event: TickEvent) {
        val fanoutEvent = applyStaleMarking(event)
        val destination = "/topic/market.tick.${fanoutEvent.code}"
        stomp.convertAndSend(destination, fanoutEvent)
        log.debug(
            "TickStompFanout → {} (price={} stale={} source={})",
            destination,
            fanoutEvent.price,
            fanoutEvent.stale,
            fanoutEvent.source,
        )
    }

    /**
     * 장 상태에 따라 stale/asOf를 조정한 TickEvent를 반환한다.
     * POST_MARKET·CLOSED·HOLIDAY → stale=true, asOf=tick.at
     * 그 외 → 원본 TickEvent 반환 (source=MOCK은 항상 LIVE 처리)
     */
    private fun applyStaleMarking(event: TickEvent): TickEvent {
        // mock source는 항상 LIVE (stale 무변경)
        if (event.source == "MOCK") return event

        return try {
            val session = sessionResolver.resolve()
            val shouldBeStale =
                when (session.state) {
                    is MarketSessionState.PostMarket,
                    is MarketSessionState.Closed,
                    is MarketSessionState.Holiday,
                    -> true
                    else -> event.stale // Open / PreMarket → 원본 유지
                }

            if (shouldBeStale && !event.stale) {
                event.copy(
                    stale = true,
                    asOf = event.asOf ?: event.at,
                )
            } else {
                event
            }
        } catch (e: Exception) {
            log.debug("TickStompFanout: sessionResolver error, skip stale marking: {}", e.message)
            event
        }
    }
}
