package kr.spdaewoon.magicjar.api.adapter.redis

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * KIS 관련 Redis 캐시 무효화 헬퍼 (ADR 0074 §7 정합).
 *
 * KIS 키 변경(PUT) 성공 직후 호출:
 *   1. access_token:{userId}:{accountType}   DEL — KIS OAuth tokenP cache
 *   2. kis:approval:{userId}                 DEL — WS approval_key cache (REAL 시)
 *   3. kis:key-changed-lock:{userId}:{accountType}  SET TTL 5s — T-O 주문 발행 잠금
 *
 * Redis 키 prefix 정책:
 *   - 기존 access_token:* 는 prefix kis: 누락 — 호환성 유지 (ADR 0074 §7-1 정합)
 *   - 신규 kis:key-changed-lock:* 는 prefix kis: 포함
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
@Component
class KisCacheInvalidator(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(KisCacheInvalidator::class.java)

    companion object {
        private const val TOKEN_KEY_PREFIX = "access_token"
        private const val APPROVAL_KEY_PREFIX = "kis:approval"
        private const val KEY_CHANGED_LOCK_PREFIX = "kis:key-changed-lock"
        private val KEY_CHANGED_LOCK_TTL = Duration.ofSeconds(5)
    }

    /**
     * access_token:{userId}:{accountType} 삭제.
     * KIS OAuth tokenP 캐시 무효화.
     */
    fun deleteAccessToken(
        userId: String,
        accountType: String,
    ) {
        val key = "$TOKEN_KEY_PREFIX:$userId:$accountType"
        val deleted = redisTemplate.delete(key)
        log.info(
            "Redis access_token DEL — userId={}**** accountType={} deleted={}",
            userId.take(4),
            accountType,
            deleted,
        )
    }

    /**
     * kis:approval:{userId} 삭제.
     * KIS WS approval_key 캐시 무효화 (accountType=REAL 시만 호출).
     */
    fun deleteApprovalKey(userId: String) {
        val key = "$APPROVAL_KEY_PREFIX:$userId"
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
        val key = "$KEY_CHANGED_LOCK_PREFIX:$userId:$accountType"
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
        val key = "$KEY_CHANGED_LOCK_PREFIX:$userId:$accountType"
        return redisTemplate.hasKey(key) == true
    }
}
