package com.mj.api.infrastructure.kis.auth

import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.market.kis.port.KisAuthScope
import com.mj.domain.market.kis.port.KisTokenStorePort
import org.redisson.api.RTopic
import org.redisson.api.RedissonClient
import org.redisson.client.RedisException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
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
 * R3-5c-3 PR3 — KIS REST 토큰 단일 store, Redis 분산 single-flight (api 모듈).
 *
 * spec §6 / §7 / §8 정합:
 *   - Redis HASH (`magicjar:kis:token:system:{mode}`) 영속 + 25h TTL
 *   - Redisson RLock (`magicjar:kis:token:lock:system:{mode}`) — `tryLock(10s, 15s)` + 워치독 비활성
 *   - RTopic (`magicjar:kis:token:invalidated`) broadcast — multi-JVM L1 evict
 *   - L1 in-process AtomicReference + 23h 만료 마진(1h)
 *
 * 동작 흐름 (spec §7-2 double-check):
 *   1. L1 hit + isValid → return
 *   2. Redis HASH read → hit + isValid → L1 fill + return
 *   3. Redis miss → tryLock(10s, 15s)
 *      - acquired → recheck Redis HASH (다른 JVM이 그 사이 발급 가능) → fetch from KIS → write Redis HASH → publish RTopic → L1 fill
 *      - timeout → waitTimeoutFallback (옛 토큰 + 401 trigger)
 *   4. Redis 다운(`RedisException`) → in-memory fallback + ERROR 로그 + `kis_token_redis_unavailable_total` 증분
 *
 * 게이트: `@ConditionalOnProperty(magicjar.kis.redis-token.enabled=true)`. default false (PR2)
 *   → PR5에서 docker-compose env 활성화 + InMemoryKisTokenStoreAdapter 자동 비활성.
 *
 * api 모듈은 시스템 키 prod 모드만 지원. 사용자 키는 consumer 모듈 책임 (PR5).
 */
