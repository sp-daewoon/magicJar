package kr.spdaewoon.magicjar.batch.infrastructure.kis.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * batch 모듈 KIS Rate Limit 설정 (spec PR #564 §2-3 옵션 a 정합 — api·consumer Properties 1:1).
 *
 * 기본값 LOCK (ADR 0037 §1 KIS 공식 한도 — 초당 10건):
 *   - bucketCapacity=10
 *   - bucketRefillTokens=10
 *   - bucketRefillPeriodSeconds=1
 *   - bucketKey="kis-rate-limit:global" (★ api·consumer KisRateLimitProperties와 동일 LOCK — Redis-level share)
 *
 * Phase 2 옵션 a 정합 (spec §2-3):
 *   - api·consumer + 본 batch Properties 동일 prefix·동일 bucketKey LOCK
 *   - multi-JVM 단일 bucket share = Bucket4j Lettuce backend Redis 단일 ZSET 키 share
 *   - 단일 source-of-truth = `magicjar.kis.rate-limiter.*` env keys
 *
 * 합류 대상 어댑터 (T-D-2 D-2 — 1일 1회 cron이라 risk 낮지만 일괄 backfill 시 burst 가능):
 *   - KisChkHolidayAdapter (CTCA0903R, 영업일 정보)
 *   - KisInstrumentMasterAdapter (KOSPI/KOSDAQ 종목마스터 zip 다운로드)
 *   - KisKsdInfoAdapter (6 ksdinfo endpoint — 배당/분할/합병/증자/감자)
 */
@ConfigurationProperties(prefix = "magicjar.kis.rate-limiter")
data class BatchKisRateLimitProperties(
    /** 어댑터 활성화 스위치 (default true — 활성). false 시 NoOp 패턴 fallback. */
    val enabled: Boolean = true,
    /** 단일 bucket 토큰 capacity (KIS 공식 한도 = 10). */
    val bucketCapacity: Long = 10L,
    /** refill 1회 토큰 수 (= capacity → 매 refill 윈도 100% 회복). */
    val bucketRefillTokens: Long = 10L,
    /** refill 윈도 초 (KIS 한도 = 1초). */
    val bucketRefillPeriodSeconds: Long = 1L,
    /** Redis ZSET 키 (★ api·consumer Properties와 동일 LOCK — multi-JVM 단일 bucket share). */
    val bucketKey: String = "kis-rate-limit:global",
    /** Bucket4j Redis backend TTL (refill 윈도의 10배 — 안전 마진). */
    val bucketTtlSeconds: Long = 10L,
    /** acquire() polling 간격 (token 부족 시 재시도 sleep). */
    val acquirePollIntervalMillis: Long = 50L,
)
