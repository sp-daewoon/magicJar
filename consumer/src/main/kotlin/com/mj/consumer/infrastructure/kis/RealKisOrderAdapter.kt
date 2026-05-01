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
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * KIS 주문/잔고/체결조회 어댑터. spec §5, §6.1.
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
        val body =
            mapOf(
                "CANO" to cred.accountNo,
                "ACNT_PRDT_CD" to cred.accountProductCode,
                "PDNO" to order.code,
                "ORD_DVSN" to ordDvsn,
                "ORD_QTY" to order.qty.toString(),
                "ORD_UNPR" to (order.price?.toPlainString() ?: "0"),
                "EXCG_ID_DVSN_CD" to "KRX",
                "SLL_TYPE" to if (order.side == Side.SELL) "01" else "",
                "CNDT_PRIC" to "",
            )
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

    private fun postWithAuth(
        cred: KisCredential,
        trId: String,
        path: String,
        body: Map<String, Any>,
    ): JsonNode {
        val session = sessionFor(cred.tradingMode)
        return session.executeWithRetry(cred.appKey, cred.appSecret) {
            val token = session.acquireToken(cred.appKey, cred.appSecret)
            val resp =
                try {
                    session.client
                        .post()
                        .uri(path)
                        .header("authorization", "Bearer $token")
                        .header("appkey", cred.appKey)
                        .header("appsecret", cred.appSecret)
                        .header("tr_id", trId)
                        .header("custtype", "P")
                        .body(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError) { _, r ->
                            throw RestClientResponseException(
                                "http=${r.statusCode}",
                                r.statusCode,
                                r.statusText,
                                r.headers,
                                r.body.readAllBytes(),
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
            val resp =
                try {
                    session.client
                        .get()
                        .uri { b ->
                            val u = b.path(path)
                            params.forEach { (k, v) -> u.queryParam(k, v) }
                            u.build()
                        }.header("authorization", "Bearer $token")
                        .header("appkey", cred.appKey)
                        .header("appsecret", cred.appSecret)
                        .header("tr_id", trId)
                        .header("custtype", "P")
                        .retrieve()
                        .onStatus(HttpStatusCode::isError) { _, r ->
                            throw RestClientResponseException(
                                "http=${r.statusCode}",
                                r.statusCode,
                                r.statusText,
                                r.headers,
                                r.body.readAllBytes(),
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
}
