package com.mj.consumer.infrastructure.bootstrap

import com.mj.domain.secret.SecretCipher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * 시드 사용자 1명을 INSERT한다 (KIS 키는 OPTIONAL — frontend SettingsModal 입력 정합).
 *
 * 정책 (memory `project_kis_key_management.md`):
 *   "개발 중에는 ⚙️ SettingsModal 입력 + DB AES 암호화. 본격 운영 시 secret manager 재논의."
 *
 * 동작:
 *   1. users row 없으면 INSERT (default user). 항상 실행.
 *   2. magicjar.seed.kis.app-key 설정되어 있으면 user_trading_config도 시드 (.env → DB 이전).
 *   3. 키 미설정이면 trading_config 비어 시작 — 사용자가 SettingsModal에서 입력 후 INSERT/UPDATE.
 *
 * 인증 도입(ISSUE-006 후속) 시 SecurityContext 통합 후 사용자 등록 endpoint로 대체.
 */
@Component
@Profile("dev | prd")
class InitialUserSeeder(
    private val jdbc: JdbcTemplate,
    private val cipher: SecretCipher,
    @Value("\${magicjar.seed.kis.app-key:}") private val appKey: String,
    @Value("\${magicjar.seed.kis.app-secret:}") private val appSecret: String,
    @Value("\${magicjar.seed.kis.account-cano:}") private val cano: String,
    @Value("\${magicjar.seed.kis.account-prdt:01}") private val prdt: String,
    @Value("\${magicjar.seed.kis.hts-id:PLACEHOLDER}") private val htsId: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun seedOnStartup() {
        seedIfNotExists()
    }

    fun seedIfNotExists() {
        val exists =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE user_id = ?",
                Int::class.java,
                DEFAULT_USER_ID,
            ) ?: 0
        if (exists > 0) {
            log.info("[SEED] default user already exists — skip")
            return
        }

        val now = OffsetDateTime.now()
        // 1) users row는 항상 INSERT — frontend가 user_id 참조
        jdbc.update(
            """INSERT INTO users(user_id, username, display_name, created_at, updated_at)
               VALUES(?,?,?,?,?)""",
            DEFAULT_USER_ID,
            "default",
            "Default User",
            now,
            now,
        )
        log.info("[SEED] default user inserted (user_id={})", DEFAULT_USER_ID)

        // 2) KIS 키가 .env에 시드되어 있을 때만 trading_config 시드 — OPTIONAL
        if (appKey.isBlank() || appSecret.isBlank() || cano.isBlank()) {
            log.info(
                "[SEED] magicjar.seed.kis.* 미설정 — trading_config 시드 skip. " +
                    "사용자가 SettingsModal(⚙️)에서 입력 후 저장 권장.",
            )
            return
        }

        jdbc.update(
            """INSERT INTO user_trading_config(user_id, trading_mode, account_no,
                                                account_product_code,
                                                kis_app_key_encrypted, kis_app_secret_encrypted,
                                                hts_id, created_at, updated_at)
               VALUES(?,?,?,?,?,?,?,?,?)""",
            DEFAULT_USER_ID,
            "MOCK_KIS",
            cano,
            prdt,
            cipher.encrypt(appKey),
            cipher.encrypt(appSecret),
            htsId,
            now,
            now,
        )
        log.info("[SEED] default user trading_config seeded: account={}, mode=MOCK_KIS, hts_id={}", cano, htsId)
        log.warn(
            "[SEED] .env에서 KIS_MOCK_APP_KEY, KIS_MOCK_APP_SECRET, " +
                "KIS_ACCOUNT_CANO, KIS_ACCOUNT_PRDT 제거 권고 — DB로 이전 완료",
        )
    }

    companion object {
        const val DEFAULT_USER_ID: String = "74683223"
    }
}
