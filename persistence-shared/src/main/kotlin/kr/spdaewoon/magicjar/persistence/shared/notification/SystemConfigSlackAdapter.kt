package kr.spdaewoon.magicjar.persistence.shared.notification

import kr.spdaewoon.magicjar.domain.notification.port.SystemConfigSlackPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.persistence.shared.system.SystemConfigJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * system_config.notification_slack_webhook BYTEA 복호화 어댑터.
 *
 * persistence-shared에 위치해 api와 consumer가 같은 Slack webhook source를 읽는다.
 * NULL 또는 복호화 실패 시 null 반환 → SlackWebhookSender graceful skip.
 */
@Component
class SystemConfigSlackAdapter(
    private val systemConfigJpaRepository: SystemConfigJpaRepository,
    private val secretCipher: SecretCipher,
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
            val decrypted = secretCipher.decrypt(encrypted)
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
