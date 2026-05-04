package com.mj.api.application.auth

import com.mj.domain.secret.SecretCipher
import com.mj.domain.user.TradingMode
import com.mj.domain.user.UserTradingConfig
import com.mj.domain.user.port.UserTradingConfigRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class AuthServiceTest {
    private val configRepo: UserTradingConfigRepository = mockk()
    private val cipher: SecretCipher = mockk()
    private val service = AuthService(configRepo, cipher)

    private val userId = "74683223"

    private fun sampleConfig(
        tradingMode: TradingMode = TradingMode.MOCK_KIS,
        appKeyEnc: String = "enc-key",
        appSecretEnc: String = "enc-secret",
    ) = UserTradingConfig(
        userId = userId,
        tradingMode = tradingMode,
        accountNo = "5000123456",
        accountProductCode = "01",
        kisAppKeyEncrypted = appKeyEnc,
        kisAppSecretEncrypted = appSecretEnc,
        htsId = "thdeodns",
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
    )

    // ─── status ────────────────────────────────────────────────────────────────

    @Test
    fun `status returns authenticated false when config not found`() {
        every { configRepo.findByUserId(userId) } returns null

        val result = service.status(userId)

        assertFalse(result.authenticated)
        assertNull(result.mode)
        assertNull(result.accountNo)
        assertFalse(result.hasAppKey)
        assertFalse(result.hasAppSecret)
    }

    @Test
    fun `status returns authenticated true when config exists with keys`() {
        every { configRepo.findByUserId(userId) } returns sampleConfig()

        val result = service.status(userId)

        assertTrue(result.authenticated)
        assertEquals(KisMode.Vps, result.mode)
        assertEquals("5000123456", result.accountNo)
        assertTrue(result.hasAppKey)
        assertTrue(result.hasAppSecret)
    }

    @Test
    fun `status maps REAL_KIS to prod mode`() {
        every { configRepo.findByUserId(userId) } returns sampleConfig(tradingMode = TradingMode.REAL_KIS)

        val result = service.status(userId)

        assertEquals(KisMode.Prod, result.mode)
    }

    // ─── login ─────────────────────────────────────────────────────────────────

    @Test
    fun `login saves new config when no existing row`() {
        every { configRepo.findByUserId(userId) } returns null
        every { cipher.encrypt("plain-key") } returns "enc-key"
        every { cipher.encrypt("plain-secret") } returns "enc-secret"
        val savedSlot = slot<UserTradingConfig>()
        every { configRepo.save(capture(savedSlot)) } answers { firstArg() }

        val result =
            service.login(
                userId,
                LoginRequest(
                    mode = KisMode.Vps,
                    appKey = "plain-key",
                    appSecret = "plain-secret",
                    accountNo = "5000123456",
                    accountProductCode = "01",
                    htsId = "thdeodns",
                ),
            )

        assertTrue(result.authenticated)
        assertEquals(KisMode.Vps, result.mode)
        with(savedSlot.captured) {
            assertEquals(TradingMode.MOCK_KIS, tradingMode)
            assertEquals("enc-key", kisAppKeyEncrypted)
            assertEquals("enc-secret", kisAppSecretEncrypted)
            assertEquals("5000123456", accountNo)
            assertEquals("thdeodns", htsId)
        }
    }

    @Test
    fun `login updates existing config`() {
        val existing = sampleConfig(tradingMode = TradingMode.MOCK_KIS)
        every { configRepo.findByUserId(userId) } returns existing
        every { cipher.encrypt("new-key") } returns "new-enc-key"
        every { cipher.encrypt("new-secret") } returns "new-enc-secret"
        val savedSlot = slot<UserTradingConfig>()
        every { configRepo.save(capture(savedSlot)) } answers { firstArg() }

        service.login(
            userId,
            LoginRequest(
                mode = KisMode.Prod,
                appKey = "new-key",
                appSecret = "new-secret",
                accountNo = "9990001111",
                accountProductCode = "01",
            ),
        )

        with(savedSlot.captured) {
            assertEquals(TradingMode.REAL_KIS, tradingMode)
            assertEquals("new-enc-key", kisAppKeyEncrypted)
            assertEquals("9990001111", accountNo)
        }
    }

    @Test
    fun `login does not expose secret in result`() {
        every { configRepo.findByUserId(userId) } returns null
        every { cipher.encrypt(any()) } returns "enc-value"
        every { configRepo.save(any()) } answers { firstArg() }

        val result =
            service.login(
                userId,
                LoginRequest(
                    mode = KisMode.Vps,
                    appKey = "plain-key",
                    appSecret = "plain-secret",
                    accountNo = "5000123456",
                    accountProductCode = "01",
                ),
            )

        // LoginResult에 appSecret 필드가 없음 (컴파일 수준 보장)
        assertEquals(KisMode.Vps, result.mode)
        assertTrue(result.authenticated)
    }

    // ─── switchMode ────────────────────────────────────────────────────────────

    @Test
    fun `switchMode returns failure when no config exists`() {
        every { configRepo.findByUserId(userId) } returns null

        val result = service.switchMode(userId, ModeChangeRequest(KisMode.Prod))

        assertFalse(result.success)
        assertTrue(result.reason?.contains("login") == true)
    }

    @Test
    fun `switchMode returns failure when credentials are blank`() {
        every { configRepo.findByUserId(userId) } returns
            sampleConfig(appKeyEnc = "", appSecretEnc = "")

        val result = service.switchMode(userId, ModeChangeRequest(KisMode.Prod))

        assertFalse(result.success)
        assertTrue(result.reason?.contains("appKey") == true)
    }

    @Test
    fun `switchMode updates tradingMode to REAL_KIS for prod`() {
        every { configRepo.findByUserId(userId) } returns sampleConfig(tradingMode = TradingMode.MOCK_KIS)
        val savedSlot = slot<UserTradingConfig>()
        every { configRepo.save(capture(savedSlot)) } answers { firstArg() }

        val result = service.switchMode(userId, ModeChangeRequest(KisMode.Prod))

        assertTrue(result.success)
        assertEquals(KisMode.Prod, result.mode)
        assertEquals(TradingMode.REAL_KIS, savedSlot.captured.tradingMode)
    }

    @Test
    fun `switchMode updates tradingMode to MOCK_KIS for vps`() {
        every { configRepo.findByUserId(userId) } returns sampleConfig(tradingMode = TradingMode.REAL_KIS)
        val savedSlot = slot<UserTradingConfig>()
        every { configRepo.save(capture(savedSlot)) } answers { firstArg() }

        val result = service.switchMode(userId, ModeChangeRequest(KisMode.Vps))

        assertTrue(result.success)
        assertEquals(KisMode.Vps, result.mode)
        assertEquals(TradingMode.MOCK_KIS, savedSlot.captured.tradingMode)
        verify(exactly = 1) { configRepo.save(any()) }
    }
}
