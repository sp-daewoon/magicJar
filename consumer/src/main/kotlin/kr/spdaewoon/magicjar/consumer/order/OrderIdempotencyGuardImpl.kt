package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.domain.order.execution.port.OrderIdempotencyGuard
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Redis SETNX 기반 주문 멱등성 가드 (ADR 0068 §14-1 + 의제 H-1 LOCK).
 *
 * §14-2 step 2 — duplicate `signalId:userId` 조합 차단.
 * key 컨벤션: caller 책임 (보통 "idem:order:{signalId}:{userId}").
 *
 * Redis 장애 시 — §14-4 risk 매트릭스: 즉시 false 반환 (= duplicate 취급) → caller가 REJECTED 결정.
 * 가용성보다 자본 보호 우선 LOCK.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class OrderIdempotencyGuardImpl(
    private val redisTemplate: StringRedisTemplate,
) : OrderIdempotencyGuard {
    private val log = LoggerFactory.getLogger(OrderIdempotencyGuardImpl::class.java)

    override fun acquire(
        key: String,
        ttlSec: Long,
    ): Boolean =
        try {
            redisTemplate
                .opsForValue()
                .setIfAbsent(key, "1", Duration.ofSeconds(ttlSec))
                ?: false
        } catch (e: Exception) {
            log.error(
                "OrderIdempotencyGuardImpl Redis SETNX 실패 — 자본 보호 LOCK: key={} err={}",
                key,
                e.message,
            )
            // §14-4 risk 매트릭스 LOCK — Redis 장애 시 가용성보다 자본 보호 우선,
            // false 반환 → caller가 REJECTED + audit REDIS_DOWN_FALLBACK 결정.
            false
        }
}
