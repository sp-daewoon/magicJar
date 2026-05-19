package kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * KIS Rate Limit 설정 (spec PR #564 §2-1 LOCK — Bucket4j-Redis 단일 bucket).
 *
 * 기본값 LOCK (ADR 0037 §1 KIS 공식 한도 — 초당 10건):
 *   - bucketCapacity=10
 *   - bucketRefillTokens=10
 *   - bucketRefillPeriodSeconds=1
 *
 * Phase 1: Circuit Breaker 통계 윈도/임계는 정의만 (실 구현 Phase 3 후속).
 *
 * env override (rollback path — spec §7 정합):
 *   - MAGICJAR_KIS_RATE_LIMIT_BURST=15 → bucketCapacity=15 (KIS 차단 risk 동반 — 사용자 알림 의무)
 */
@ConfigurationProperties(prefix = "magicjar.kis.rate-limiter")
data class KisRateLimitProperties(
    /**
     * 어댑터 활성화 스위치 (default true — 활성).
     * false 시 NoOpKisRateLimiterAdapter fallback (테스트·단일 JVM 부팅 path).
     */
    val enabled: Boolean = true,
    /**
     * 단일 bucket 토큰 capacity (KIS 공식 한도 = 10).
     * 11번째 호출은 1초 윈도 내 timeout.
     */
    val bucketCapacity: Long = 10L,
    /**
     * refill 1회 토큰 수 (= capacity → 매 refill 윈도 100% 회복).
     */
    val bucketRefillTokens: Long = 10L,
    /**
     * refill 윈도 초 (KIS 한도 = 1초).
     */
    val bucketRefillPeriodSeconds: Long = 1L,
    /**
     * Redis ZSET 키 (multi-JVM share — 단일 글로벌 bucket).
     */
    val bucketKey: String = "kis-rate-limit:global",
    /**
     * Bucket4j Redis backend TTL (refill 윈도의 10배 — 안전 마진).
     */
    val bucketTtlSeconds: Long = 10L,
    /**
     * acquire() polling 간격 (token 부족 시 재시도 sleep).
     *
     * Phase 1 단순화: BucketProxy는 `tryConsume` non-blocking 위주 → timeout 내 short sleep polling.
     * 50ms = KIS 한도 0.5 token 회복 기다림 (정확한 ceiling 도달 시 즉시 진입).
     */
    val acquirePollIntervalMillis: Long = 50L,
    /**
     * Circuit Breaker — fail rate 임계 (default 0.5 = 50%). Phase 3 합류.
     */
    val circuitBreakerFailureRate: Double = 0.5,
    /**
     * Circuit Breaker — 윈도 초 (default 300 = 5분). Phase 3 합류.
     */
    val circuitBreakerWindowSeconds: Long = 300L,
    /**
     * Circuit Breaker — OPEN 유지 초 (default 300 = 5분). Phase 3 합류.
     */
    val circuitBreakerOpenDurationSeconds: Long = 300L,
)
