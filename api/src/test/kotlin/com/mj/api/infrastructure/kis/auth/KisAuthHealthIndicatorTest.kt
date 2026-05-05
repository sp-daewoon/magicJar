package com.mj.api.infrastructure.kis.auth

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * R3-5c-3 PR3 회귀: `/actuator/health/kisAuth` HealthIndicator.
 *
 * spec §10-4 정합:
 *   - 5분 윈도우 내 fallback 0 → UP
 *   - 5분 윈도우 내 fallback >0 → DEGRADED + advice 메시지
 */
class KisAuthHealthIndicatorTest {
    @Test
    fun `fallback 미발생 시 UP 상태`() {
        val registry = SimpleMeterRegistry()
        val indicator = KisAuthHealthIndicator(registry)

        val health = indicator.health()

        assertThat(health.status.code).isEqualTo("UP")
        assertThat(health.details).containsEntry("redisUnavailableLast5m", 0L)
    }

    @Test
    fun `fallback 발생 시 DEGRADED + advice 노출`() {
        val registry = SimpleMeterRegistry()
        val indicator = KisAuthHealthIndicator(registry)

        // 첫 측정으로 baseline 고정 (fallback 0)
        indicator.health()

        // 그 후 카운터 증가 — 새 측정에서 delta=2 노출
        registry
            .counter("kis_token_redis_unavailable_total", "scope", "system:prod")
            .increment(2.0)
        registry
            .counter("kis_token_fallback_used_total", "scope", "system:prod", "fallback", "in-memory-fresh")
            .increment(5.0)

        val health = indicator.health()

        assertThat(health.status.code).isEqualTo("DEGRADED")
        assertThat(health.details).containsEntry("redisUnavailableLast5m", 2L)
        assertThat(health.details).containsEntry("fallbackUsedLast5m", 5L)
        assertThat(health.details).containsEntry("raceRisk", "high")
        assertThat(health.details["advice"].toString()).contains("redis 컨테이너 즉시 복구")
    }
}
