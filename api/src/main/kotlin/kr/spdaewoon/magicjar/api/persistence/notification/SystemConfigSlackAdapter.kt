package kr.spdaewoon.magicjar.api.persistence.notification

import kr.spdaewoon.magicjar.api.persistence.SystemConfigJpaRepository
import kr.spdaewoon.magicjar.api.secret.AesGcmCipher
import kr.spdaewoon.magicjar.domain.notification.port.SystemConfigSlackPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * system_config.notification_slack_webhook BYTEA 복호화 어댑터 (ADR 0046 AES-256-GCM 정합).
 *
 * Flyway V27 ALTER 정합 — notification_slack_webhook BYTEA 컬럼.
 * SystemConfigJpaEntity.notificationSlackWebhook (BYTEA) → AesGcmCipher.decrypt() → String URL.
 *
 * NULL 또는 복호화 실패 시 null 반환 → SlackWebhookSender graceful skip.
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class SystemConfigSlackAdapter(
    private val systemConfigJpaRepository: SystemConfigJpaRepository,
    private val aesGcmCipher: AesGcmCipher,
) : SystemConfigSlackPort {
    private val log = LoggerFactory.getLogger(SystemConfigSlackAdapter::class.java)

    override fun fetchSlackWebhook(): String? {
        val config =
            systemConfigJpaRepository.findSingleton() ?: run {
                log.warn("system_config 레코드 없음 — Slack webhook null 반환")
                return null
            }

        val encrypted = config.notificationSlackWebhook
        if (encrypted == null || encrypted.isEmpty()) {
            log.debug("system_config.notification_slack_webhook 미설정 (NULL)")
            return null
        }

        return try {
            val decrypted = aesGcmCipher.decrypt(encrypted)
            val url = String(decrypted, Charsets.UTF_8).trim()
            if (url.isBlank()) {
                log.warn("system_config.notification_slack_webhook 복호화 결과 empty")
                null
            } else {
                url
            }
        } catch (e: Exception) {
            log.error("Slack webhook 복호화 실패 — null 반환: err={}", e.message)
            null
        }
    }
}
