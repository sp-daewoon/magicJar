package kr.spdaewoon.magicjar.api.adapter.redis

import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * KIS 관련 Redis 캐시 무효화 헬퍼 (ADR 0074 §7 정합).
 *
 * KIS 키 변경(PUT) 성공 직후 호출:
 *   1. `kis:token:{userId}:{accountType}`        DEL — KIS OAuth tokenP cache (T-1 단일 prefix LOCK)
 *      + `access_token:{userId}:{accountType}`   DEL — 전환기 안전망 (T-2, 기존 운영 환경 잔존 키 흡수)
 *   2. `kis:approval:{userId}`                   DEL — WS approval_key cache (REAL 시)
 *   3. `kis:key-changed-lock:{userId}:{accountType}` SET TTL 5s — T-O 주문 발행 잠금
 *
 * ### Redis 키 prefix 정책 (T-1·T-2 통합 — 2026-05-20)
 * - 신규 표준: `kis:token:*` (consumer `KisAuthAdapter` + api `KisTokenCacheHelper` 공통)
 * - 결함 prefix `access_token:*` 폐기 — 본 invalidator 만 전환기 안전망으로 동시 DEL
 * - `kis:approval:*` · `kis:key-changed-lock:*` 는 기존 유지
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
@Component
class KisCacheInvalidator(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(KisCacheInvalidator::class.java)

    companion object {
        private val KEY_CHANGED_LOCK_TTL = Duration.ofSeconds(5)
    }

    /**
     * KIS OAuth tokenP cache 무효화 — 두 prefix 모두 DEL (T-1·T-2).
     *
     * 신규 표준 `kis:token:` + 결함 `access_token:` 동시 삭제로 운영 환경 마이그레이션 안전.
     * 결함 prefix DEL 결과는 0건이면 자연 sunset (다음 minor에서 LEGACY 제거 후보).
     */
    fun deleteAccessToken(
        userId: String,
        accountType: String,
    ) {
        val primaryKey = RedisKeyRegistry.Kis.token(userId, accountType)
        val legacyKey = RedisKeyRegistry.Kis.legacyAccessToken(userId, accountType)
        val primaryDeleted = redisTemplate.delete(primaryKey)
        val legacyDeleted = redisTemplate.delete(legacyKey)
        log.info(
            "Redis KIS token DEL — userId={}**** accountType={} primary={} legacy={}",
            userId.take(4),
            accountType,
            primaryDeleted,
            legacyDeleted,
        )
    }

    /**
     * kis:approval:{userId} 삭제.
     * KIS WS approval_key 캐시 무효화 (accountType=REAL 시만 호출).
     */
    fun deleteApprovalKey(userId: String) {
        val key = RedisKeyRegistry.Kis.approvalLegacyUserOnly(userId)
        val deleted = redisTemplate.delete(key)
        log.info(
            "Redis approval_key DEL — userId={}**** deleted={}",
            userId.take(4),
            deleted,
        )
    }

    /**
     * kis:key-changed-lock:{userId}:{accountType} SET TTL 5초.
     * T-O OrderRouter 주문 발행 잠금 (ADR 0074 §2-2-3 5초 잠금 LOCK).
     */
    fun setKeyChangedLock(
        userId: String,
        accountType: String,
    ) {
        val key = RedisKeyRegistry.Kis.keyChangedLock(userId, accountType)
        redisTemplate.opsForValue().set(key, "1", KEY_CHANGED_LOCK_TTL)
        log.info(
            "Redis key-changed-lock SET 5s — userId={}**** accountType={}",
            userId.take(4),
            accountType,
        )
    }

    /**
     * kis:key-changed-lock:{userId}:{accountType} 존재 여부 확인.
     * T-O OrderRouter에서 주문 발행 전 호출.
     */
    fun isKeyChangedLockActive(
        userId: String,
        accountType: String,
    ): Boolean {
        val key = RedisKeyRegistry.Kis.keyChangedLock(userId, accountType)
        return redisTemplate.hasKey(key) == true
    }
}
