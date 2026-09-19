package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.ActiveTradingModePort
import kr.spdaewoon.magicjar.domain.order.execution.port.RealTradingConsentPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant

/**
 * TradingModeGateImpl 단위 테스트 (ADR 0068 §14-3·§14-7 LOCK + 감사 2026-09-19 §5 A).
 *
 * 케이스 매트릭스:
 *  (a) MOCK fallback (env=MOCK) — DB 활성모드 무관
 *  (b) REAL 정상 (env 상한 3조건 + DB 활성모드=REAL)
 *  (c) ALLOW_REAL=false denied
 *  (d) CONSENT_NOT_GIVEN denied
 *  (e) paper + REAL 3조건 충족 → MOCK 강제
 *  (f) paper + env=MOCK → MOCK
 *  (g) paper 시 consentPort 미호출
 *  (h) env 상한 REAL 열림 + DB 활성모드=MOCK → MOCK 존중 (재기동 없는 전환 · 감사 §5 A)
 *  (i) env 상한 MOCK → DB 활성모드=REAL 이어도 MOCK (상한이 절대 · 자본 사고 방지)
 *  (j) ActiveTradingModePort 미합류 → MOCK fail-closed
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class TradingModeGateImplTest {
    private val consentPort = mockk<RealTradingConsentPort>()
    private val consentProvider =
        mockk<ObjectProvider<RealTradingConsentPort>>().also {
            every { it.getIfAvailable() } returns consentPort
        }

    /** DB 활성모드 포트 provider — 기본 REAL(기존 REAL 케이스가 상한만으로 REAL 되게). */
    private fun activeModeProvider(mode: TradingMode = TradingMode.REAL): ObjectProvider<ActiveTradingModePort> {
        val port = mockk<ActiveTradingModePort>()
        every { port.activeMode() } returns mode
        return mockk<ObjectProvider<ActiveTradingModePort>>().also {
            every { it.getIfAvailable() } returns port
        }
    }

    private fun gate(
        consent: ObjectProvider<RealTradingConsentPort> = consentProvider,
        activeMode: ObjectProvider<ActiveTradingModePort> = activeModeProvider(),
        magicjarMode: String,
        magicjarAllowReal: String,
    ) = TradingModeGateImpl(consent, activeMode, magicjarMode, magicjarAllowReal)

    @Test
    fun `(a) env mode가 MOCK이면 MOCK fallback (안전 default)`() {
        val gate = gate(magicjarMode = "MOCK", magicjarAllowReal = "false")

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Right::class.java)
        assertThat((result as OrderResult.Right).value).isEqualTo(TradingMode.MOCK)
    }

    @Test
    fun `(b) REAL 정상 — env 상한 3조건 + DB 활성모드 REAL → REAL 반환`() {
        every { consentPort.findConsentAt("12345678") } returns Instant.parse("2026-05-10T00:00:00Z")
        val gate = gate(magicjarMode = "REAL", magicjarAllowReal = "true")

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Right::class.java)
        assertThat((result as OrderResult.Right).value).isEqualTo(TradingMode.REAL)
    }

    @Test
    fun `env mode와 allow-real은 대소문자와 공백을 정규화한다`() {
        every { consentPort.findConsentAt("12345678") } returns Instant.parse("2026-05-10T00:00:00Z")
        val gate = gate(magicjarMode = " real ", magicjarAllowReal = "True")

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Right::class.java)
        assertThat((result as OrderResult.Right).value).isEqualTo(TradingMode.REAL)
    }

    @Test
    fun `(c) MAGICJAR_ALLOW_REAL=false → DENIED MAGICJAR_ALLOW_REAL=false`() {
        val gate = gate(magicjarMode = "REAL", magicjarAllowReal = "false")

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Left::class.java)
        val deny = (result as OrderResult.Left).value
        assertThat(deny).isInstanceOf(DenyReason.RealGateDenied::class.java)
        assertThat(deny.condition).isEqualTo("MAGICJAR_ALLOW_REAL=false")
    }

    @Test
    fun `(d) CONSENT_NOT_GIVEN — 사용자 동의 미존재 → DENIED CONSENT_NOT_GIVEN`() {
        every { consentPort.findConsentAt("12345678") } returns null
        val gate = gate(magicjarMode = "REAL", magicjarAllowReal = "true")

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Left::class.java)
        val deny = (result as OrderResult.Left).value
        assertThat(deny).isInstanceOf(DenyReason.RealGateDenied::class.java)
        assertThat(deny.condition).isEqualTo("CONSENT_NOT_GIVEN")
    }

    // ── ADR 0122 §6 GAP-0 hotfix — 페이퍼 전략 MOCK 강제 ──────────────────────

    @Test
    fun `(e) paper + REAL 3조건 모두 충족 → env 무관 MOCK 강제`() {
        every { consentPort.findConsentAt("12345678") } returns Instant.parse("2026-05-10T00:00:00Z")
        val gate = gate(magicjarMode = "REAL", magicjarAllowReal = "true")

        val result = gate.resolveMode(userId = "12345678", paperStrategy = true)

        assertThat(result).isInstanceOf(OrderResult.Right::class.java)
        assertThat((result as OrderResult.Right).value).isEqualTo(TradingMode.MOCK)
    }

    @Test
    fun `(f) paper + env=MOCK → MOCK (현행 동작 유지)`() {
        val gate = gate(magicjarMode = "MOCK", magicjarAllowReal = "false")

        val result = gate.resolveMode(userId = "12345678", paperStrategy = true)

        assertThat(result).isInstanceOf(OrderResult.Right::class.java)
        assertThat((result as OrderResult.Right).value).isEqualTo(TradingMode.MOCK)
    }

    @Test
    fun `(g) paper 시 consentPort 미호출 — 3중 게이트 평가 자체 skip`() {
        val isolatedConsentPort = mockk<RealTradingConsentPort>()
        val isolatedProvider =
            mockk<ObjectProvider<RealTradingConsentPort>>().also {
                every { it.getIfAvailable() } returns isolatedConsentPort
            }
        val gate = gate(consent = isolatedProvider, magicjarMode = "REAL", magicjarAllowReal = "true")

        val result = gate.resolveMode(userId = "12345678", paperStrategy = true)

        assertThat((result as OrderResult.Right).value).isEqualTo(TradingMode.MOCK)
        verify(exactly = 0) { isolatedConsentPort.findConsentAt(any()) }
        verify(exactly = 0) { isolatedProvider.getIfAvailable() }
    }

    // ── 감사 2026-09-19 §5 A — DB 활성모드 스위치 (재기동 없는 전환) ──────────

    @Test
    fun `(h) env 상한 REAL 열림 + DB 활성모드 MOCK → MOCK 존중 (재기동 없는 전환)`() {
        every { consentPort.findConsentAt("12345678") } returns Instant.parse("2026-05-10T00:00:00Z")
        val gate =
            gate(
                activeMode = activeModeProvider(TradingMode.MOCK),
                magicjarMode = "REAL",
                magicjarAllowReal = "true",
            )

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Right::class.java)
        assertThat((result as OrderResult.Right).value).isEqualTo(TradingMode.MOCK)
    }

    @Test
    fun `(i) env 상한 MOCK — DB 활성모드 REAL 이어도 MOCK (상한이 절대)`() {
        // env 상한이 MOCK이면 조건1에서 즉시 MOCK — DB REAL 을 넘어 REAL 을 열 수 없다(자본 사고 방지).
        val activeReal = activeModeProvider(TradingMode.REAL)
        val gate = gate(activeMode = activeReal, magicjarMode = "MOCK", magicjarAllowReal = "true")

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Right::class.java)
        assertThat((result as OrderResult.Right).value).isEqualTo(TradingMode.MOCK)
    }

    @Test
    fun `(j) ActiveTradingModePort 미합류 → MOCK fail-closed`() {
        every { consentPort.findConsentAt("12345678") } returns Instant.parse("2026-05-10T00:00:00Z")
        val emptyActiveProvider =
            mockk<ObjectProvider<ActiveTradingModePort>>().also {
                every { it.getIfAvailable() } returns null
            }
        val gate = gate(activeMode = emptyActiveProvider, magicjarMode = "REAL", magicjarAllowReal = "true")

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Right::class.java)
        assertThat((result as OrderResult.Right).value).isEqualTo(TradingMode.MOCK)
    }
}
