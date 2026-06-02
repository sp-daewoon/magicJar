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
        val displayInstant = eventInstant(eventType, meta) ?: Instant.now()
        val kst = formatKst(displayInstant)
        val maskedUser = maskUserId(userId)
        val (emoji, typeLabel) = eventLabel(eventType)
        val action = immediateAction(eventType, meta)

        return buildString {
            appendLine("$emoji *[$typeLabel]* $kst KST")
            appendLine("사용자: $maskedUser")
            strategyLine(eventType, meta)?.let { appendLine(it) }
            appendContent(eventType, reason, meta)
            if (action.isNotBlank()) {
                appendLine("즉시 조치: $action")
            }
        }.trimEnd()
    }

    private fun maskUserId(userId: String): String =
        if (userId == "broadcast") {
            "시스템"
        } else if (userId.length > 4) {
            userId.take(4) + "****"
        } else {
            "****"
        }

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
            NotificationEventType.SYSTEM_EVENT -> ":rotating_light:" to "시스템 이벤트"
        }

    private fun immediateAction(
        eventType: NotificationEventType,
        meta: Map<String, String?>,
    ): String =
        when (eventType) {
            NotificationEventType.RECONCILE_DRIFT -> "KIS HTS 직접 확인 후 수동 KillSwitch OFF 토글 필요"
            NotificationEventType.KILLSWITCH_ENGAGED -> "자동매매 일시 중단됨. KIS 잔고 확인 후 수동 재활성화 필요"
            NotificationEventType.RISK_VIOLATION ->
                meta["alertAction"]?.takeIf { it.isNotBlank() } ?: "리스크 한도 점검 후 필요 시 전략 비활성화"
            NotificationEventType.DLT_RECEIVED ->
                "DLT 토픽 record 검토 후 수동 replay 또는 운영 결함 fix. KIS 주문 영향 시 HTS 직접 확인."
            NotificationEventType.SYSTEM_EVENT -> "시스템 이벤트 상세와 Dozzle 로그를 함께 확인"
            else -> ""
        }

    private fun StringBuilder.appendContent(
        eventType: NotificationEventType,
        reason: String,
        meta: Map<String, String?>,
    ) {
        if (eventType == NotificationEventType.ORDER_FILLED) {
            appendLine("내용: $reason")
            eventInstant(eventType, meta)?.let { appendLine("체결시각: ${formatKst(it)} KST") }
            if (meta["fillSource"] == "REST_BACKFILL") {
                appendLine("반영: KIS REST backfill")
            }
            return
        }

        if (eventType != NotificationEventType.RISK_VIOLATION) {
            appendLine("내용: $reason")
            return
        }

        val title = meta["alertTitle"]?.takeIf { it.isNotBlank() }
        val message = meta["alertMessage"]?.takeIf { it.isNotBlank() }
        val formula = meta["alertFormula"]?.takeIf { it.isNotBlank() }
        val setting = meta["alertSetting"]?.takeIf { it.isNotBlank() }
        val instrumentLabel = instrumentLabel(meta)

        if (title == null && message == null && formula == null && setting == null) {
            appendLine("내용: $reason")
            return
        }

        instrumentLabel?.let { appendLine("종목: $it") }
        appendLine("내용: ${title ?: "리스크 차단"}")
        appendLine("설명: ${withInstrumentLabel(message ?: reason, meta)}")
        withInstrumentLabel(formula, meta)?.lineSequence()?.forEachIndexed { index, line ->
            appendLine(if (index == 0) "수식: $line" else "      $line")
        }
        setting?.let { appendLine("설정: $it") }
    }

    private fun instrumentLabel(meta: Map<String, String?>): String? {
        val code = meta["code"]?.takeIf { it.isNotBlank() }
        val name = meta["instrumentName"]?.takeIf { it.isNotBlank() && it != code }
        return when {
            name != null && code != null -> "$name($code)"
            code != null -> code
            name != null -> name
            else -> null
        }
    }

    private fun withInstrumentLabel(
        value: String?,
        meta: Map<String, String?>,
    ): String? {
        val code = meta["code"]?.takeIf { it.isNotBlank() } ?: return value
        val label = instrumentLabel(meta) ?: return value
        return value?.replace("종목 $code", "종목 $label")
    }

    private fun strategyLine(
        eventType: NotificationEventType,
        meta: Map<String, String?>,
    ): String? {
        val strategyId = meta["strategyId"]?.toLongOrNull()
        val source = meta["source"]?.uppercase()
        val manual = source == "MANUAL" || strategyId == 0L
        return when (eventType) {
            NotificationEventType.ORDER_FILLED,
            NotificationEventType.ORDER_REJECTED,
            NotificationEventType.ORDER_CANCELLED,
            -> {
                if (manual) {
                    "주문출처: 수동 주문"
                } else {
                    strategyDisplay(strategyId, meta)?.let { "주문출처: 자동 전략 $it" }
                }
            }
            NotificationEventType.RISK_VIOLATION -> strategyDisplay(strategyId, meta)?.let { "전략: $it" }
            else -> null
        }
    }

    private fun strategyDisplay(
        strategyId: Long?,
        meta: Map<String, String?>,
    ): String? {
        if (strategyId == null || strategyId <= 0L) return null
        val name = meta["strategyName"]?.takeIf { it.isNotBlank() }
        return if (name != null) "$name (#$strategyId)" else "#$strategyId"
    }

    private fun eventInstant(
        eventType: NotificationEventType,
        meta: Map<String, String?>,
    ): Instant? =
        when (eventType) {
            NotificationEventType.ORDER_FILLED -> parseInstant(meta["executedAt"])
            NotificationEventType.RISK_VIOLATION -> parseInstant(meta["violatedAt"] ?: meta["blockedAt"] ?: meta["ts"])
            else -> null
        }

    private fun parseInstant(value: String?): Instant? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        raw.toLongOrNull()?.let { epoch ->
            return if (epoch > 100_000_000_000L) {
                Instant.ofEpochMilli(epoch)
            } else {
                Instant.ofEpochSecond(epoch)
            }
        }
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    private fun formatKst(instant: Instant): String = instant.atZone(KST_ZONE).format(DATE_FMT)
}
