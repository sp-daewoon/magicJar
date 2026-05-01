package com.mj.consumer.infrastructure.secret

import com.mj.domain.secret.SecretCipher
import com.mj.domain.user.TradingMode
import com.mj.domain.user.UserTradingConfig
import com.mj.domain.user.port.UserTradingConfigRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID

class DbKisCredentialProviderTest {
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val repo = mockk<UserTradingConfigRepository>()
    private val cipher = mockk<SecretCipher>()
    private val provider = DbKisCredentialProvider(repo, cipher)

    @Test
    fun `forUser decrypts app key and secret`() {
        every { repo.findByUserId(userId) } returns
            UserTradingConfig(
                userId = userId,
                tradingMode = TradingMode.MOCK_KIS,
                accountNo = "50185338",
                accountProductCode = "01",
                kisAppKeyEncrypted = "ENC_KEY",
                kisAppSecretEncrypted = "ENC_SEC",
                htsId = "thdeodns",
                createdAt = OffsetDateTime.now(),
                updatedAt = OffsetDateTime.now(),
            )
        every { cipher.decrypt("ENC_KEY") } returns "PLAIN_KEY"
        every { cipher.decrypt("ENC_SEC") } returns "PLAIN_SEC"

        val cred = provider.forUser(userId)

        cred.appKey shouldBe "PLAIN_KEY"
        cred.appSecret shouldBe "PLAIN_SEC"
        cred.tradingMode shouldBe TradingMode.MOCK_KIS
        cred.accountNo shouldBe "50185338"
        cred.htsId shouldBe "thdeodns"
        // toString masking — 평문 키 노출되지 않아야
        cred.toString() shouldNotContain "PLAIN_KEY"
        cred.toString() shouldNotContain "PLAIN_SEC"
    }

    @Test
    fun `forUser throws when config not found`() {
        every { repo.findByUserId(userId) } returns null
        assertThrows<IllegalStateException> { provider.forUser(userId) }
    }
}
