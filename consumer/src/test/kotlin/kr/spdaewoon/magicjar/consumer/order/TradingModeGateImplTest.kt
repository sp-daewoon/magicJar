package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.RealTradingConsentPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant

/**
 * TradingModeGateImpl 단위 테스트 (ADR 0068 §14-3·§14-7 LOCK).
 *
 * 4 케이스 매트릭스:
 *  (a) MOCK fallback (env=MOCK)
 *  (b) REAL 정상 (3 조건 모두 true)
 *  (c) ALLOW_REAL=false denied
 *  (d) CONSENT_NOT_GIVEN denied
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class TradingModeGateImplTest {
    private val consentPort = mockk<RealTradingConsentPort>()
    private val consentProvider =
        mockk<ObjectProvider<RealTradingConsentPort>>().also {
            every { it.getIfAvailable() } returns consentPort
        }

    @Test
    fun `(a) env mode가 MOCK이면 MOCK fallback (안전 default)`() {
        val gate = TradingModeGateImpl(consentProvider, magicjarMode = "MOCK", magicjarAllowReal = "false")

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Right::class.java)
        assertThat((result as OrderResult.Right).value).isEqualTo(TradingMode.MOCK)
    }

    @Test
    fun `(b) REAL 정상 — 3 조건 모두 충족 → REAL 반환`() {
        every { consentPort.findConsentAt("12345678") } returns Instant.parse("2026-05-10T00:00:00Z")
        val gate = TradingModeGateImpl(consentProvider, magicjarMode = "REAL", magicjarAllowReal = "true")

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Right::class.java)
        assertThat((result as OrderResult.Right).value).isEqualTo(TradingMode.REAL)
    }

    @Test
    fun `env mode와 allow-real은 대소문자와 공백을 정규화한다`() {
        every { consentPort.findConsentAt("12345678") } returns Instant.parse("2026-05-10T00:00:00Z")
        val gate = TradingModeGateImpl(consentProvider, magicjarMode = " real ", magicjarAllowReal = "True")

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Right::class.java)
        assertThat((result as OrderResult.Right).value).isEqualTo(TradingMode.REAL)
    }

    @Test
    fun `(c) MAGICJAR_ALLOW_REAL=false → DENIED MAGICJAR_ALLOW_REAL=false`() {
        val gate = TradingModeGateImpl(consentProvider, magicjarMode = "REAL", magicjarAllowReal = "false")

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Left::class.java)
        val deny = (result as OrderResult.Left).value
        assertThat(deny).isInstanceOf(DenyReason.RealGateDenied::class.java)
        assertThat(deny.condition).isEqualTo("MAGICJAR_ALLOW_REAL=false")
    }

    @Test
    fun `(d) CONSENT_NOT_GIVEN — 사용자 동의 미존재 → DENIED CONSENT_NOT_GIVEN`() {
        every { consentPort.findConsentAt("12345678") } returns null
        val gate = TradingModeGateImpl(consentProvider, magicjarMode = "REAL", magicjarAllowReal = "true")

        val result = gate.resolveMode(userId = "12345678")

        assertThat(result).isInstanceOf(OrderResult.Left::class.java)
        val deny = (result as OrderResult.Left).value
        assertThat(deny).isInstanceOf(DenyReason.RealGateDenied::class.java)
        assertThat(deny.condition).isEqualTo("CONSENT_NOT_GIVEN")
    }
}
