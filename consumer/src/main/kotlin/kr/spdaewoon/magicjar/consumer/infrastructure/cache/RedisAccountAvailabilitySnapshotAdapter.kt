package kr.spdaewoon.magicjar.consumer.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.domain.account.port.AccountAvailabilitySnapshot
import kr.spdaewoon.magicjar.domain.account.port.AccountAvailabilitySnapshotPort
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 계좌 availability snapshot Redis 저장소.
 *
 * key: `account:availability:{mode}:{userId}`.
 * value: [AccountAvailabilitySnapshot] JSON.
 *
 * 주문 hot path 는 [read]만 수행한다. KIS 조회/refresh 는 별도 worker 가 수행하고 [putIfNewer]로 LWW 저장한다.
 */
@Component
class RedisAccountAvailabilitySnapshotAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.kis.account-availability.snapshot-ttl-seconds:900}")
    private val ttlSeconds: Long = 900,
) : AccountAvailabilitySnapshotPort {
    private val log = LoggerFactory.getLogger(RedisAccountAvailabilitySnapshotAdapter::class.java)

    companion object {
        private const val KEY_PREFIX = "account:availability"
    }

    override fun read(
        userId: String,
        mode: TradingMode,
    ): AccountAvailabilitySnapshot? {
        val key = key(mode, userId)
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return try {
            objectMapper.readValue<AccountAvailabilitySnapshot>(json)
        } catch (e: Exception) {
            log.warn(
                "account availability snapshot 역직렬화 실패 — cache miss 처리: key={} err={}",
                maskKey(key),
                e.message,
            )
            null
        }
    }

    override fun putIfNewer(snapshot: AccountAvailabilitySnapshot): Boolean {
        val key = key(snapshot.mode, snapshot.userId)
        val current = read(snapshot.userId, snapshot.mode)
        if (current != null && current.versionMillis > snapshot.versionMillis) {
            log.debug(
                "account availability snapshot LWW skip: key={} currentVersion={} incomingVersion={}",
                maskKey(key),
                current.versionMillis,
                snapshot.versionMillis,
            )
            return false
        }

        redisTemplate
            .opsForValue()
            .set(key, objectMapper.writeValueAsString(snapshot), Duration.ofSeconds(ttlSeconds))
        return true
    }

    internal fun key(
        mode: TradingMode,
        userId: String,
    ): String = "$KEY_PREFIX:${mode.name}:$userId"

    private fun maskKey(key: String): String =
        key
            .split(":")
            .mapIndexed { index, part -> if (index == 3 && part.length >= 4) part.take(4) + "****" else part }
            .joinToString(":")
}
