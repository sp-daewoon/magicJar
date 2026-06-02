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
                reason = "체결: [MOCK] 삼성전자(005930) 매수 10주 @ 75,000원",
                meta = emptyMap(),
            )
        assertThat(msg).contains("주문 체결")
        assertThat(msg).contains("1234****") // 마스킹
        assertThat(msg).contains("삼성전자(005930)")
        assertThat(msg).contains("75,000원")
        assertThat(msg).doesNotContain("즉시 조치")
    }

    @Test
    fun `(1-2) ORDER_FILLED — executedAt 기준 시각과 backfill 반영 경로 표시`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_FILLED,
                reason = "체결: [MOCK] 삼성전자(005930) 매도 1주 @ 353,000원",
                meta =
                    mapOf(
                        "executedAt" to "1780373478",
                        "fillSource" to "REST_BACKFILL",
                    ),
            )

        assertThat(msg).contains("[주문 체결]* 2026-06-02 13:11:18 KST")
        assertThat(msg).contains("체결시각: 2026-06-02 13:11:18 KST")
        assertThat(msg).contains("반영: KIS REST backfill")
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
                meta =
                    mapOf(
                        "alertTitle" to "D. 거래 빈도 한도 도달 - 일별 최대 거래",
                        "alertMessage" to "종목 005930 주문이 오늘 이 전략에서 허용된 주문 횟수를 모두 사용해 차단됐습니다.",
                        "alertFormula" to "오늘 허용 주문 수 50회 >= 일별 최대 거래 50회/일",
                        "alertSetting" to "D. 거래 빈도 한도 - 일별 최대 거래",
                        "code" to "005930",
                        "instrumentName" to "삼성전자",
                    ),
            )
        assertThat(msg).contains("리스크 위반")
        assertThat(msg).contains("종목: 삼성전자(005930)")
        assertThat(msg).contains("내용: D. 거래 빈도 한도 도달 - 일별 최대 거래")
        assertThat(msg).contains("설명: 종목 삼성전자(005930)")
        assertThat(msg).contains("수식: 오늘 허용 주문 수 50회 >= 일별 최대 거래 50회/일")
        assertThat(msg).contains("설정: D. 거래 빈도 한도 - 일별 최대 거래")
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
