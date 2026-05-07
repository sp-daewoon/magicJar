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
 * KIS WebSocket approval_key 발급 어댑터 (ADR 0038 정합 — Redis cache + 분산락).
 *
 * KIS-first 출처: open-trading-api examples_user/kis_auth.py
 * - endpoint: POST /oauth2/Approval (access_token /oauth2/tokenP 와 별도)
 * - request body: {"grant_type": "client_credentials", "appkey": "...", "secretkey": "..."}
 * - response: {"approval_key": "..."}
 *
 * Redis:
 * - cache key: `kis:approval:{userId}:{accountType}` TTL 23h
 * - 분산락: `kis:approval:lock:{userId}:{accountType}` SETNX TTL 30s
 */
@Component
class KisApprovalAdapter(
    private val webClient: WebClient,
    private val redisTemplate: StringRedisTemplate,
    private val secretCipher: SecretCipher,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) {
    private val log = LoggerFactory.getLogger(KisApprovalAdapter::class.java)

    companion object {
        private const val APPROVAL_TTL_HOURS = 23L
        private const val LOCK_TTL_SECONDS = 30L
        private const val LOCK_WAIT_MS = 500L
        private const val LOCK_MAX_RETRIES = 60 // 30s / 0.5s
    }

    /**
     * KIS WebSocket approval_key 반환 (Redis cache + 분산락).
     *
     * 1) Redis hit → 반환
     * 2) miss → 분산락 획득 시도 (SETNX 30s)
     *    - 획득 성공: POST /oauth2/Approval → cache 저장 → unlock
     *    - 획득 실패: spinWait 후 cache 재조회 → 없으면 직접 발급
     *
     * KIS-first: endpoint는 /oauth2/Approval (tokenP가 아님).
     * open-trading-api kis_auth.py auth_ws() 함수 기반.
     */
    fun getApprovalKey(
        userId: String,
        accountType: AccountType,
    ): String {
        val cacheKey = "kis:approval:$userId:${accountType.name}"
        val lockKey = "kis:approval:lock:$userId:${accountType.name}"

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

                val key = issueApprovalKey(userId, accountType)
                redisTemplate
                    .opsForValue()
                    .set(cacheKey, key, Duration.ofHours(APPROVAL_TTL_HOURS))
                log.info(
                    "KIS approval_key 발급 완료: user={} type={}",
                    maskUserId(userId),
                    accountType.name,
                )
                key
            } finally {
                redisTemplate.delete(lockKey)
            }
        } else {
            log.debug(
                "KIS approval_key 분산락 대기: user={} type={}",
                maskUserId(userId),
                accountType.name,
            )
            for (i in 0 until LOCK_MAX_RETRIES) {
                Thread.sleep(LOCK_WAIT_MS)
                redisTemplate.opsForValue().get(cacheKey)?.let { return it }
            }
            // 30s 대기 후에도 없으면 직접 발급 (단일 JVM fallback)
            log.warn("KIS approval_key 분산락 30s 타임아웃 — 직접 발급")
            issueApprovalKey(userId, accountType).also { key ->
                redisTemplate
                    .opsForValue()
                    .set(cacheKey, key, Duration.ofHours(APPROVAL_TTL_HOURS))
            }
        }
    }

    /**
     * KIS /oauth2/Approval 직접 호출.
     *
     * KIS-first 출처: open-trading-api kis_auth.py auth_ws():
     *   url = f"{_cfg[svr]}/oauth2/Approval"
     *   p = {"grant_type": "client_credentials", "appkey": ..., "secretkey": ...}
     *   res = requests.post(url, data=json.dumps(p), headers=_getBaseHeader())
     *   approval_key = res.json()["approval_key"]
     */
    private fun issueApprovalKey(
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

            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .post()
                    .uri("$baseUrl/oauth2/Approval")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(
                        mapOf(
                            "grant_type" to "client_credentials",
                            "appkey" to appKeyStr,
                            "secretkey" to secretKeyStr,
                        ),
                    ).retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .block() as? Map<String, Any>
                    ?: error("KIS /oauth2/Approval 응답 없음")

            response["approval_key"]?.toString()
                ?: error("KIS /oauth2/Approval 응답에 approval_key 없음")
        } catch (e: WebClientResponseException) {
            log.error(
                "KIS approval_key 발급 실패 (인증 실패 — 재시도 금지): user={} status={} body={}",
                maskUserId(userId),
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            // 인증 실패(4xx)는 즉시 실패 — 재시도 금지 (ADR 0038 §2-1)
            throw e
        } finally {
            // 메모리 zero-fill (ADR 0038 §5-3 의무)
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
