package com.mj.batch.infrastructure.kis.auth

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * R3-5c-3 PR2 — KIS 토큰 운영 메트릭 (batch 모듈).
 *
 * spec §3 D12 / §14 정합. api·consumer와 같은 메트릭 이름 + 모듈별 등록.
 * 메트릭 차원에서 모듈을 구분할 필요는 없다(scope 태그가 충분).
 * 단 ConditionalOnProperty 게이트와 등록 시점만 모듈별로 분리.
 *
 * 사용처는 PR4 어댑터(BatchRedisKisTokenStoreAdapter / RealKisRestAdapter / MinuteCandleBackfillJob).
 */
@Component
@ConditionalOnProperty(
    prefix = "magicjar.kis.redis-token",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class KisAuthMetrics(
    private val registry: MeterRegistry,
) {
    fun tokenIssued(
        scope: String,
        source: TokenIssuanceSource,
    ) {
        Counter
            .builder("kis_token_issuance_total")
            .description("KIS 토큰 발급 카운터 (source = fresh/redis-hit/in-memory)")
            .tag("scope", scope)
            .tag("source", source.tag)
            .register(registry)
            .increment()
    }

    fun tokenInvalidated(
        scope: String,
        reason: TokenInvalidationReason,
    ) {
        Counter
            .builder("kis_token_invalidation_total")
            .description("KIS 토큰 무효화 카운터 (reason = 401/manual/rtopic)")
            .tag("scope", scope)
            .tag("reason", reason.tag)
            .register(registry)
            .increment()
    }

    fun lockWaitTimer(scope: String): Timer =
        Timer
            .builder("kis_token_lock_wait_seconds")
            .description("KIS 토큰 RLock 대기 시간")
            .tag("scope", scope)
            .register(registry)

    fun redisUnavailable(scope: String) {
        Counter
            .builder("kis_token_redis_unavailable_total")
            .description("KIS 토큰 Redis 다운 감지 — race-risk-high. P0 알람 trigger")
            .tag("scope", scope)
            .register(registry)
            .increment()
    }

    fun fallbackUsed(
        scope: String,
        fallback: FallbackKind,
    ) {
        Counter
            .builder("kis_token_fallback_used_total")
            .description("KIS 토큰 in-memory fallback 사용 횟수")
            .tag("scope", scope)
            .tag("fallback", fallback.tag)
            .register(registry)
            .increment()
    }

    fun approvalIssued(
        scope: String,
        source: TokenIssuanceSource,
    ) {
        Counter
            .builder("kis_approval_issuance_total")
            .description("KIS WS approval 발급 카운터 (batch에서는 미사용)")
            .tag("scope", scope)
            .tag("source", source.tag)
            .register(registry)
            .increment()
    }

    enum class TokenIssuanceSource(
        val tag: String,
    ) {
        FRESH("fresh"),
        REDIS_HIT("redis-hit"),
        IN_MEMORY("in-memory"),
    }

    enum class TokenInvalidationReason(
        val tag: String,
    ) {
        UNAUTHORIZED_401("401"),
        MANUAL("manual"),
        RTOPIC("rtopic"),
    }

    enum class FallbackKind(
        val tag: String,
    ) {
        IN_MEMORY_HIT("in-memory-hit"),
        IN_MEMORY_FRESH("in-memory-fresh"),
    }
}
