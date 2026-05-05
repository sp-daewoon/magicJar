package com.mj.api.infrastructure.kis.auth

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
 * R3-5c-3 PR2 — Redisson 분산 single-flight Bean.
 *
 * 본 PR은 의존성·Bean·메트릭 골격만 등록하고, 실제 어댑터(RLock/RTopic 활용)는
 * PR3(api)·PR4(batch)·PR5(consumer)에서 활성화한다.
 *
 * 게이트:
 * - `magicjar.kis.redis-token.enabled=true` 필요. default false (현 단계).
 * - `RedissonClient` Bean이 이미 등록돼 있으면(다른 통합) 본 Bean은 skip.
 *
 * 운영:
 * - `application.yml`의 `spring.data.redis.{host,port}` 값을 그대로 재사용한다.
 *   별도 RedisProperties 분기 없이 동일 인스턴스 공유(spec §7-1).
 * - Spring Data Redis(Lettuce)와 별도 connection 풀로 작동(같은 Redis instance).
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
            .setTimeout(2000) // KIS 토큰 fetch 평균 1~3s, Redis op timeout 2s 별개
            .setRetryAttempts(2)
            .setRetryInterval(1500)
        redisProps.password?.takeIf { it.isNotBlank() }?.let { config.useSingleServer().password = it }
        return Redisson.create(config)
    }
}
