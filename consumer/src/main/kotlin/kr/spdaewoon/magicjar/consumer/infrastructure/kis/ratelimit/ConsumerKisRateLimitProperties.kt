package kr.spdaewoon.magicjar.consumer.infrastructure.kis.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * KIS Rate Limit 설정 (consumer 모듈 Bean — spec PR #564 §2-3 옵션 a 정합).
 *
 * 기본값 LOCK (ADR 0037 §1 KIS 공식 한도 — 초당 10건):
 *   - bucketCapacity=10
 *   - bucketRefillTokens=10
 *   - bucketRefillPeriodSeconds=1
 *   - bucketKey="kis-rate-limit:global" (★ api 모듈 KisRateLimitProperties와 동일 LOCK — Redis-level share)
 *
 * Phase 2 옵션 a 정합 (spec §2-3):
 *   - api 모듈 [KisRateLimitProperties] + consumer 모듈 본 Properties 동일 prefix·동일 bucketKey LOCK
 *   - multi-JVM 단일 bucket share = Bucket4j Lettuce backend Redis 단일 ZSET 키 share (api·consumer ApplicationContext 분리 정합)
 *   - 단일 source-of-truth = `magicjar.kis.rate-limiter.*` env keys (api·consumer 동일 application.yml prefix 강제)
 *
 * env override (rollback path — spec §7 정합):
 *   - MAGICJAR_KIS_RATE_LIMIT_BURST=15 → bucketCapacity=15 (KIS 차단 risk 동반 — 사용자 알림 의무)
 */
@ConfigurationProperties(prefix = "magicjar.kis.rate-limiter")
data class ConsumerKisRateLimitProperties(
    /**
     * 어댑터 활성화 스위치 (default true — 활성).
     * false 시 NoOp 패턴 fallback — 본 dispatch는 baseline true LOCK.
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
     * USER_PATH 전용 예약 토큰 수 (ADR 0112 의제 — 주문 path 우선권 보호).
     *
     * ★ api·batch Properties와 동일 default·동일 의미 LOCK (multi-JVM 동일 Redis bucket 분할 정합).
     *
     * 차등 라우팅:
     *   - shared pool = bucketCapacity - userPathReserveTokens (비주문: INGESTION·BACKFILL·INGESTION_LOW)
     *   - reserve pool = userPathReserveTokens (USER_PATH 전용 — shared 소진 시 fallback)
     *   - 총합 = bucketCapacity (KIS 초당 한도 미초과)
     *
     * consumer 모듈은 주문 본주문(KisOrderHttpAdapter USER_PATH)이 흐르는 핵심 경로 — reserve 보호 직접 수혜.
     * 0 설정 시 단일 bucket 회귀 (rollback path).
     */
    val userPathReserveTokens: Long = 3L,
    /**
     * refill 윈도 초 (KIS 한도 = 1초).
     */
    val bucketRefillPeriodSeconds: Long = 1L,
    /**
     * Redis ZSET 키 (★ api 모듈 KisRateLimitProperties와 동일 LOCK — multi-JVM 단일 bucket share).
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
     * interval refill 전환 후에는 다음 1초 윈도우 진입을 짧게 확인한다.
     */
    val acquirePollIntervalMillis: Long = 50L,
)
