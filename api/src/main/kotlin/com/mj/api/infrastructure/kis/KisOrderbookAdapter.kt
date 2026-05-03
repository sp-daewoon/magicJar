package com.mj.api.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.market.Orderbook
import com.mj.domain.market.OrderbookLevel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * KIS 호가창 REST 어댑터.
 *
 * 엔드포인트: GET /uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn
 * TR_ID: FHKST01010200
 *
 * MCP + 직접 호출로 실측 검증 완료 (2026-05-04, 005930 기준):
 * - output1: askp1~10(매도호가), bidp1~10(매수호가)
 *            askp_rsqn1~10(매도잔량), bidp_rsqn1~10(매수잔량)
 *            askp_rsqn_icdc1~10(매도잔량증감), bidp_rsqn_icdc1~10(매수잔량증감)
 *            total_askp_rsqn(총매도잔량), total_bidp_rsqn(총매수잔량)
 * - output2: stck_prpr(현재가), antc_cnpr(예상체결가), antc_vol(예상체결수량)
 *
 * 401 시 토큰 재발급 1회 재시도. KIS 키 미설정 시 null 반환 (graceful degradation).
 */
@Component
class KisOrderbookAdapter(
    @Value("\${magicjar.kis.base-url:https://openapivts.koreainvestment.com:29443}")
    private val baseUrl: String,
    @Value("\${magicjar.kis.app-key:}")
    private val appKey: String,
    @Value("\${magicjar.kis.app-secret:}")
    private val appSecret: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val kst = ZoneId.of("Asia/Seoul")
    private val timeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    private val restClient: RestClient by lazy {
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()
    }

    @Volatile
    private var cachedToken: String? = null

    /**
     * 종목 호가창 조회 (inquire-asking-price-exp-ccn / FHKST01010200).
     * KIS 키 미설정 또는 API 실패 시 null 반환.
     */
    fun fetchOrderbook(code: String): Orderbook? {
        if (appKey.isBlank() || appSecret.isBlank()) {
            log.debug("[kis-orderbook] appKey 미설정 — fetchOrderbook 건너뜀 code={}", code)
            return null
        }

        return try {
            if (cachedToken == null) cachedToken = issueToken() ?: return null
            callOrderbook(code)
        } catch (ex: KisOrderbookException) {
            if (ex.statusCode == 401) {
                log.warn("[kis-orderbook] 401 — 토큰 재발급 후 1회 재시도 code={}", code)
                cachedToken = issueToken() ?: return null
                runCatching { callOrderbook(code) }.getOrElse {
                    log.warn("[kis-orderbook] 재시도 실패 code={} error={}", code, it.message)
                    null
                }
            } else {
                log.warn("[kis-orderbook] fetchOrderbook 실패 code={} error={}", code, ex.message)
                null
            }
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                cachedToken = issueToken() ?: return null
                runCatching { callOrderbook(code) }.getOrElse { null }
            } else {
                log.warn("[kis-orderbook] HTTP 오류 code={} status={}", code, ex.statusCode)
                null
            }
        } catch (ex: Exception) {
            log.warn("[kis-orderbook] fetchOrderbook 실패 code={} error={}", code, ex.message)
            null
        }
    }

    private fun callOrderbook(code: String): Orderbook? {
        log.info("[kis-orderbook] FHKST01010200 호출 code={}", code)
        val response =
            restClient
                .get()
                .uri { b ->
                    b
                        .path("/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", code)
                        .build()
                }.headers { h ->
                    h["authorization"] = "Bearer ${cachedToken!!}"
                    h["appkey"] = appKey
                    h["appsecret"] = appSecret
                    h["tr_id"] = "FHKST01010200"
                    h["custtype"] = "P"
                }.retrieve()
                .onStatus(HttpStatusCode::isError) { _, resp ->
                    if (resp.statusCode == HttpStatus.UNAUTHORIZED) {
                        throw KisOrderbookException("401", 401)
                    }
                    log.warn("[kis-orderbook] HTTP 오류 code={} status={}", code, resp.statusCode)
                    throw KisOrderbookException("KIS inquire-asking-price-exp-ccn 오류: ${resp.statusCode}", resp.statusCode.value())
                }.body(AskingPriceResponse::class.java)
                ?: return null

        log.info("[kis-orderbook] FHKST01010200 응답 code={} rt_cd={}", code, response.rtCd)

        if (response.rtCd != "0") {
            log.warn("[kis-orderbook] rt_cd 비정상 code={} rt_cd={} msg={}", code, response.rtCd, response.msg1)
            return null
        }

        return response.toOrderbook(code, kst, timeFormatter)
    }

    private fun issueToken(): String? =
        try {
            log.info("[kis-orderbook] 토큰 발급 요청 baseUrl={}", baseUrl)
            val resp =
                restClient
                    .post()
                    .uri("/oauth2/tokenP")
                    .body(TokenRequest(appkey = appKey, appsecret = appSecret))
                    .retrieve()
                    .onStatus(org.springframework.http.HttpStatusCode::isError) { _, r ->
                        log.warn("[kis-orderbook] 토큰 발급 HTTP 오류 status={}", r.statusCode)
                        throw KisOrderbookException("token issue failed: ${r.statusCode}", r.statusCode.value())
                    }.body(TokenResponse::class.java)
            if (resp?.accessToken.isNullOrBlank()) {
                log.warn("[kis-orderbook] 토큰 발급 응답 access_token 비어있음")
                null
            } else {
                resp!!.accessToken
            }
        } catch (ex: Exception) {
            log.warn("[kis-orderbook] 토큰 발급 실패 error={}", ex.message)
            null
        }

    // ── 내부 예외 ─────────────────────────────────────────────────────────────

    private class KisOrderbookException(
        message: String,
        val statusCode: Int = 0,
    ) : RuntimeException(message)

    // ── 내부 DTO ──────────────────────────────────────────────────────────────

    private data class TokenRequest(
        val grant_type: String = "client_credentials",
        val appkey: String,
        val appsecret: String,
    )

    private data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String = "",
    )

    /**
     * FHKST01010200 응답 (inquire-asking-price-exp-ccn).
     *
     * output1: 매도/매수 10단 호가·잔량·잔량증감 + 총잔량
     * output2: 현재가·예상체결가·예상체결수량
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AskingPriceResponse(
        @JsonProperty("rt_cd") val rtCd: String = "",
        @JsonProperty("msg_cd") val msgCd: String = "",
        @JsonProperty("msg1") val msg1: String = "",
        @JsonProperty("output1") val output1: AskingPriceOutput1 = AskingPriceOutput1(),
        @JsonProperty("output2") val output2: AskingPriceOutput2 = AskingPriceOutput2(),
    ) {
        fun toOrderbook(
            code: String,
            kst: ZoneId,
            fmt: DateTimeFormatter,
        ): Orderbook {
            val o1 = output1
            val o2 = output2

            // 호가 기준 시각: HHmmss → Instant (오늘 KST 기준)
            val acceptedAt =
                runCatching {
                    val t = LocalTime.parse(o1.asprAcptHour.padStart(6, '0'), fmt)
                    LocalDate
                        .now(kst)
                        .atTime(t)
                        .atZone(kst)
                        .toInstant()
                }.getOrElse { Instant.now() }

            // 매도 호가 10단 (askp1~10, askp_rsqn1~10, askp_rsqn_icdc1~10)
            val askLevels =
                (1..10).mapNotNull { i ->
                    val price = o1.askPrice(i).parseBigDecimal() ?: return@mapNotNull null
                    if (price <= BigDecimal.ZERO) return@mapNotNull null
                    OrderbookLevel(
                        price = price,
                        qty = o1.askRsqn(i).parseLong() ?: 0L,
                        qtyChange = o1.askRsqnIcdc(i).parseLong() ?: 0L,
                    )
                }

            // 매수 호가 10단 (bidp1~10, bidp_rsqn1~10, bidp_rsqn_icdc1~10)
            val bidLevels =
                (1..10).mapNotNull { i ->
                    val price = o1.bidPrice(i).parseBigDecimal() ?: return@mapNotNull null
                    if (price <= BigDecimal.ZERO) return@mapNotNull null
                    OrderbookLevel(
                        price = price,
                        qty = o1.bidRsqn(i).parseLong() ?: 0L,
                        qtyChange = o1.bidRsqnIcdc(i).parseLong() ?: 0L,
                    )
                }

            return Orderbook(
                code = code,
                acceptedAt = acceptedAt,
                askLevels = askLevels,
                bidLevels = bidLevels,
                totalAskQty = o1.totalAskpRsqn.parseLong() ?: 0L,
                totalBidQty = o1.totalBidpRsqn.parseLong() ?: 0L,
                currentPrice = o2.stckPrpr.parseBigDecimal(),
                anticipatedPrice = o2.antcCnpr.parseBigDecimal(),
                anticipatedVolume = o2.antcVol.parseLong(),
            )
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AskingPriceOutput1(
        /** 호가 접수 시각 (HHmmss) */
        @JsonProperty("aspr_acpt_hour") val asprAcptHour: String = "",
        // 매도 호가 1~10
        @JsonProperty("askp1") val askp1: String = "",
        @JsonProperty("askp2") val askp2: String = "",
        @JsonProperty("askp3") val askp3: String = "",
        @JsonProperty("askp4") val askp4: String = "",
        @JsonProperty("askp5") val askp5: String = "",
        @JsonProperty("askp6") val askp6: String = "",
        @JsonProperty("askp7") val askp7: String = "",
        @JsonProperty("askp8") val askp8: String = "",
        @JsonProperty("askp9") val askp9: String = "",
        @JsonProperty("askp10") val askp10: String = "",
        // 매수 호가 1~10
        @JsonProperty("bidp1") val bidp1: String = "",
        @JsonProperty("bidp2") val bidp2: String = "",
        @JsonProperty("bidp3") val bidp3: String = "",
        @JsonProperty("bidp4") val bidp4: String = "",
        @JsonProperty("bidp5") val bidp5: String = "",
        @JsonProperty("bidp6") val bidp6: String = "",
        @JsonProperty("bidp7") val bidp7: String = "",
        @JsonProperty("bidp8") val bidp8: String = "",
        @JsonProperty("bidp9") val bidp9: String = "",
        @JsonProperty("bidp10") val bidp10: String = "",
        // 매도 잔량 1~10
        @JsonProperty("askp_rsqn1") val askpRsqn1: String = "",
        @JsonProperty("askp_rsqn2") val askpRsqn2: String = "",
        @JsonProperty("askp_rsqn3") val askpRsqn3: String = "",
        @JsonProperty("askp_rsqn4") val askpRsqn4: String = "",
        @JsonProperty("askp_rsqn5") val askpRsqn5: String = "",
        @JsonProperty("askp_rsqn6") val askpRsqn6: String = "",
        @JsonProperty("askp_rsqn7") val askpRsqn7: String = "",
        @JsonProperty("askp_rsqn8") val askpRsqn8: String = "",
        @JsonProperty("askp_rsqn9") val askpRsqn9: String = "",
        @JsonProperty("askp_rsqn10") val askpRsqn10: String = "",
        // 매수 잔량 1~10
        @JsonProperty("bidp_rsqn1") val bidpRsqn1: String = "",
        @JsonProperty("bidp_rsqn2") val bidpRsqn2: String = "",
        @JsonProperty("bidp_rsqn3") val bidpRsqn3: String = "",
        @JsonProperty("bidp_rsqn4") val bidpRsqn4: String = "",
        @JsonProperty("bidp_rsqn5") val bidpRsqn5: String = "",
        @JsonProperty("bidp_rsqn6") val bidpRsqn6: String = "",
        @JsonProperty("bidp_rsqn7") val bidpRsqn7: String = "",
        @JsonProperty("bidp_rsqn8") val bidpRsqn8: String = "",
        @JsonProperty("bidp_rsqn9") val bidpRsqn9: String = "",
        @JsonProperty("bidp_rsqn10") val bidpRsqn10: String = "",
        // 매도 잔량 증감 1~10
        @JsonProperty("askp_rsqn_icdc1") val askpRsqnIcdc1: String = "",
        @JsonProperty("askp_rsqn_icdc2") val askpRsqnIcdc2: String = "",
        @JsonProperty("askp_rsqn_icdc3") val askpRsqnIcdc3: String = "",
        @JsonProperty("askp_rsqn_icdc4") val askpRsqnIcdc4: String = "",
        @JsonProperty("askp_rsqn_icdc5") val askpRsqnIcdc5: String = "",
        @JsonProperty("askp_rsqn_icdc6") val askpRsqnIcdc6: String = "",
        @JsonProperty("askp_rsqn_icdc7") val askpRsqnIcdc7: String = "",
        @JsonProperty("askp_rsqn_icdc8") val askpRsqnIcdc8: String = "",
        @JsonProperty("askp_rsqn_icdc9") val askpRsqnIcdc9: String = "",
        @JsonProperty("askp_rsqn_icdc10") val askpRsqnIcdc10: String = "",
        // 매수 잔량 증감 1~10
        @JsonProperty("bidp_rsqn_icdc1") val bidpRsqnIcdc1: String = "",
        @JsonProperty("bidp_rsqn_icdc2") val bidpRsqnIcdc2: String = "",
        @JsonProperty("bidp_rsqn_icdc3") val bidpRsqnIcdc3: String = "",
        @JsonProperty("bidp_rsqn_icdc4") val bidpRsqnIcdc4: String = "",
        @JsonProperty("bidp_rsqn_icdc5") val bidpRsqnIcdc5: String = "",
        @JsonProperty("bidp_rsqn_icdc6") val bidpRsqnIcdc6: String = "",
        @JsonProperty("bidp_rsqn_icdc7") val bidpRsqnIcdc7: String = "",
        @JsonProperty("bidp_rsqn_icdc8") val bidpRsqnIcdc8: String = "",
        @JsonProperty("bidp_rsqn_icdc9") val bidpRsqnIcdc9: String = "",
        @JsonProperty("bidp_rsqn_icdc10") val bidpRsqnIcdc10: String = "",
        /** 총 매도 잔량 */
        @JsonProperty("total_askp_rsqn") val totalAskpRsqn: String = "",
        /** 총 매수 잔량 */
        @JsonProperty("total_bidp_rsqn") val totalBidpRsqn: String = "",
    ) {
        fun askPrice(i: Int): String =
            when (i) {
                1 -> askp1
                2 -> askp2
                3 -> askp3
                4 -> askp4
                5 -> askp5
                6 -> askp6
                7 -> askp7
                8 -> askp8
                9 -> askp9
                else -> askp10
            }

        fun bidPrice(i: Int): String =
            when (i) {
                1 -> bidp1
                2 -> bidp2
                3 -> bidp3
                4 -> bidp4
                5 -> bidp5
                6 -> bidp6
                7 -> bidp7
                8 -> bidp8
                9 -> bidp9
                else -> bidp10
            }

        fun askRsqn(i: Int): String =
            when (i) {
                1 -> askpRsqn1
                2 -> askpRsqn2
                3 -> askpRsqn3
                4 -> askpRsqn4
                5 -> askpRsqn5
                6 -> askpRsqn6
                7 -> askpRsqn7
                8 -> askpRsqn8
                9 -> askpRsqn9
                else -> askpRsqn10
            }

        fun bidRsqn(i: Int): String =
            when (i) {
                1 -> bidpRsqn1
                2 -> bidpRsqn2
                3 -> bidpRsqn3
                4 -> bidpRsqn4
                5 -> bidpRsqn5
                6 -> bidpRsqn6
                7 -> bidpRsqn7
                8 -> bidpRsqn8
                9 -> bidpRsqn9
                else -> bidpRsqn10
            }

        fun askRsqnIcdc(i: Int): String =
            when (i) {
                1 -> askpRsqnIcdc1
                2 -> askpRsqnIcdc2
                3 -> askpRsqnIcdc3
                4 -> askpRsqnIcdc4
                5 -> askpRsqnIcdc5
                6 -> askpRsqnIcdc6
                7 -> askpRsqnIcdc7
                8 -> askpRsqnIcdc8
                9 -> askpRsqnIcdc9
                else -> askpRsqnIcdc10
            }

        fun bidRsqnIcdc(i: Int): String =
            when (i) {
                1 -> bidpRsqnIcdc1
                2 -> bidpRsqnIcdc2
                3 -> bidpRsqnIcdc3
                4 -> bidpRsqnIcdc4
                5 -> bidpRsqnIcdc5
                6 -> bidpRsqnIcdc6
                7 -> bidpRsqnIcdc7
                8 -> bidpRsqnIcdc8
                9 -> bidpRsqnIcdc9
                else -> bidpRsqnIcdc10
            }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AskingPriceOutput2(
        /** 현재가 (원) */
        @JsonProperty("stck_prpr") val stckPrpr: String = "",
        /** 예상체결가 (원) */
        @JsonProperty("antc_cnpr") val antcCnpr: String = "",
        /** 예상체결수량 */
        @JsonProperty("antc_vol") val antcVol: String = "",
    )

    companion object {
        private fun String.parseBigDecimal(): BigDecimal? =
            trim().replace(",", "").takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

        private fun String.parseLong(): Long? = trim().replace(",", "").takeIf { it.isNotBlank() }?.toLongOrNull()
    }
}
