package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mj.domain.common.Order
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import com.mj.domain.order.KisBalanceSnapshot
import com.mj.domain.order.KisExecutionLine
import com.mj.domain.order.KisOrderResult
import com.mj.domain.order.port.KisOrderPort
import com.mj.domain.user.TradingMode
import com.mj.domain.user.port.KisCredential
import com.mj.domain.user.port.KisCredentialProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * KIS 주문/잔고/체결조회 어댑터. spec §5, §6.1, ADR 0035.
 *
 * T2-T6: raw HTTP 로깅 추가 (debug level, `MAGICJAR_KIS_RAW_HTTP_LOGGING=true` 시 활성).
 *   - request: 메서드·URI·헤더(secret 마스킹)·body
 *   - response: status·body 요약
 *   - 컨벤션 §8: appkey·appsecret·authorization 값 마스킹
 *
 * T2-T7: EGW00202 hotfix
 *   (a) hashkey 헤더 자동 첨부 — `/uapi/hashkey` POST 후 응답 HASH 값을 `hashkey` 헤더로 전달.
 *   (b) Accept·Content-Type 명시 override (RestClient 기본 협상 헤더 방지).
 *   (c) User-Agent 명시 (KIS gateway가 표준 브라우저/서버 UA 기대할 수 있음).
 *   (d) SLL_TYPE 빈 문자열 → BUY 시 제거 (KIS 명세: SELL에만 "01" 전달, BUY는 키 미포함).
 *
 * - mode (MOCK_KIS / REAL_KIS) 별로 별도 KisHttpSession 사용 (서로 다른 baseUrl)
 * - TR_ID는 mode + 메서드 조합으로 결정 (spec §3 D2)
 * - 401 응답 시 KisHttpSession.executeWithRetry로 토큰 재발급+재시도
 * - 비-200 또는 rt_cd!=0 시 KisApiException(category 자동 분류)
 */
