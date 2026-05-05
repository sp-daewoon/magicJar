package com.mj.api.infrastructure.kis.auth

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * R3-5c-3 PR2 — KIS 토큰 운영 메트릭 (api 모듈).
 *
 * spec §3 D12 / §14 정합 — 5 메트릭:
 * - `kis_token_issuance_total{scope, source}` — fresh / redis-hit / in-memory
 * - `kis_token_invalidation_total{scope, reason}` — 401 / manual / rtopic
 * - `kis_token_lock_wait_seconds{scope}` (Timer)
 * - `kis_token_redis_unavailable_total{scope}` — P0 알람 후보
 * - `kis_approval_issuance_total{scope, source}` — WS approval (consumer만 사용, api는 0)
 *
 * 별도: `kis_token_fallback_used_total{scope, fallback}` — Redis 다운 fallback 사용 횟수
 *  (in-memory L1 hit / in-memory fresh) — 사용자 결정 D7 보강 정합.
 *
 * 게이트:
 * - `magicjar.kis.redis-token.enabled=true` 필요 (Redisson Bean과 동일 게이트).
 * - 본 PR은 메트릭 등록만, 실제 increment는 PR3+ 어댑터에서 호출.
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
    /** scope = "system:prod" / "system:vps" / "user:{userId}:{mode}" */
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
            .description("KIS 토큰 RLock 대기 시간 (Redisson tryLock waitTime)")
            .tag("scope", scope)
            .register(registry)

    /**
     * spec §10 / 사용자 결정 D7 보강 — Redis 다운 fallback 카운터.
     * P0 알람 트리거 후보: `increase(kis_token_redis_unavailable_total[5m]) > 0`.
     */
    fun redisUnavailable(scope: String) {
        Counter
            .builder("kis_token_redis_unavailable_total")
            .description("KIS 토큰 Redis 다운 감지 횟수 — race-risk-high. P0 알람 trigger 후보")
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
            .description("KIS 토큰 in-memory fallback 사용 횟수 — Redis 다운 중 매 호출 누적")
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
            .description("KIS WS approval 발급 카운터")
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