@Component("apiRedisKisTokenStoreAdapter")
@ConditionalOnProperty(
    prefix = "magicjar.kis.redis-token",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class ApiRedisKisTokenStoreAdapter(
    private val redissonClient: RedissonClient,
    private val metrics: KisAuthMetrics,
    @Value("\${magicjar.kis.base-url.prod:\${spring.kis.base-url:https://openapi.koreainvestment.com:9443}}")
    private val prodBaseUrl: String,
    @Value("\${magicjar.kis.system.app-key.prod:\${spring.kis.app-key:\${magicjar.kis.app-key:}}}")
    private val prodAppKey: String,
    @Value("\${magicjar.kis.system.app-secret.prod:\${spring.kis.app-secret:\${magicjar.kis.app-secret:}}}")
    private val prodAppSecret: String,
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

    /** L1 캐시 — scope.tokenKey() → AtomicReference<CachedToken>. */
    private val l1Cache = ConcurrentHashMap<String, AtomicReference<CachedToken?>>()

    /** 발급한 JVM 식별자 — Redis HASH `issuer_jvm` 필드용. */
    private val jvmId: String =
        runCatching { "${InetAddress.getLocalHost().hostName}:${ProcessHandle.current().pid()}" }
            .getOrElse { "api:${UUID.randomUUID()}" }

    private val restClient: RestClient by lazy {
        RestClient
            .builder()
            .baseUrl(prodBaseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()
    }

    private val invalidateTopic: RTopic by lazy {
        redissonClient.getTopic(INVALIDATE_TOPIC)
    }

    init {
        // RTopic subscribe — 다른 JVM이 invalidate 발행 시 L1 evict
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
        validateScope(scope)
        val tokenKey = scope.tokenKey()
        val now = Instant.now()

        // 1. L1 cache hit
        l1Cache[tokenKey]?.get()?.let { if (it.isValid(now, refreshMarginSeconds)) return it.token }

        // 2. Redis HASH read
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
            // 그 외 Redis 오류 — 동일 fallback
            log.warn("[kis-auth] Redis read 예외 scope={} reason={}", scope.identifier(), ex.javaClass.simpleName)
            return inMemoryFallback(scope, tokenKey, "read")
        }

        // 3. Redis miss → RLock 획득 시도
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
            // double-check — 다른 JVM이 그 사이 발급했을 수 있음
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

            // 실 발급
            val (appKey, appSecret) = credentialsFor(scope)
            val issued = fetchFromKis(scope, appKey, appSecret)

            // Redis HASH write
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
                // L1만 채우고 return
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

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun validateScope(scope: KisAuthScope) {
        when (scope) {
            is KisAuthScope.System ->
                if (scope.mode != "prod") {
                    throw IllegalArgumentException(
                        "[kis-auth] api 모듈은 prod 시스템 키만 지원 (받음: ${scope.identifier()})",
                    )
                }
            is KisAuthScope.User ->
                throw IllegalArgumentException(
                    "[kis-auth] api 모듈은 시스템 키만 지원 (사용자 scope=${scope.identifier()}는 consumer 모듈에서 처리)",
                )
        }
    }

    private fun credentialsFor(scope: KisAuthScope): Pair<String, String> {
        validateScope(scope)
        require(prodAppKey.isNotBlank() && prodAppSecret.isNotBlank()) {
            "[kis-auth] 키 미설정 scope=${scope.identifier()}"
        }
        return prodAppKey to prodAppSecret
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
        // Redis 키 자체 TTL = 25h (KIS 24h + 1h 안전 마진)
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

        // L1 hit → 사용
        val now = Instant.now()
        l1Cache[tokenKey]?.get()?.let {
            if (it.isValid(now, refreshMarginSeconds)) {
                metrics.fallbackUsed(scope.identifier(), KisAuthMetrics.FallbackKind.IN_MEMORY_HIT)
                return it.token
            }
        }

        // L1 miss → synchronized 발급(per-JVM, multi-JVM race 위험 명시)
        return synchronized(this) {
            l1Cache[tokenKey]?.get()?.let {
                if (it.isValid(Instant.now(), refreshMarginSeconds)) {
                    metrics.fallbackUsed(scope.identifier(), KisAuthMetrics.FallbackKind.IN_MEMORY_HIT)
                    return@synchronized it.token
                }
            }
            val (appKey, appSecret) = credentialsFor(scope)
            val issued = fetchFromKis(scope, appKey, appSecret)
            l1Cache.computeIfAbsent(tokenKey) { AtomicReference() }.set(issued)
            metrics.fallbackUsed(scope.identifier(), KisAuthMetrics.FallbackKind.IN_MEMORY_FRESH)
            metrics.tokenIssued(scope.identifier(), KisAuthMetrics.TokenIssuanceSource.IN_MEMORY)
            issued.token
        }
    }

    private fun fetchFromKis(
        scope: KisAuthScope,
        appKey: String,
        appSecret: String,
    ): CachedToken {
        log.info(
            "[kis-auth] issued scope={} source=fresh appKey={}…",
            scope.identifier(),
            appKey.take(8),
        )
        val body =
            mapOf(
                "grant_type" to "client_credentials",
                "appkey" to appKey,
                "appsecret" to appSecret,
            )
        val resp =
            restClient
                .post()
                .uri("/oauth2/tokenP")
                .body(body)
                .retrieve()
                .body(TokenResponse::class.java)
                ?: error("[kis-auth] null 응답 scope=${scope.identifier()}")
        val expiresAt = Instant.now().plusSeconds(86_400L) // KIS 24h 표준
        log.info("[kis-auth] issued scope={} source=fresh expiresAt={}", scope.identifier(), expiresAt)
        return CachedToken(resp.accessToken, expiresAt)
    }

    /** scope identifier → tokenKey 역매핑 (RTopic listener용). */
    private fun scopeIdToTokenKey(scopeId: String): String? {
        // identifier 형식: "system:{mode}" 또는 "user:{userId}:{mode}"
        return when {
            scopeId.startsWith("system:") -> "magicjar:kis:token:$scopeId"
            scopeId.startsWith("user:") -> "magicjar:kis:token:$scopeId"
            else -> null
        }
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
