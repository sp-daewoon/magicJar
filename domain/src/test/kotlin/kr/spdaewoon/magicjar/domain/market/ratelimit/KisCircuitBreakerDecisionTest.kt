package kr.spdaewoon.magicjar.domain.market.ratelimit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * KisCircuitBreakerDecision 순수 판정 테스트 (ADR 0037 Phase 3).
 *
 * 자본 안전 핵심: USER_PATH는 OPEN이어도 절대 차단 안 됨(정책 C).
 */
class KisCircuitBreakerDecisionTest {
    // ── shouldBlock: 정책 C (USER_PATH 예외) ──────────────────────────────

    @Test
    fun `회로 닫힘이면 어떤 priority도 차단 안 함`() {
        KisCallPriority.entries.forEach { p ->
            KisCircuitBreakerDecision.shouldBlock(circuitOpen = false, priority = p) shouldBe false
        }
    }

    @Test
    fun `회로 OPEN이어도 USER_PATH는 절대 차단 안 함 (자본 경로 보호)`() {
        KisCircuitBreakerDecision.shouldBlock(circuitOpen = true, priority = KisCallPriority.USER_PATH) shouldBe false
    }

    @Test
    fun `회로 OPEN이면 USER_PATH 외 배경 트래픽은 차단`() {
        KisCallPriority.entries
            .filter { it != KisCallPriority.USER_PATH }
            .forEach { p ->
                KisCircuitBreakerDecision.shouldBlock(circuitOpen = true, priority = p) shouldBe true
            }
    }

    // ── shouldOpen: fail rate + 최소 표본 ────────────────────────────────

    @Test
    fun `표본 부족이면 실패율 높아도 OPEN 안 함`() {
        KisCircuitBreakerDecision.shouldOpen(
            failureCount = 19,
            successCount = 0,
            failureRateThreshold = 0.5,
            minimumSamples = 20,
        ) shouldBe false
    }

    @Test
    fun `표본 충분 + 실패율 임계 이상이면 OPEN`() {
        KisCircuitBreakerDecision.shouldOpen(
            failureCount = 10,
            successCount = 10,
            failureRateThreshold = 0.5,
            minimumSamples = 20,
        ) shouldBe true
    }

    @Test
    fun `표본 충분해도 실패율 임계 미만이면 OPEN 안 함`() {
        KisCircuitBreakerDecision.shouldOpen(
            failureCount = 9,
            successCount = 21,
            failureRateThreshold = 0.5,
            minimumSamples = 20,
        ) shouldBe false
    }

    @Test
    fun `실패 0이면 OPEN 안 함`() {
        KisCircuitBreakerDecision.shouldOpen(
            failureCount = 0,
            successCount = 100,
            failureRateThreshold = 0.5,
            minimumSamples = 20,
        ) shouldBe false
    }

    @Test
    fun `실패율 임계 초과(전건 실패)면 OPEN`() {
        KisCircuitBreakerDecision.shouldOpen(
            failureCount = 25,
            successCount = 0,
            failureRateThreshold = 0.5,
            minimumSamples = 20,
        ) shouldBe true
    }
}
