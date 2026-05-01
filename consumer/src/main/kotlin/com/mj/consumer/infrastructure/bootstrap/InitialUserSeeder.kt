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
import java.util.UUID

/**
 * 시드 사용자 1명 + user_trading_config 1행을 .env 값에서 읽어 INSERT.
 * spec §4.3. mock profile에선 skip(외부 호출 0).
 *
 * 1번 트랙 1차 — single user 가정. 인증 도입(ISSUE-006 후속) 시 SecurityContext
 * 통합 후 사용자 등록 endpoint로 대체.
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
        require(appKey.isNotBlank()) { "magicjar.seed.kis.app-key 미설정" }
        require(appSecret.isNotBlank()) { "magicjar.seed.kis.app-secret 미설정" }
        require(cano.isNotBlank()) { "magicjar.seed.kis.account-cano 미설정" }

        val now = OffsetDateTime.now()
        jdbc.update(
            """INSERT INTO users(user_id, username, display_name, created_at, updated_at)
               VALUES(?,?,?,?,?)""",
            DEFAULT_USER_ID,
            "default",
            "Default User",
            now,
            now,
        )
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
        log.info("[SEED] default user seeded: account=$cano, mode=MOCK_KIS, hts_id=$htsId")
        log.warn(
            "[SEED] .env에서 KIS_MOCK_APP_KEY, KIS_MOCK_APP_SECRET, " +
                "KIS_ACCOUNT_CANO, KIS_ACCOUNT_PRDT 제거 권고 — DB로 이전 완료",
        )
    }

    companion object {
        val DEFAULT_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
