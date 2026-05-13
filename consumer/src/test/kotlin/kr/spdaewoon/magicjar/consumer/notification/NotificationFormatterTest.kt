package kr.spdaewoon.magicjar.consumer.notification

import kr.spdaewoon.magicjar.domain.notification.NotificationEventType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NotificationFormatter 단위 테스트 (spec §2-4 LOCK).
 *
 * 7 케이스 — 트리거별 메시지 포맷 검증:
 *  (1) ORDER_FILLED — 체결 메시지
 *  (2) ORDER_REJECTED — 거절 메시지
 *  (3) ORDER_CANCELLED — 취소 메시지
 *  (4) RISK_VIOLATION — 경고 + 즉시 조치
 *  (5) RECONCILE_DRIFT — 경보 + 즉시 조치
 *  (6) KILLSWITCH_ENGAGED — 경보 + 즉시 조치
 *  (7) RECONCILE_RUN — audit 메시지
 *
 * AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2).
 */
class NotificationFormatterTest {
    @Test
    fun `(1) ORDER_FILLED — 체결 메시지 포함 검증`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_FILLED,
                reason = "체결: 005930 BUY 10주 @ 75000",
                meta = emptyMap(),
            )
        assertThat(msg).contains("주문 체결")
        assertThat(msg).contains("1234****") // 마스킹
        assertThat(msg).contains("체결: 005930")
        assertThat(msg).doesNotContain("즉시 조치")
    }

    @Test
    fun `(2) ORDER_REJECTED — 거절 메시지 + 마스킹`() {
        val msg =
            NotificationFormatter.format(
                userId = "9999",
                eventType = NotificationEventType.ORDER_REJECTED,
                reason = "주문 거절: 005930 BUY — KILLSWITCH_ON",
                meta = emptyMap(),
            )
        assertThat(msg).contains("주문 거절")
        assertThat(msg).contains("****") // 4자 미만도 마스킹
    }

    @Test
    fun `(3) ORDER_CANCELLED — 취소 메시지`() {
        val msg =
            NotificationFormatter.format(
                userId = "U1",
                eventType = NotificationEventType.ORDER_CANCELLED,
                reason = "주문 취소: 005930 SELL (주문번호 K123)",
                meta = emptyMap(),
            )
        assertThat(msg).contains("주문 취소")
        assertThat(msg).contains("취소")
    }

    @Test
    fun `(4) RISK_VIOLATION — 경고 이모지 + 즉시 조치 포함`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.RISK_VIOLATION,
                reason = "리스크 위반: 005930 rule=MAX_LOSS_PER_TRADE",
                meta = emptyMap(),
            )
        assertThat(msg).contains("리스크 위반")
        assertThat(msg).contains("즉시 조치")
        assertThat(msg).contains("리스크 한도")
    }

    @Test
    fun `(5) RECONCILE_DRIFT — 경보 + 즉시 KillSwitch OFF 조치 포함`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.RECONCILE_DRIFT,
                reason = "포지션 불일치: 005930 KIS=100주 내부=99주 diff=1",
                meta = emptyMap(),
            )
        assertThat(msg).contains("포지션 불일치")
        assertThat(msg).contains("즉시 조치")
        assertThat(msg).contains("KillSwitch OFF")
    }

    @Test
    fun `(6) KILLSWITCH_ENGAGED — 경보 + 즉시 재활성화 조치 포함`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.KILLSWITCH_ENGAGED,
                reason = "KillSwitch 발동: reason=POSITION_DRIFT triggeredBy=AUTO",
                meta = emptyMap(),
            )
        assertThat(msg).contains("KillSwitch 자동 발동")
        assertThat(msg).contains("즉시 조치")
        assertThat(msg).contains("재활성화")
    }

    @Test
    fun `(7) RECONCILE_RUN — audit 메시지 즉시 조치 없음`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.RECONCILE_RUN,
                reason = "잔고 점검 완료: status=RECONCILED driftCount=0",
                meta = emptyMap(),
            )
        assertThat(msg).contains("잔고 점검")
        assertThat(msg).doesNotContain("즉시 조치")
    }
}
