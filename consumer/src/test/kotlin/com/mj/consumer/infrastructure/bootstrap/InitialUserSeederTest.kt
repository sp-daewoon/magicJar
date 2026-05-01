package com.mj.consumer.infrastructure.bootstrap

import com.mj.domain.secret.SecretCipher
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime

/**
 * InitialUserSeeder 단위테스트 — mockk 기반.
 *
 * Testcontainers postgres 통합테스트는 sandbox bash 환경에서 Docker daemon 직접 접근
 * 불가라 host 환경에서 별도 검증. 본 단위는 jdbc 호출 시퀀스·암호화 호출 패턴·idempotency만
 * 검증한다. 실 schema 적용 검증은 docker compose 재기동 시 부팅 로그에서.
 */
class InitialUserSeederTest {
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val cipher = mockk<SecretCipher>()

    private fun seeder(
        appKey: String = "PLAIN_KEY",
        appSecret: String = "PLAIN_SECRET",
        cano: String = "50185338",
        prdt: String = "01",
        htsId: String = "thdeodns",
    ) = InitialUserSeeder(jdbc, cipher, appKey, appSecret, cano, prdt, htsId)

    @Test
    fun `seeder inserts user and config when none exists`() {
        every {
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE user_id = ?",
                Int::class.java,
                InitialUserSeeder.DEFAULT_USER_ID,
            )
        } returns 0
        every { cipher.encrypt("PLAIN_KEY") } returns "ENC_KEY"
        every { cipher.encrypt("PLAIN_SECRET") } returns "ENC_SEC"

        seeder().seedIfNotExists()

        verify(exactly = 1) {
            jdbc.update(
                match<String> { it.contains("INSERT INTO users") },
                InitialUserSeeder.DEFAULT_USER_ID,
                "default",
                "Default User",
                any<OffsetDateTime>(),
                any<OffsetDateTime>(),
            )
        }
        verify(exactly = 1) {
            jdbc.update(
                match<String> { it.contains("INSERT INTO user_trading_config") },
                InitialUserSeeder.DEFAULT_USER_ID,
                "MOCK_KIS",
                "50185338",
                "01",
                "ENC_KEY",
                "ENC_SEC",
                "thdeodns",
                any<OffsetDateTime>(),
                any<OffsetDateTime>(),
            )
        }
    }

    @Test
    fun `seeder is idempotent when user already exists`() {
        every {
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE user_id = ?",
                Int::class.java,
                InitialUserSeeder.DEFAULT_USER_ID,
            )
        } returns 1

        seeder().seedIfNotExists()

        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
        verify(exactly = 0) { cipher.encrypt(any()) }
    }

    @Test
    fun `seeder fails when appKey is blank`() {
        every {
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE user_id = ?",
                Int::class.java,
                InitialUserSeeder.DEFAULT_USER_ID,
            )
        } returns 0

        val ex =
            assertThrows<IllegalArgumentException> {
                seeder(appKey = "").seedIfNotExists()
            }
        ex.message?.contains("app-key") shouldBe true
    }
}
