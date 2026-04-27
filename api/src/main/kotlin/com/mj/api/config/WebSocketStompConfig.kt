package com.mj.api.config

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * STOMP over WebSocket 설정.
 *
 * - 클라이언트 연결: `ws://host/ws` (SockJS fallback 포함)
 * - subscribe 목적지: `/topic/...`
 *
 * spec §4 — `market.tick.v1` 등 Kafka 메시지를 받아 STOMP `/topic/market.tick.<symbol>`로 fan-out.
 *
 * P4 자율 hotfix — STOMP listener는 P2에서 도입됐으나 broker 설정이 누락되어
 * `SimpMessagingTemplate` 빈 부재로 ApplicationContext 기동 실패. spec §4 정합 복원.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketStompConfig : WebSocketMessageBrokerConfigurer {
    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        // 단순 인메모리 브로커 — 운영 시 RabbitMQ/ActiveMQ 등 외부 STOMP 브로커 고려.
        registry.enableSimpleBroker("/topic", "/queue")
        registry.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry
            .addEndpoint("/ws")
            // CORS — frontend dev 서버(5173)·nginx(3000) 모두 허용
            .setAllowedOriginPatterns("*")
            .withSockJS()
    }
}
