package com.mj.consumer.infrastructure.bootstrap

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * 부팅 시 MAGICJAR_SECRET_MASTER_KEY 검증.
 * - mock profile / 빈 profile: skip (외부 호출 0)
 * - dev / prd profile: 미설정 또는 길이 불일치 시 fail-fast
 *
 * spec §8.1. 사용자가 .env에 master key를 발급해두지 않으면 부팅 자체를 막는다.
 */
@Component
class MasterKeyValidator(
    @Value("\${magicjar.secret.master-key:}") private val masterKey: String,
    private val env: Environment,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun verify() {
        val active = env.activeProfiles.toSet()
        val isMock = active.contains("mock") || active.isEmpty()
        if (isMock) {
            log.info("[MASTER_KEY] skip verification (profile={})", active)
            return
        }
        require(masterKey.isNotBlank()) {
            "MAGICJAR_SECRET_MASTER_KEY is required for profile=$active. " +
                "Generate with: openssl rand -base64 32 → add to .env"
        }
        try {
            val raw = Base64.getDecoder().decode(masterKey)
            require(raw.size == 32) {
                "master key must be 32 bytes after base64 decode (got ${raw.size})"
            }
        } catch (e: IllegalArgumentException) {
            error("MAGICJAR_SECRET_MASTER_KEY invalid base64 / size: ${e.message}")
        }
        log.info("[MASTER_KEY] verified (length=32 bytes, profile={})", active)
    }
}
