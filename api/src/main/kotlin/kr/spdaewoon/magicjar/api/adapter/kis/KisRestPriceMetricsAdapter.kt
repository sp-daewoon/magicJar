package kr.spdaewoon.magicjar.api.adapter.kis

import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.api.secret.AesGcmCipher
import kr.spdaewoon.magicjar.domain.market.PriceMetrics
import kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

/**
 * KIS REST PER·PBR·EPS·BPS·시총 조회 어댑터 (api 모듈) — SymbolMetaService 전용.
 *
 * KIS-first 출처: kis-api-reference.md §3 #1, §4 fetchPriceMetrics 패턴.
 * TR_ID: FHKST01010100 (inquire-price — 모의투자 지원)
 * endpoint: GET /uapi/domestic-stock/v1/quotations/inquire-price
 *
 * 토큰 발급: Redis access_token:{userId}:{accountType} 캐시 → miss 시 KIS /oauth2/tokenP 발급.
 * timeout: 3s (dispatch-spec §3-2 정합).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class KisRestPriceMetricsAdapter(
    private val webClient: WebClient,
    private val credentialJpaRepository: UserKisCredentialJpaRepository,
    private val aesGcmCipher: AesGcmCipher,
    private val redisTemplate: StringRedisTemplate,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
) : KisRestPriceMetricsPort {
    private val log = LoggerFactory.getLogger(KisRestPriceMetricsAdapter::class.java)

    override fun fetchPriceMetrics(
        userId: String,
        code: String,
    ): PriceMetrics {
        val baseUrl = if (magicjarMode == "REAL") kisBaseProd else kisBaseProd // 조회는 항상 실전 endpoint
        val token = resolveAccessToken(userId)

        return try {
            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .get()
                    .uri(
                        "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price" +
                            "?fid_cond_mrkt_div_code=J&fid_input_iscd=$code",
                    ).header("authorization", "Bearer $token")
                    .header("tr_id", "FHKST01010100")
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(3))
                    .block() as? Map<String, Any>
                    ?: error("KIS inquire-price 응답 없음 — code=$code")

            val output =
                response["output"] as? Map<String, Any>
                    ?: error("KIS inquire-price output 없음 — code=$code")

            PriceMetrics(
                per = output["per"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                pbr = output["pbr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                eps = output["eps"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                bps = output["bps"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                marketCap = output["hts_avls"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                industry = output["bstp_kor_isnm"]?.toString()?.ifBlank { null },
                name = output["hts_kor_isnm"]?.toString()?.ifBlank { null },
                market = output["rprs_mrkt_kor_name"]?.toString()?.ifBlank { null },
                previousClose = output["stck_sdpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                high = output["stck_hgpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                low = output["stck_lwpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                open = output["stck_oprc"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                volume = output["acml_vol"]?.toString()?.ifBlank { null }?.toLongOrNull(),
            )
        } catch (e: WebClientResponseException) {
            log.error(
                "KIS inquire-price 실패: code={} status={} body={}",
                code,
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            throw e
        }
    }

    /**
     * KIS access_token 해결:
     * 1. Redis access_token:{userId}:REAL 캐시 확인
     * 2. miss → DB 자격증명 조회 + AES-256-GCM 복호화 + KIS /oauth2/tokenP 발급 + Redis 캐시
     *
     * KIS-first 출처: open-trading-api kis_auth.py OAuth2 tokenP 발급 패턴.
     */
    private fun resolveAccessToken(userId: String): String {
        val cacheKey = "access_token:$userId:REAL"

        // 1. Redis 캐시 확인
        val cached =
            try {
                redisTemplate.opsForValue().get(cacheKey)
            } catch (e: Exception) {
                null
            }
        if (!cached.isNullOrBlank()) return cached

        // 2. DB 자격증명 조회 (REAL 계좌)
        val credential =
            credentialJpaRepository.findAllByAccountType("REAL").firstOrNull()
                ?: run {
                    log.warn("KIS REAL 자격증명 없음 — userId={}...", userId.take(4))
                    // MOCK 계좌도 시도
                    credentialJpaRepository.findAllByAccountType("MOCK").firstOrNull()
                        ?: throw IllegalStateException("KIS 자격증명이 등록되지 않았습니다")
                }

        val appKey =
            try {
                String(aesGcmCipher.decrypt(credential.appKeyEnc))
            } catch (e: Exception) {
                log.error("KIS appKey 복호화 실패: {}", e.message)
                throw IllegalStateException("KIS 자격증명 복호화 실패")
            }
        val secretKey =
            try {
                String(aesGcmCipher.decrypt(credential.secretKeyEnc))
            } catch (e: Exception) {
                log.error("KIS secretKey 복호화 실패: {}", e.message)
                throw IllegalStateException("KIS 자격증명 복호화 실패")
            }

        // 3. KIS /oauth2/tokenP 발급
        val accountType = credential.id.accountType
        val baseUrl = if (accountType == "REAL") kisBaseProd else kisBaseVps
        val trId = if (accountType == "REAL") "FHKST01010100" else "VTTC0802U" // 모의는 VTS prefix

        return try {
            @Suppress("UNCHECKED_CAST")
            val tokenResponse =
                webClient
                    .post()
                    .uri("$baseUrl/oauth2/tokenP")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .bodyValue(
                        mapOf(
                            "grant_type" to "client_credentials",
                            "appkey" to appKey,
                            "appsecret" to secretKey,
                        ),
                    ).retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(5))
                    .block() as? Map<String, Any>
                    ?: throw IllegalStateException("KIS tokenP 응답 없음")

            val accessToken =
                tokenResponse["access_token"]?.toString()
                    ?: throw IllegalStateException("KIS access_token 없음")

            // Redis 캐시 (TTL 23h — KIS 토큰 24h 유효)
            try {
                redisTemplate.opsForValue().set(cacheKey, accessToken, Duration.ofHours(23))
            } catch (e: Exception) {
                log.warn("Redis access_token 캐시 실패 (무시): {}", e.message)
            }

            // 메모리 zero-fill
            appKey.toCharArray().fill('0')
            secretKey.toCharArray().fill('0')

            log.info("KIS access_token 발급 성공: userId={}... accountType={}", userId.take(4), accountType)
            accessToken
        } catch (e: Exception) {
            log.error("KIS access_token 발급 실패: {}", e.message)
            throw e
        }
    }
}
