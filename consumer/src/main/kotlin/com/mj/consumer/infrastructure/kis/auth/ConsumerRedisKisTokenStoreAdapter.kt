package com.mj.consumer.infrastructure.kis.auth

import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.consumer.infrastructure.kis.KisProperties
import com.mj.domain.market.kis.port.KisAuthScope
import com.mj.domain.market.kis.port.KisTokenStorePort
import org.redisson.api.RTopic
import org.redisson.api.RedissonClient
import org.redisson.client.RedisException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * R3-5c-3 PR5 — KIS REST 토큰 단일 store, Redis 분산 single-flight (consumer 모듈).
 *
 * api / batch 모듈과 동일 Redis 키 schema·RLock·RTopic 정책. consumer는 시스템 키 +
 * **사용자 키** 모두 지원. (api·batch는 시스템만)
 *
 * 사용자 키 path는 본 사이클 시점에는 `KisProperties.appKey/appSecret`로 단일 사용자(시드) 의존.
 * Multi-user 진입은 후속 사이클에서 `KisCredentialProvider.forUser(scope)` 통합 (track1 산출물).
 */
@Component("consumerRedisKisTokenStoreAdapter")
@Profile("dev | prd")
@ConditionalOnProperty(
    prefix = "magicjar.kis.redis-token",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class ConsumerRedisKisTokenStoreAdapter(
    private val redissonClient: RedissonClient,
    private val metrics: KisAuthMetrics,
    private val properties: KisProperties,
    @Value("\${magicjar.kis.token.refresh-margin-seconds:3600}")
    private val refreshMarginSeconds: Long,
    @Value("\${magicjar.kis.token.lock-wait-time-seconds:10}")
    private val lockWaitTimeSeconds: Long,
    @Value("\${magicjar.kis.token.lock-lease-time-seconds:15}")
    private val lockLeaseTimeSeconds: Long,
    @Value("\${magicjar.kis.token.redis-ttl-hours:25}")
    private val redisTtlHours: Long,
) : KisTokenStorePort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val l1Cache = ConcurrentHashMap<String, AtomicReference<CachedToken?>>()

    private val jvmId: String =
        runCatching { "${InetAddress.getLocalHost().hostName}:${ProcessHandle.current().pid()}" }
            .getOrElse { "consumer:${UUID.randomUUID()}" }

    private val restClient: RestClient by lazy {
        RestClient
            .builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()
    }

    private val invalidateTopic: RTopic by lazy {
        redissonClient.getTopic(INVALIDATE_TOPIC)
    }

    init {
        runCatching {
            invalidateTopic.addListener(String::class.java) { _, scopeId ->
                val key = scopeIdToTokenKey(scopeId)
                if (key != null) {
                    l1Cache[key]?.set(null)
                    log.info("[kis-auth] L1 evicted via RTopic scopeId={}", scopeId)
                }
            }
        }.onFailure {
            log.warn("[kis-auth][redis-down] RTopic subscribe 실패 reason={}", it.javaClass.simpleName)
        }
    }

    override fun currentTokenOrIssue(scope: KisAuthScope): String {
        val tokenKey = scope.tokenKey()
        val now = Instant.now()

        l1Cache[tokenKey]?.get()?.let { if (it.isValid(now, refreshMarginSeconds)) return it.token }

        try {
            val redisHit = readRedisHash(tokenKey)
            if (redisHit != null && redisHit.isValid(now, refreshMarginSeconds)) {
                l1Cache.computeIfAbsent(tokenKey) { AtomicReference() }.set(redisHit)
                metrics.tokenIssued(scope.identifier(), KisAuthMetrics.TokenIssuanceSource.REDIS_HIT)
                return redisHit.token
            }
        } catch (ex: RedisException) {
            return inMemoryFallback(scope, tokenKey, "read")
        } catch (ex: Exception) {
            log.warn("[kis-auth] Redis read 예외 scope={} reason={}", scope.identifier(), ex.javaClass.simpleName)
            return inMemoryFallback(scope, tokenKey, "read")
        }

        val lockKey = scope.lockKey()
        val lock =
            try {
                redissonClient.getLock(lockKey)
            } catch (ex: Exception) {
                log.error(
                    "[kis-auth][redis-down] fallback=in-memory race-risk-high scope={} op=lock-acquire reason={}",
                    scope.identifier(),
                    ex.javaClass.simpleName,
                )
                metrics.redisUnavailable(scope.identifier())
                return inMemoryFallback(scope, tokenKey, "lock-init")
            }

        val lockTimer = metrics.lockWaitTimer(scope.identifier())
        val acquired =
            try {
                lockTimer.recordCallable {
                    lock.tryLock(lockWaitTimeSeconds, lockLeaseTimeSeconds, TimeUnit.SECONDS)
                } ?: false
            } catch (ex: RedisException) {
                log.error(
                    "[kis-auth][redis-down] fallback=in-memory race-risk-high scope={} op=lock-acquire reason={}",
                    scope.identifier(),
                    ex.javaClass.simpleName,
                )
                metrics.redisUnavailable(scope.identifier())
                return inMemoryFallback(scope, tokenKey, "lock")
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ex
            }

        if (!acquired) {
            log.warn(
                "[kis-auth] tryLock timeout scope={} waitTime={}s — fallback=in-memory",
                scope.identifier(),
                lockWaitTimeSeconds,
            )
            return inMemoryFallback(scope, tokenKey, "lock-timeout")
        }

        try {
            val recheck =
                try {
                    readRedisHash(tokenKey)
                } catch (ex: Exception) {
                    null
                }
            if (recheck != null && recheck.isValid(Instant.now(), refreshMarginSeconds)) {
                l1Cache.computeIfAbsent(tokenKey) { AtomicReference() }.set(recheck)
                metrics.tokenIssued(scope.identifier(), KisAuthMetrics.TokenIssuanceSource.REDIS_HIT)
                return recheck.token
            }

            val issued = fetchFromKis(scope)

            try {
                writeRedisHash(tokenKey, issued)
                invalidateTopic.publish(scope.identifier())
            } catch (ex: Exception) {
                log.error(
                    "[kis-auth][redis-down] fallback=in-memory race-risk-high scope={} op=write reason={}",
                    scope.identifier(),
                    ex.javaClass.simpleName,
                )
                metrics.redisUnavailable(scope.identifier())
            }

            l1Cache.computeIfAbsent(tokenKey) { AtomicReference() }.set(issued)
            metrics.tokenIssued(scope.identifier(), KisAuthMetrics.TokenIssuanceSource.FRESH)
            return issued.token
        } finally {
            runCatching { if (lock.isHeldByCurrentThread) lock.unlock() }
                .onFailure { log.warn("[kis-auth] lock unlock 실패 scope={}", scope.identifier()) }
        }
    }

    override fun invalidate(scope: KisAuthScope) {
        val tokenKey = scope.tokenKey()
        l1Cache[tokenKey]?.set(null)
        try {
            redissonClient.getMap<String, String>(tokenKey).delete()
        } catch (ex: Exception) {
            log.error(
                "[kis-auth][redis-down] fallback=in-memory race-risk-high scope={} op=invalidate reason={}",
                scope.identifier(),
                ex.javaClass.simpleName,
            )
            metrics.redisUnavailable(scope.identifier())
        }
        try {
            invalidateTopic.publish(scope.identifier())
        } catch (ex: Exception) {
            log.warn("[kis-auth] RTopic publish 실패 scope={}", scope.identifier())
        }
        metrics.tokenInvalidated(scope.identifier(), KisAuthMetrics.TokenInvalidationReason.MANUAL)
        log.info("[kis-auth] invalidate scope={}", scope.identifier())
    }

    private fun readRedisHash(tokenKey: String): CachedToken? {
        val map = redissonClient.getMap<String, String>(tokenKey)
        if (map.isEmpty()) return null
        val token = map["access_token"] ?: return null
        val expiresAtSec = map["expires_at"]?.toLongOrNull() ?: return null
        return CachedToken(token, Instant.ofEpochSecond(expiresAtSec))
    }

    private fun writeRedisHash(
        tokenKey: String,
        cached: CachedToken,
    ) {
        val map = redissonClient.getMap<String, String>(tokenKey)
        map.putAll(
            mapOf(
                "access_token" to cached.token,
                "expires_at" to cached.expiresAt.epochSecond.toString(),
                "issued_at" to Instant.now().epochSecond.toString(),
                "issuer_jvm" to jvmId,
                "kis_token_type" to "Bearer",
            ),
        )
        map.expire(Duration.ofHours(redisTtlHours))
    }

    private fun inMemoryFallback(
        scope: KisAuthScope,
        tokenKey: String,
        op: String,
    ): String {
        log.error(
            "[kis-auth][redis-down] fallback=in-memory race-risk-high scope={} op={} source=fallback",
            scope.identifier(),
            op,
        )
        metrics.redisUnavailable(scope.identifier())

        val now = Instant.now()
        l1Cache[tokenKey]?.get()?.let {
            if (it.isValid(now, refreshMarginSeconds)) {
                metrics.fallbackUsed(scope.identifier(), KisAuthMetrics.FallbackKind.IN_MEMORY_HIT)
                return it.token
            }
        }

        return synchronized(this) {
            l1Cache[tokenKey]?.get()?.let {
                if (it.isValid(Instant.now(), refreshMarginSeconds)) {
                    metrics.fallbackUsed(scope.identifier(), KisAuthMetrics.FallbackKind.IN_MEMORY_HIT)
                    return@synchronized it.token
                }
            }
            val issued = fetchFromKis(scope)
            l1Cache.computeIfAbsent(tokenKey) { AtomicReference() }.set(issued)
            metrics.fallbackUsed(scope.identifier(), KisAuthMetrics.FallbackKind.IN_MEMORY_FRESH)
            metrics.tokenIssued(scope.identifier(), KisAuthMetrics.TokenIssuanceSource.IN_MEMORY)
            issued.token
        }
    }

    /**
     * 사용자 키 진입은 properties.appKey/appSecret을 그대로 사용 (시드 단일 사용자).
     * Multi-user 진입은 후속 사이클에서 `KisCredentialProvider.forUser(scope)` 통합 (track1).
     */
    private fun fetchFromKis(scope: KisAuthScope): CachedToken {
        check(properties.hasCredentials()) {
            "[kis-auth] KIS 키 미설정 scope=${scope.identifier()}"
        }
        log.info(
            "[kis-auth] issued scope={} source=fresh appKey={}…",
            scope.identifier(),
            (properties.appKey ?: "").take(8),
        )
        val body =
            mapOf(
                "grant_type" to "client_credentials",
                "appkey" to properties.appKey,
                "appsecret" to properties.appSecret,
            )
        val resp =
            restClient
                .post()
                .uri("/oauth2/tokenP")
                .body(body)
                .retrieve()
                .body(TokenResponse::class.java)
                ?: error("[kis-auth] null 응답 scope=${scope.identifier()}")
        val expiresAt = Instant.now().plusSeconds(properties.tokenTtlSeconds + 3600L) // KIS 24h 가정 + 1h 마진
        log.info("[kis-auth] issued scope={} source=fresh expiresAt={}", scope.identifier(), expiresAt)
        return CachedToken(resp.accessToken, expiresAt)
    }

    private fun scopeIdToTokenKey(scopeId: String): String? =
        when {
            scopeId.startsWith("system:") -> "magicjar:kis:token:$scopeId"
            scopeId.startsWith("user:") -> "magicjar:kis:token:$scopeId"
            else -> null
        }

    internal data class CachedToken(
        val token: String,
        val expiresAt: Instant,
    ) {
        fun isValid(
            now: Instant,
            marginSeconds: Long,
        ): Boolean = now.isBefore(expiresAt.minusSeconds(marginSeconds))
    }

    internal data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
    )

    companion object {
        const val INVALIDATE_TOPIC: String = "magicjar:kis:token:invalidated"
    }
}
