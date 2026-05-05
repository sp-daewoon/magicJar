package com.mj.consumer.infrastructure.kis.auth

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * R3-5c-3 PR2 — consumer 모듈 Redisson Bean (KIS 토큰 + WS approval 분산 single-flight).
 *
 * 본 PR은 의존성·Bean·메트릭 골격만 등록하고, 실제 어댑터는 PR5에서 활성화.
 *
 * 게이트:
 * - `magicjar.kis.redis-token.enabled=true` 필요. default false.
 * - `RedissonClient` Bean이 이미 등록돼 있으면 skip.
 *
 * 운영:
 * - consumer는 시스템 키(데이터 조회 fallback) + 사용자 키(`KisHttpSession`) +
 *   WS approval(시세·체결통보) 3종 모두 본 RedissonClient를 공유한다.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "magicjar.kis.redis-token",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@EnableConfigurationProperties(RedisProperties::class)
class KisAuthRedissonConfig {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient::class)
    fun kisAuthRedissonClient(redisProps: RedisProperties): RedissonClient {
        val config = Config()
        val address = "redis://${redisProps.host}:${redisProps.port}"
        config
            .useSingleServer()
            .setAddress(address)
            .setConnectionPoolSize(8)
            .setConnectionMinimumIdleSize(2)
            .setTimeout(2000)
            .setRetryAttempts(2)
            .setRetryInterval(1500)
        redisProps.password?.takeIf { it.isNotBlank() }?.let { config.useSingleServer().password = it }
        return Redisson.create(config)
    }
}
