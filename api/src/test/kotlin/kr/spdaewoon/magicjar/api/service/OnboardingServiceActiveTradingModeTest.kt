package kr.spdaewoon.magicjar.api.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kr.spdaewoon.magicjar.api.persistence.AuditTrailJpaEntity
import kr.spdaewoon.magicjar.api.persistence.AuditTrailJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.api.persistence.killswitch.KillSwitchWriteJpaRepository
import kr.spdaewoon.magicjar.api.secret.AesGcmCipher
import kr.spdaewoon.magicjar.persistence.shared.system.SystemConfigJpaEntity
import kr.spdaewoon.magicjar.persistence.shared.system.SystemConfigJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional

/**
 * OnboardingService.updateActiveTradingMode 단위 테스트 (감사 2026-09-19 §5 A·C).
 *
 * 자본 안전 검증:
 *  - MOCK 전환은 항상 허용(env 상한/동의 무관)
 *  - REAL 전환은 env MAGICJAR_MODE=REAL + ALLOW_REAL=true + 사용자 동의 모두 충족 시에만
 *  - 하나라도 미충족 → 412
 *  - 잘못된 mode 값 → 400
 */
class OnboardingServiceActiveTradingModeTest {
    private val userAccountRepo = mockk<UserAccountJpaRepository>(relaxed = true)
    private val credentialRepo = mockk<UserKisCredentialJpaRepository>(relaxed = true)
    private val killSwitchRepo = mockk<KillSwitchWriteJpaRepository>(relaxed = true)
    private val systemConfigRepo = mockk<SystemConfigJpaRepository>(relaxed = true)
    private val auditRepo = mockk<AuditTrailJpaRepository>(relaxed = true)

    private fun service(
        mode: String,
        allowReal: String,
    ): OnboardingService =
        OnboardingService(
            userAccountJpaRepository = userAccountRepo,
            userKisCredentialJpaRepository = credentialRepo,
            killSwitchWriteJpaRepository = killSwitchRepo,
            systemConfigJpaRepository = systemConfigRepo,
            auditTrailJpaRepository = auditRepo,
            kisAuthValidator = mockk(relaxed = true),
            aesGcmCipher = mockk<AesGcmCipher>(relaxed = true),
            eventPublisher = mockk(relaxed = true),
            webClient = mockk(relaxed = true),
            magicjarMode = mode,
            magicjarAllowReal = allowReal,
        )

    private fun stubConfig() {
        every { systemConfigRepo.findSingleton() } returns SystemConfigJpaEntity(id = 1)
        every { systemConfigRepo.save(any()) } answers { firstArg() }
        every { auditRepo.save(any<AuditTrailJpaEntity>()) } answers { firstArg() }
    }

    private fun stubConsent(consentAt: Instant?) {
        every { userAccountRepo.findFirstUserIds(any<PageRequest>()) } returns listOf("74683223")
        every { userAccountRepo.findById("74683223") } returns
            Optional.of(
                UserAccountJpaEntity(
                    userId = "74683223",
                    displayName = "t",
                    maskedAccount = "7468****",
                    createdAt = Instant.parse("2026-05-01T00:00:00Z"),
                    lastLoginAt = null,
                    realTradingConsentAt = consentAt,
                ),
            )
    }

    @Test
    fun `MOCK 전환은 env 잠겨 있어도 항상 허용`() {
        stubConfig()
        val result = service(mode = "MOCK", allowReal = "false").updateActiveTradingMode("MOCK")
        assertThat(result.activeTradingMode).isEqualTo("MOCK")
    }

    @Test
    fun `REAL 전환 — env 상한 3조건 + 동의 충족 시 허용`() {
        stubConfig()
        stubConsent(Instant.parse("2026-05-10T00:00:00Z"))
        val result = service(mode = "REAL", allowReal = "true").updateActiveTradingMode("REAL")
        assertThat(result.activeTradingMode).isEqualTo("REAL")
    }

    @Test
    fun `REAL 전환 — env MAGICJAR_MODE!=REAL 이면 412`() {
        assertThatThrownBy { service(mode = "MOCK", allowReal = "true").updateActiveTradingMode("REAL") }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("REAL_MODE_LOCKED")
    }

    @Test
    fun `REAL 전환 — ALLOW_REAL=false 이면 412`() {
        assertThatThrownBy { service(mode = "REAL", allowReal = "false").updateActiveTradingMode("REAL") }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("ALLOW_REAL_LOCKED")
    }

    @Test
    fun `REAL 전환 — 사용자 동의 없으면 412`() {
        stubConsent(consentAt = null)
        assertThatThrownBy { service(mode = "REAL", allowReal = "true").updateActiveTradingMode("REAL") }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("CONSENT_REQUIRED")
    }

    @Test
    fun `잘못된 mode 값은 400`() {
        assertThatThrownBy { service(mode = "REAL", allowReal = "true").updateActiveTradingMode("FOO") }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("INVALID_MODE")
    }

    @Test
    fun `REAL 전환 성공 시 audit ACTIVE_TRADING_MODE_CHANGED 남긴다`() {
        stubConfig()
        stubConsent(Instant.parse("2026-05-10T00:00:00Z"))
        val auditSlot = slot<AuditTrailJpaEntity>()
        every { auditRepo.save(capture(auditSlot)) } answers { firstArg() }

        service(mode = "REAL", allowReal = "true").updateActiveTradingMode("REAL")

        assertThat(auditSlot.captured.action).isEqualTo("ACTIVE_TRADING_MODE_CHANGED")
        assertThat(auditSlot.captured.detailJson).contains("REAL")
    }
}
