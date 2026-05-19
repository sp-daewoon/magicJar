package kr.spdaewoon.magicjar.api.adapter.kis

import kr.spdaewoon.magicjar.api.infrastructure.kis.KisTokenCacheHelper
import kr.spdaewoon.magicjar.domain.market.PriceMetrics
import kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
 * 토큰 발급: [KisTokenCacheHelper] 위임 — `kis:token:{userId}:{accountType}` 단일 prefix LOCK (T-1).
 * - 기존 `access_token:` prefix 폐기 (T-1 502 root cause fix)
 * - ADR 0038 분산락 + ADR 정책 expires_in 동적 TTL (T-3)
 *
 * timeout: 3s (dispatch-spec §3-2 정합).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class KisRestPriceMetricsAdapter(
    private val webClient: WebClient,
    private val tokenHelper: KisTokenCacheHelper,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Suppress("UNUSED_PARAMETER")
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    kisBaseVps: String,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
) : KisRestPriceMetricsPort {
    private val log = LoggerFactory.getLogger(KisRestPriceMetricsAdapter::class.java)

    override fun fetchPriceMetrics(
        userId: String,
        code: String,
    ): PriceMetrics {
        val baseUrl = if (magicjarMode == "REAL") kisBaseProd else kisBaseProd // 조회는 항상 실전 endpoint
        // PR-19 hotfix — appkey/appsecret 헤더 누락으로 KIS EGW00104 "AppSecret은 필수입니다" 발생.
        // KIS quotation endpoint는 Bearer token 외에 appkey + appsecret 헤더도 필수 (KIS-first 정합).
        // T-4 — KisTokenCacheHelper 위임 (kis:token: 단일 prefix + 분산락 + expires_in 동적 TTL)
        val (token, appKey, secretKey) = tokenHelper.resolveTokenAndKeys(userId, AccountType.REAL)

        return try {
            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .get()
                    .uri(
                        "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price" +
                            "?fid_cond_mrkt_div_code=J&fid_input_iscd=$code",
                    ).header("authorization", "Bearer $token")
                    .header("appkey", appKey)
                    .header("appsecret", secretKey)
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
                currentPrice = output["stck_prpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                previousClose = output["stck_sdpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                priceChange = output["prdy_vrss"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                changeRate = output["prdy_ctrt"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                changeSign = output["prdy_vrss_sign"]?.toString()?.ifBlank { null },
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
}
