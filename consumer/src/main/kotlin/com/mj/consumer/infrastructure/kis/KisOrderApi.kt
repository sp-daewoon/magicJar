package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.common.Order
import com.mj.domain.common.OrderResult
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * KIS 주문 API. 모의/실 분기는 trId로 처리한다.
 *
 * tr_id
 * - 매수: TTTC0802U(실) / VTTC0802U(모의)
 * - 매도: TTTC0801U(실) / VTTC0801U(모의)
 *
 * 자격증명이 없으면 즉시 IllegalStateException — 주문은 절대 폴백하지 않는다.
 */
@Component
class KisOrderApi(
    private val properties: KisProperties,
    private val rest: KisRestClient,
) {
    private val log = LoggerFactory.getLogger(KisOrderApi::class.java)

    fun placeOrder(order: Order): OrderResult {
        check(properties.hasCredentials()) { "KIS credentials missing — placeOrder forbidden" }
        check(properties.accountCano != null) { "KIS_ACCOUNT_CANO required for placeOrder" }

        val trId = trIdFor(order.side, properties.mockMode)
        val body =
            CashOrderRequest(
                cano = properties.accountCano,
                acntPrdtCd = properties.accountPrdt,
                pdno = order.code,
                ordDvsn = ordDvsn(order.type),
                ordQty = order.qty.toString(),
                ordUnpr = (order.price ?: BigDecimal.ZERO).toPlainString(),
            )

        log.info("KIS placeOrder side={} code={} qty={} trId={}", order.side, order.code, order.qty, trId)

        val response =
            rest.postOrder(
                path = "/uapi/domestic-stock/v1/trading/order-cash",
                trId = trId,
                hashKey = null,
                body = body,
                responseType = CashOrderResponse::class.java,
            )

        return response.toOrderResult(order)
    }

    private fun ordDvsn(type: OrderType): String =
        when (type) {
            OrderType.MARKET -> "01"
            OrderType.LIMIT -> "00"
        }

    private fun trIdFor(
        side: Side,
        mock: Boolean,
    ): String =
        when (side) {
            Side.BUY -> if (mock) "VTTC0802U" else "TTTC0802U"
            Side.SELL -> if (mock) "VTTC0801U" else "TTTC0801U"
        }

    internal data class CashOrderRequest(
        @JsonProperty("CANO") val cano: String,
        @JsonProperty("ACNT_PRDT_CD") val acntPrdtCd: String,
        @JsonProperty("PDNO") val pdno: String,
        @JsonProperty("ORD_DVSN") val ordDvsn: String,
        @JsonProperty("ORD_QTY") val ordQty: String,
        @JsonProperty("ORD_UNPR") val ordUnpr: String,
    )

    internal data class CashOrderResponse(
        @JsonProperty("rt_cd") val rtCd: String? = null,
        @JsonProperty("msg_cd") val msgCd: String? = null,
        @JsonProperty("msg1") val msg: String? = null,
        @JsonProperty("output") val output: Output? = null,
    ) {
        data class Output(
            @JsonProperty("KRX_FWDG_ORD_ORGNO") val ordOrgno: String? = null,
            @JsonProperty("ODNO") val orderNo: String? = null,
            @JsonProperty("ORD_TMD") val orderTime: String? = null,
        )

        fun toOrderResult(order: Order): OrderResult {
            val accepted = rtCd == "0"
            val status = if (accepted) OrderStatus.PENDING else OrderStatus.REJECTED
            return OrderResult(
                order = order.copy(status = status),
                resultStatus = status,
                filledQty = 0,
                avgFillPrice = null,
                filledAt = if (accepted) Instant.now() else null,
                rejectReason = if (accepted) null else (msg ?: "KIS rejected $msgCd"),
            )
        }
    }
}
