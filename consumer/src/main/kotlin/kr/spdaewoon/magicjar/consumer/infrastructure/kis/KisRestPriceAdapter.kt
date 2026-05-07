package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration

/**
 * KIS REST 현재가 단건 조회 어댑터 — polling fallback 및 Redis warm-up 용도.
 *
 * KIS-first 출처:
 * - TR_ID: FHKST01010100
 * - endpoint: GET /uapi/domestic-stock/v1/quotations/inquire-price
 * - param: fid_cond_mrkt_div_code=J, fid_input_iscd={code}
 * - KIS API reference: docs/operations/kis-api-reference.md §3 #1
 *
 * 또한 KisRestPort.fetchCandles 구현도 담당 (기존 RealKisRestAdapter 역할 consumer 내 추가).
 * consumer 모듈은 api 모듈 어댑터에 의존할 수 없으므로 consumer 자체 구현.
 */
@Component("consumerKisRestPriceAdapter")
class KisRestPriceAdapter(
    private val webClient: WebClient,
    private val kisAuthAdapter: KisAuthAdapter,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) : KisRestPort {
    private val log = LoggerFactory.getLogger(KisRestPriceAdapter::class.java)

    /**
     * KIS REST 현재가 단건 조회 (FHKST01010100 inquire-price).
     *
     * KIS-first 출처: kis-api-reference.md §3 #1, external-api-policy.md §2-4
     * Header: authorization (Bearer {access_token}), appkey, appsecret, tr_id=FHKST01010100, custtype=P
     * Response: output.stck_prpr (현재가), output.acml_vol (누적거래량)
     *
     * timeout: 3s (dispatch-spec §3-2-C 정합)
     */
    override fun fetchPrice(
        userId: String,
        code: String,
    ): TickEvent {
        val accountType = AccountType.REAL
        val baseUrl = kisBaseProd
        val token = kisAuthAdapter.getAccessToken(userId, accountType)

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

            val priceStr = output["stck_prpr"]?.toString() ?: "0"
            val volumeStr = output["acml_vol"]?.toString() ?: "0"

            TickEvent(
                code = code,
                price = BigDecimal(priceStr.ifBlank { "0" }),
                volume = volumeStr.ifBlank { "0" }.toLongOrNull() ?: 0L,
                ts = Clock.System.now(),
                source = "polling",
                stale = false,
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
     * KIS REST 기간별 캔들 조회 (FHKST03010100 inquire-daily-itemchartprice).
     * consumer 모듈용 — api 모듈 RealKisRestAdapter와 독립적으로 구현.
     * 1차 MVP: 기본 구현 (1wk·1mo·1y 백필 용도는 backend-core PR-9a에서 구현).
     */
    override fun fetchCandles(
        code: String,
        interval: CandleInterval,
        count: Int,
    ): List<Candle> {
        // 1차 MVP: consumer 내 캔들 REST 백필은 backend-core PR-9a KisRestCandleAdapter 담당
        // 본 어댑터는 fetchPrice만 사용 — fetchCandles는 UnsupportedOperation (consumer 단독)
        log.warn("KisRestPriceAdapter.fetchCandles: consumer 모듈은 fetchPrice 전용 — fetchCandles는 backend-core PR-9a 담당")
        return emptyList()
    }

    companion object {
        fun accountTypeForMode(mode: String): AccountType = if (mode == "REAL") AccountType.REAL else AccountType.MOCK
    }
}
