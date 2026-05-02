package kr.spdaewoon.magicjar.web.websocket

import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * 실시간 시세 중계 허브 (스텁).
 *
 * TODO: market-data 모듈이 core에 `RealtimeQuoteEvent(code, price, volume, at)` 이벤트 타입을
 * 추가하고 Spring `ApplicationEvent` 로 발행하기 시작하면, 아래 주석의 `@EventListener` 를
 * 되살려 `/topic/quotes/{code}` 로 push 한다. 현재는 빈 컨테이너.
 *
 * 원칙 (module-boundaries):
 *   - market-data 의 구현 클래스를 직접 import 금지.
 *   - core.event 에 정의된 이벤트 타입만 구독한다.
 */
@Component
class QuoteStreamHub(
    private val messagingTemplate: SimpMessagingTemplate,
) {
    // TODO: core.event.RealtimeQuoteEvent 도입 후 아래 리스너 활성화
    // @EventListener
    // fun onQuote(event: RealtimeQuoteEvent) {
    //     messagingTemplate.convertAndSend("/topic/quotes/${event.code}", event)
    // }

    /** 테스트/진단용: 외부에서 수동 push 해볼 수 있는 창구. 프로덕션에서는 사용되지 않는다. */
    fun pushQuote(
        code: String,
        payload: Any,
    ) {
        messagingTemplate.convertAndSend("/topic/quotes/$code", payload)
    }
}
