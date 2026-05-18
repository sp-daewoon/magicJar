package kr.spdaewoon.magicjar.consumer.notification

import kr.spdaewoon.magicjar.domain.notification.NotificationEventType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Kafka 이벤트 → Slack 메시지 포맷터 (ADR 0044 §2-3 + spec §2-4 LOCK).
 *
 * 한국어 5필드 포맷:
 * 1. 시각 (KST)
 * 2. 이벤트 유형 (이모지 + 한국어 설명)
 * 3. 사용자 (마스킹 — userId 앞 4자만)
 * 4. 사유
 * 5. 즉시 액션 (P0만)
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2)
 */
object NotificationFormatter {
    private val KST_ZONE = ZoneId.of("Asia/Seoul")
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun format(
        userId: String,
        eventType: NotificationEventType,
        reason: String,
        meta: Map<String, String?>,
    ): String {
        val kst = Instant.now().atZone(KST_ZONE).format(DATE_FMT)
        val maskedUser = maskUserId(userId)
        val (emoji, typeLabel) = eventLabel(eventType)
        val action = immediateAction(eventType)

        return buildString {
            appendLine("$emoji *[$typeLabel]* $kst KST")
            appendLine("사용자: $maskedUser")
            appendLine("내용: $reason")
            if (action.isNotBlank()) {
                appendLine("즉시 조치: $action")
            }
        }.trimEnd()
    }

    private fun maskUserId(userId: String): String = if (userId.length > 4) userId.take(4) + "****" else "****"

    private fun eventLabel(eventType: NotificationEventType): Pair<String, String> =
        when (eventType) {
            NotificationEventType.ORDER_FILLED -> ":white_check_mark:" to "주문 체결"
            NotificationEventType.ORDER_REJECTED -> ":x:" to "주문 거절"
            NotificationEventType.ORDER_CANCELLED -> ":grey_exclamation:" to "주문 취소"
            NotificationEventType.RISK_VIOLATION -> ":warning:" to "리스크 위반"
            NotificationEventType.RECONCILE_DRIFT -> ":rotating_light:" to "포지션 불일치"
            NotificationEventType.KILLSWITCH_ENGAGED -> ":red_circle:" to "KillSwitch 자동 발동"
            NotificationEventType.RECONCILE_RUN -> ":mag:" to "잔고 점검"
            NotificationEventType.DLT_RECEIVED -> ":boom:" to "Kafka DLT 수신 (P0 자본 path risk)"
        }

    private fun immediateAction(eventType: NotificationEventType): String =
        when (eventType) {
            NotificationEventType.RECONCILE_DRIFT -> "KIS HTS 직접 확인 후 수동 KillSwitch OFF 토글 필요"
            NotificationEventType.KILLSWITCH_ENGAGED -> "자동매매 일시 중단됨. KIS 잔고 확인 후 수동 재활성화 필요"
            NotificationEventType.RISK_VIOLATION -> "리스크 한도 점검 후 필요 시 전략 비활성화"
            NotificationEventType.DLT_RECEIVED ->
                "DLT 토픽 record 검토 후 수동 replay 또는 운영 결함 fix. KIS 주문 영향 시 HTS 직접 확인."
            else -> ""
        }
}
