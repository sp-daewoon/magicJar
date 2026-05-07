package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

/**
 * KIS OAuth 운영용 토큰 어댑터 (ADR 0038 — Redis cache + 분산락).
 * - Redis cache `kis:token:{userId}:{accountType}` TTL 23h
 * - 분산락 `kis:token:lock:{userId}:{accountType}` TTL 30s (SETNX)
 * - 분산락 미획득 시 30s spinWait 후 cache 재조회 (Bootstrap fallback)
 * - PR-6 KisAuthValidator(단발 검증용)와 별개 — 운영용 신규 클래스
 *
 * 복호화 메모리 zero-fill 의무: APP_KEY·SECRET_KEY 사용 후 즉시 fill.
 */
@Component
class KisAuthAdapter(
    private val webClient: WebClient,
    private val redisTemplate: StringRedisTemplate,
    private val secretCipher: SecretCipher,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) {
    private val log = LoggerFactory.getLogger(KisAuthAdapter::class.java)

    companion object {
        private const val TOKEN_TTL_HOURS = 23L
        private const val LOCK_TTL_SECONDS = 30L
        private const val LOCK_WAIT_MS = 500L
        private const val LOCK_MAX_RETRIES = 60 // 30s / 0.5s
    }

    /**
     * KIS 액세스 토큰 반환 (ADR 0038 분산락 + cache).
     * 1) Redis hit → 반환
     * 2) miss → 분산락 획득 시도 (SETNX 30s)
     *    - 획득 성공: KIS /oauth2/tokenP → cache 저장 → unlock
     *    - 획득 실패: spinWait 후 cache 재조회
     */
    fun getAccessToken(
        userId: String,
        accountType: AccountType,
    ): String {
        val cacheKey = "kis:token:$userId:${accountType.name}"
        val lockKey = "kis:token:lock:$userId:${accountType.name}"

        // 1. Redis cache hit
        redisTemplate.opsForValue().get(cacheKey)?.let { return it }

        // 2. 분산락 획득 시도 (SETNX)
        val lockAcquired =
            redisTemplate
                .opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(LOCK_TTL_SECONDS)) == true

        return if (lockAcquired) {
            try {
                // Double-check: 다른 JVM이 이미 발급?
                redisTemplate.opsForValue().get(cacheKey)?.let { return it }

                val token = issueToken(userId, accountType)
                redisTemplate
                    .opsForValue()
                    .set(cacheKey, token, Duration.ofHours(TOKEN_TTL_HOURS))
                log.info(
                    "KIS 토큰 발급 완료: user={} type={}",
                    maskUserId(userId),
                    accountType.name,
                )
                token
            } finally {
                redisTemplate.delete(lockKey)
            }
        } else {
            // 락 미획득 — spinWait 후 cache 재조회
            log.debug("KIS 토큰 발급 락 대기: user={} type={}", maskUserId(userId), accountType.name)
            for (i in 0 until LOCK_MAX_RETRIES) {
                Thread.sleep(LOCK_WAIT_MS)
                redisTemplate.opsForValue().get(cacheKey)?.let { return it }
            }
            // 30s 대기 후에도 없으면 직접 발급 (단일 JVM fallback)
            log.warn("KIS 토큰 분산락 30s 타임아웃 — 직접 발급 (단일 JVM fallback)")
            issueToken(userId, accountType).also { token ->
                redisTemplate
                    .opsForValue()
                    .set(cacheKey, token, Duration.ofHours(TOKEN_TTL_HOURS))
            }
        }
    }

    /**
     * KIS /oauth2/tokenP 호출 (ADR 0038 §2-2).
     * 메모리 zero-fill: APP_KEY·SECRET_KEY 사용 후 Arrays.fill.
     */
    private fun issueToken(
        userId: String,
        accountType: AccountType,
    ): String {
        val cred =
            credentialRepository
                .findAllByAccountType(accountType.name)
                .firstOrNull { it.id.userId == userId }
                ?: error("KIS 자격증명 없음 — userId=$userId type=${accountType.name}")

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)

            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val baseUrl = if (accountType == AccountType.REAL) kisBaseProd else kisBaseVps

            val response =
                webClient
                    .post()
                    .uri("$baseUrl/oauth2/tokenP")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(
                        mapOf(
                            "grant_type" to "client_credentials",
                            "appkey" to appKeyStr,
                            "appsecret" to secretKeyStr,
                        ),
                    ).retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(5))
                    .block() ?: error("KIS tokenP 응답 없음")

            response["access_token"]?.toString()
                ?: error("KIS tokenP 응답에 access_token 없음")
        } catch (e: WebClientResponseException) {
            log.error(
                "KIS tokenP 실패: user={} status={} body={}",
                maskUserId(userId),
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            throw e
        } finally {
            // 메모리 zero-fill (ADR 0038 §5-3 의무)
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
