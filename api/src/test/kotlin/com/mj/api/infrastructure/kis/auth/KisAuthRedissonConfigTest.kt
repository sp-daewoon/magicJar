package com.mj.api.infrastructure.kis.auth

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * R3-5c-3 PR2 회귀:
 *  1. `magicjar.kis.redis-token.enabled` 미설정/false → RedissonClient·KisAuthMetrics Bean 모두 미등록.
 *  2. `enabled=true` → Bean 등록.
 *  3. enabled=true 시 KisAuthMetrics는 모든 메트릭 핸들 발급 가능 (SimpleMeterRegistry 동작 확인).
 */
class KisAuthRedissonConfigTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration::class.java))
            .withUserConfiguration(
                KisAuthRedissonConfig::class.java,
                KisAuthMetrics::class.java,
                TestMeterRegistryConfig::class.java,
            )

    @Test
    fun `enabled 미설정 시 RedissonClient + KisAuthMetrics Bean 미등록 (default OFF)`() {
        runner.run { ctx ->
            assertThat(ctx).doesNotHaveBean(RedissonClient::class.java)
            assertThat(ctx).doesNotHaveBean(KisAuthMetrics::class.java)
        }
    }

    @Test
    fun `enabled=false 시 Bean 미등록`() {
        runner
            .withPropertyValues("magicjar.kis.redis-token.enabled=false")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(RedissonClient::class.java)
                assertThat(ctx).doesNotHaveBean(KisAuthMetrics::class.java)
            }
    }

    @Test
    fun `enabled=true 시 KisAuthMetrics Bean 등록되고 메트릭 increment 가능`() {
        runner
            .withPropertyValues(
                "magicjar.kis.redis-token.enabled=true",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6379",
            ).run { ctx ->
                // RedissonClient는 실 Redis 미가용 시 Bean 등록은 시도되지만 connection 실패 가능.
                // 본 회귀의 핵심은 게이트 작동(미등록 → 등록)이므로 Bean 정의만 확인.
                assertThat(ctx).hasBean("kisAuthMetrics".replaceFirstChar { it })
                val metrics = ctx.getBean(KisAuthMetrics::class.java)
                metrics.tokenIssued("system:prod", KisAuthMetrics.TokenIssuanceSource.FRESH)
                metrics.tokenInvalidated("system:prod", KisAuthMetrics.TokenInvalidationReason.UNAUTHORIZED_401)
                metrics.redisUnavailable("system:prod")
                metrics.fallbackUsed("system:prod", KisAuthMetrics.FallbackKind.IN_MEMORY_HIT)
                metrics.approvalIssued("system:prod", KisAuthMetrics.TokenIssuanceSource.FRESH)
                val registry = ctx.getBean(MeterRegistry::class.java)
                assertThat(registry.find("kis_token_issuance_total").counter()).isNotNull
                assertThat(registry.find("kis_token_redis_unavailable_total").counter()).isNotNull
                assertThat(registry.find("kis_token_fallback_used_total").counter()).isNotNull
                assertThat(registry.find("kis_approval_issuance_total").counter()).isNotNull
            }
    }

    @Configuration
    class TestMeterRegistryConfig {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }
}