@Component
@Profile("dev | prd")
class RealKisOrderAdapter(
    @Qualifier("mockKisHttpSession") private val mockSession: KisHttpSession,
    @Qualifier("realKisHttpSession") private val realSession: KisHttpSession,
    private val credentials: KisCredentialProvider,
    private val hashKeyClient: KisHashKeyClient,
    @Value("\${magicjar.kis.raw-http-logging:false}") private val rawHttpLogging: Boolean,
) : KisOrderPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val yyyymmdd = DateTimeFormatter.ofPattern("yyyyMMdd")

    private fun sessionFor(mode: TradingMode): KisHttpSession =
        when (mode) {
            TradingMode.MOCK_KIS -> mockSession
            TradingMode.REAL_KIS -> realSession
        }

    override fun placeOrder(
        userId: UUID,
        order: Order,
    ): KisOrderResult {
        val cred = credentials.forUser(userId)
        val isMock = cred.tradingMode == TradingMode.MOCK_KIS
        val trId =
            when (order.side) {
                Side.BUY -> if (isMock) "VTTC0012U" else "TTTC0012U"
                Side.SELL -> if (isMock) "VTTC0011U" else "TTTC0011U"
            }
        val ordDvsn =
            when (order.type) {
                OrderType.MARKET -> "01"
                OrderType.LIMIT -> "00"
            }
        // T2-T7 (d): BUY 시 SLL_TYPE 키 미포함, SELL만 "01"
        // KIS 명세: 빈 문자열 필드가 오히려 파싱 오류 유발 가능성 있음
        val body =
            buildMap<String, Any> {
                put("CANO", cred.accountNo)
                put("ACNT_PRDT_CD", cred.accountProductCode)
                put("PDNO", order.code)
                put("ORD_DVSN", ordDvsn)
                put("ORD_QTY", order.qty.toString())
                put("ORD_UNPR", order.price?.toPlainString() ?: "0")
                put("EXCG_ID_DVSN_CD", "KRX")
                if (order.side == Side.SELL) put("SLL_TYPE", "01")
            }
        val output = postWithAuth(cred, trId, "/uapi/domestic-stock/v1/trading/order-cash", body)
        return KisOrderResponseMapper.toOrderResult(output)
    }

    override fun cancelOrder(
        userId: UUID,
        kisOrderNo: String,
        krxFwdgOrdOrgno: String,
        qtyAll: Boolean,
        qty: Int,
    ): KisOrderResult =
        rvseCncl(
            userId,
            kisOrderNo,
            krxFwdgOrdOrgno,
            rvseCnclCode = "02",
            newQty = qty,
            newPrice = BigDecimal.ZERO,
            qtyAll = qtyAll,
        )

    override fun modifyOrder(
        userId: UUID,
        kisOrderNo: String,
        krxFwdgOrdOrgno: String,
        newQty: Int,
        newPrice: BigDecimal,
    ): KisOrderResult =
        rvseCncl(
            userId,
            kisOrderNo,
            krxFwdgOrdOrgno,
            rvseCnclCode = "01",
            newQty = newQty,
            newPrice = newPrice,
            qtyAll = false,
        )

    private fun rvseCncl(
        userId: UUID,
        kisOrderNo: String,
        krxFwdgOrdOrgno: String,
        rvseCnclCode: String,
        newQty: Int,
        newPrice: BigDecimal,
        qtyAll: Boolean,
    ): KisOrderResult {
        val cred = credentials.forUser(userId)
        val isMock = cred.tradingMode == TradingMode.MOCK_KIS
        val trId = if (isMock) "VTTC0013U" else "TTTC0013U"
        val body =
            mapOf(
                "CANO" to cred.accountNo,
                "ACNT_PRDT_CD" to cred.accountProductCode,
                "KRX_FWDG_ORD_ORGNO" to krxFwdgOrdOrgno,
                "ORGN_ODNO" to kisOrderNo,
                "ORD_DVSN" to "00",
                "RVSE_CNCL_DVSN_CD" to rvseCnclCode,
                "ORD_QTY" to newQty.toString(),
                "ORD_UNPR" to newPrice.toPlainString(),
                "QTY_ALL_ORD_YN" to if (qtyAll) "Y" else "N",
                "EXCG_ID_DVSN_CD" to "KRX",
            )
        val output = postWithAuth(cred, trId, "/uapi/domestic-stock/v1/trading/order-rvsecncl", body)
        return KisOrderResponseMapper.toOrderResult(output)
    }

    override fun fetchBalance(userId: UUID): KisBalanceSnapshot {
        val cred = credentials.forUser(userId)
        val trId = if (cred.tradingMode == TradingMode.MOCK_KIS) "VTTC8434R" else "TTTC8434R"
        val params =
            linkedMapOf(
                "CANO" to cred.accountNo,
                "ACNT_PRDT_CD" to cred.accountProductCode,
                "AFHR_FLPR_YN" to "N",
                "OFL_YN" to "",
                "INQR_DVSN" to "02",
                "UNPR_DVSN" to "01",
                "FUND_STTL_ICLD_YN" to "N",
                "FNCG_AMT_AUTO_RDPT_YN" to "N",
                "PRCS_DVSN" to "00",
                "CTX_AREA_FK100" to "",
                "CTX_AREA_NK100" to "",
            )
        val body = getWithAuth(cred, trId, "/uapi/domestic-stock/v1/trading/inquire-balance", params)
        return KisOrderResponseMapper.toBalanceSnapshot(body)
    }

    override fun fetchExecutions(
        userId: UUID,
        date: LocalDate,
        executedOnly: Boolean,
    ): List<KisExecutionLine> {
        val cred = credentials.forUser(userId)
        val trId = if (cred.tradingMode == TradingMode.MOCK_KIS) "VTTC0081R" else "TTTC0081R"
        val params =
            linkedMapOf(
                "CANO" to cred.accountNo,
                "ACNT_PRDT_CD" to cred.accountProductCode,
                "INQR_STRT_DT" to date.format(yyyymmdd),
                "INQR_END_DT" to date.format(yyyymmdd),
                "SLL_BUY_DVSN_CD" to "00",
                "PDNO" to "",
                "CCLD_DVSN" to if (executedOnly) "01" else "00",
                "INQR_DVSN" to "00",
                "INQR_DVSN_3" to "00",
                "ORD_GNO_BRNO" to "",
                "ODNO" to "",
                "INQR_DVSN_1" to "",
                "EXCG_ID_DVSN_CD" to "KRX",
                "CTX_AREA_FK100" to "",
                "CTX_AREA_NK100" to "",
            )
        val body = getWithAuth(cred, trId, "/uapi/domestic-stock/v1/trading/inquire-daily-ccld", params)
        return KisOrderResponseMapper.toExecutionLines(body)
    }

    // ----------------------------------------------------------------
    // 내부 HTTP 호출 공통 메서드
    // ----------------------------------------------------------------

    private fun postWithAuth(
        cred: KisCredential,
        trId: String,
        path: String,
        body: Map<String, Any>,
    ): JsonNode {
        val session = sessionFor(cred.tradingMode)
        val isMock = cred.tradingMode == TradingMode.MOCK_KIS

        return session.executeWithRetry(cred.appKey, cred.appSecret) {
            val token = session.acquireToken(cred.appKey, cred.appSecret)

            // T2-T7 (a): hashkey 발급
            val hashKey = hashKeyClient.fetchHashKey(isMock, cred.appKey, cred.appSecret, body)

            // T2-T6: raw HTTP 로깅
            if (rawHttpLogging) {
                logRawRequest("POST", path, trId, body, cred.appKey, hashKey)
            }

            val resp: JsonNode =
                try {
                    val result =
                        session.client
                            .post()
                            .uri(path)
                            // T2-T7 (b)(c): 헤더 명시 override
                            .header("Accept", "application/json; charset=utf-8")
                            .header("Content-Type", "application/json; charset=utf-8")
                            .header("User-Agent", "magicjar/1.0")
                            .header("authorization", "Bearer $token")
                            .header("appkey", cred.appKey)
                            .header("appsecret", cred.appSecret)
                            .header("tr_id", trId)
                            .header("custtype", "P")
                            .also { r ->
                                if (hashKey != null) r.header("hashkey", hashKey)
                            }.body(body)
                            .retrieve()
                            .onStatus(HttpStatusCode::isError) { _, r ->
                                val bodyBytes = r.body.readAllBytes()
                                if (rawHttpLogging) {
                                    log.debug("[KIS_RAW] response status={} body={}", r.statusCode, String(bodyBytes, Charsets.UTF_8))
                                }
                                throw RestClientResponseException(
                                    "http=${r.statusCode}",
                                    r.statusCode,
                                    r.statusText,
                                    r.headers,
                                    bodyBytes,
                                    Charsets.UTF_8,
                                )
                            }.body(JsonNode::class.java)
                            ?: error("[KIS_ORDER] empty response path=$path tr_id=$trId")

                    if (rawHttpLogging) {
                        log.debug("[KIS_RAW] response OK rt_cd={} msg_cd={}", result.path("rt_cd").asText(), result.path("msg_cd").asText())
                    }
                    result
                } catch (ex: RestClientResponseException) {
                    if (ex.statusCode.value() == 401) throw ex
                    val node =
                        runCatching { mapper.readTree(ex.responseBodyAsString) }.getOrElse {
                            mapper.createObjectNode()
                        }
                    throw KisApiException(
                        rtCd = node.path("rt_cd").asText(""),
                        msgCd = node.path("msg_cd").asText(""),
                        msgRaw = node.path("msg1").asText(ex.message ?: ""),
                        httpStatus = ex.statusCode.value(),
                    )
                }
            checkRtCd(resp, path, trId)
            resp.path("output")
        }
    }

    private fun getWithAuth(
        cred: KisCredential,
        trId: String,
        path: String,
        params: Map<String, String>,
    ): JsonNode {
        val session = sessionFor(cred.tradingMode)

        return session.executeWithRetry(cred.appKey, cred.appSecret) {
            val token = session.acquireToken(cred.appKey, cred.appSecret)

            if (rawHttpLogging) {
                log.debug("[KIS_RAW] GET {} tr_id={} params_keys={}", path, trId, params.keys)
            }

            val resp =
                try {
                    session.client
                        .get()
                        .uri { b ->
                            val u = b.path(path)
                            params.forEach { (k, v) -> u.queryParam(k, v) }
                            u.build()
                        }
                        // T2-T7 (b)(c): 헤더 명시 override
                        .header("Accept", "application/json; charset=utf-8")
                        .header("User-Agent", "magicjar/1.0")
                        .header("authorization", "Bearer $token")
                        .header("appkey", cred.appKey)
                        .header("appsecret", cred.appSecret)
                        .header("tr_id", trId)
                        .header("custtype", "P")
                        .retrieve()
                        .onStatus(HttpStatusCode::isError) { _, r ->
                            val bodyBytes = r.body.readAllBytes()
                            if (rawHttpLogging) {
                                log.debug("[KIS_RAW] response status={} body={}", r.statusCode, String(bodyBytes, Charsets.UTF_8))
                            }
                            throw RestClientResponseException(
                                "http=${r.statusCode}",
                                r.statusCode,
                                r.statusText,
                                r.headers,
                                bodyBytes,
                                Charsets.UTF_8,
                            )
                        }.body(JsonNode::class.java)
                        ?: error("[KIS_ORDER] empty response path=$path tr_id=$trId")
                } catch (ex: RestClientResponseException) {
                    if (ex.statusCode.value() == 401) throw ex
                    val node =
                        runCatching { mapper.readTree(ex.responseBodyAsString) }.getOrElse {
                            mapper.createObjectNode()
                        }
                    throw KisApiException(
                        rtCd = node.path("rt_cd").asText(""),
                        msgCd = node.path("msg_cd").asText(""),
                        msgRaw = node.path("msg1").asText(ex.message ?: ""),
                        httpStatus = ex.statusCode.value(),
                    )
                }
            if (rawHttpLogging) {
                log.debug("[KIS_RAW] GET response OK rt_cd={}", resp.path("rt_cd").asText())
            }
            checkRtCd(resp, path, trId)
            resp
        }
    }

    private fun checkRtCd(
        resp: JsonNode,
        path: String,
        trId: String,
    ) {
        val rtCd = resp.path("rt_cd").asText("0")
        if (rtCd != "0") {
            val msgCd = resp.path("msg_cd").asText("")
            val msg1 = resp.path("msg1").asText("")
            log.warn("[KIS_ORDER] rt_cd={} msg_cd={} msg={} path={} tr_id={}", rtCd, msgCd, msg1, path, trId)
            throw KisApiException(rtCd, msgCd, msg1, httpStatus = 200)
        }
    }

    /**
     * T2-T6: raw request 로그. secret/authorization 값 마스킹 (컨벤션 §8).
     */
    private fun logRawRequest(
        method: String,
        path: String,
        trId: String,
        body: Map<String, Any>,
        appKey: String,
        hashKey: String?,
    ) {
        val maskedBody =
            body.entries.joinToString(", ") { (k, v) ->
                val masked =
                    when (k.uppercase()) {
                        "CANO" -> maskMiddle(v.toString())
                        else -> v.toString()
                    }
                "$k=$masked"
            }
        log.debug(
            "[KIS_RAW] {} {} tr_id={} appkey={}*** hashkey={} body=[{}]",
            method,
            path,
            trId,
            appKey.take(4),
            hashKey?.take(8)?.let { "$it..." } ?: "none",
            maskedBody,
        )
    }

    /** 계좌번호 가운데 마스킹 — 앞 2자리·뒤 2자리만 노출. */
    private fun maskMiddle(value: String): String {
        if (value.length <= 4) return "****"
        return "${value.take(2)}${"*".repeat(value.length - 4)}${value.takeLast(2)}"
    }
}
