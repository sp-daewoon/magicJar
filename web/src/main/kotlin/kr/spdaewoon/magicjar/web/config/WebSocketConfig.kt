package kr.spdaewoon.magicjar.web.config

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * WebSocket/STOMP 브로커 구성.
 *
 * 현재는 빈 허브로만 존재한다. 실시간 시세 이벤트 타입(예: `RealtimeQuoteEvent`)은 아직
 * `core.event` 에 정의되어 있지 않고, market-data 모듈이 Phase B-2 완료 후 core에
 * 이벤트 타입을 추가·머지해야 한다. 그 시점에 [QuoteStreamHub]가 ApplicationEvent 를
 * 구독하고 `SimpMessagingTemplate.convertAndSend("/topic/quotes/{code}", ...)` 로 중계한다.
 *
 * ADR 참조: `_workspace/arch/module-boundaries.md` (web 섹션)
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {
    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        // 클라이언트가 구독할 prefix. 홈/종목 페이지가 /topic/quotes/{code}, /topic/orders 를 구독할 예정.
        config.enableSimpleBroker("/topic")
        // 클라이언트가 서버로 메시지를 보낼 때의 prefix. 현재 읽기 전용이라 사용처 없음.
        config.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        // SockJS 없이 순수 WS. 추후 프런트에서 fallback 필요 시 .withSockJS() 추가.
        registry
            .addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
    }
}
