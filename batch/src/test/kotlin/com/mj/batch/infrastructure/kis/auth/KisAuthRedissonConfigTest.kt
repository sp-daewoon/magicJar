package com.mj.batch.infrastructure.kis.auth

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
 * R3-5c-3 PR2 — batch 모듈 게이트 회귀.
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
    fun `enabled 미설정 시 RedissonClient + KisAuthMetrics Bean 미등록`() {
        runner.run { ctx ->
            assertThat(ctx).doesNotHaveBean(RedissonClient::class.java)
            assertThat(ctx).doesNotHaveBean(KisAuthMetrics::class.java)
        }
    }

    @Test
    fun `enabled=true 시 KisAuthMetrics Bean 등록 + 메트릭 카운터 발급`() {
        runner
            .withPropertyValues(
                "magicjar.kis.redis-token.enabled=true",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6379",
            ).run { ctx ->
                val metrics = ctx.getBean(KisAuthMetrics::class.java)
                metrics.tokenIssued("system:prod", KisAuthMetrics.TokenIssuanceSource.FRESH)
                metrics.redisUnavailable("system:prod")
                val registry = ctx.getBean(MeterRegistry::class.java)
                assertThat(registry.find("kis_token_issuance_total").counter()).isNotNull
                assertThat(registry.find("kis_token_redis_unavailable_total").counter()).isNotNull
            }
    }

    @Configuration
    class TestMeterRegistryConfig {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }
}
