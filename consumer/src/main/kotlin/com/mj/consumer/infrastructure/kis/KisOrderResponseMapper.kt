package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.databind.JsonNode
import com.mj.domain.common.Side
import com.mj.domain.order.AccountSummary
import com.mj.domain.order.HoldingLine
import com.mj.domain.order.KisBalanceSnapshot
import com.mj.domain.order.KisExecutionLine
import com.mj.domain.order.KisOrderResult
import java.math.BigDecimal

/**
 * KIS REST 응답 → 도메인 모델 변환. spec §5.
 * 응답 필드 누락 시 안전 디폴트 ("0", "", false) 사용.
 */
object KisOrderResponseMapper {
    fun toOrderResult(output: JsonNode): KisOrderResult {
        val raw = mutableMapOf<String, String>()
        output.fields().forEachRemaining { raw[it.key] = it.value.asText("") }
        return KisOrderResult(
            kisOrderNo = output.path("ODNO").asText(""),
            krxFwdgOrdOrgno = output.path("KRX_FWDG_ORD_ORGNO").asText(""),
            orderTime = output.path("ORD_TMD").asText(""),
            raw = raw,
        )
    }

    fun toBalanceSnapshot(body: JsonNode): KisBalanceSnapshot {
        val holdings =
            body.path("output1").map { node ->
                HoldingLine(
                    code = node.path("pdno").asText(""),
                    name = node.path("prdt_name").asText(""),
                    heldQty = node.path("hldg_qty").asInt(0),
                    orderableQty = node.path("ord_psbl_qty").asInt(0),
                    avgPurchasePrice = node.path("pchs_avg_pric").asText("0").toBigDecimal(),
                    currentPrice = node.path("prpr").asText("0").toBigDecimal(),
                    evaluationAmount = node.path("evlu_amt").asText("0").toBigDecimal(),
                    evaluationPnL = node.path("evlu_pfls_amt").asText("0").toBigDecimal(),
                )
            }
        val out2 = body.path("output2").firstOrNull() ?: body.path("output2")
        val acc =
            AccountSummary(
                depositAmount = out2.path("dnca_tot_amt").asText("0").toBigDecimal(),
                nextDayExchangeAmount = out2.path("nxdy_excc_amt").asText("0").toBigDecimal(),
                twoDayExchangeAmount = out2.path("prvs_rcdl_excc_amt").asText("0").toBigDecimal(),
                totalEvaluationAmount = out2.path("tot_evlu_amt").asText("0").toBigDecimal(),
                netAssetAmount = out2.path("nass_amt").asText("0").toBigDecimal(),
                totalPnL = out2.path("evlu_pfls_smtl_amt").asText("0").toBigDecimal(),
            )
        return KisBalanceSnapshot(holdings, acc)
    }

    fun toExecutionLines(body: JsonNode): List<KisExecutionLine> =
        body.path("output1").map { node ->
            KisExecutionLine(
                orderDate = node.path("ord_dt").asText(""),
                kisOrderNo = node.path("odno").asText(""),
                originalOrderNo = node.path("orgn_odno").asText(""),
                krxFwdgOrdOrgno = node.path("ord_gno_brno").asText(""),
                code = node.path("pdno").asText(""),
                side =
                    if (node.path("sll_buy_dvsn_cd").asText() == "02") Side.BUY else Side.SELL,
                orderedQty = node.path("ord_qty").asInt(0),
                orderedPrice = node.path("ord_unpr").asText("0").toBigDecimalOrNull(),
                cumulativeExecutedQty = node.path("tot_ccld_qty").asInt(0),
                cumulativeExecutedAmount = node.path("tot_ccld_amt").asText("0").toBigDecimal(),
                canceled = node.path("cncl_yn").asText() == "Y",
                remainingQty = node.path("rmn_qty").asInt(0),
                rejectedQty = node.path("rjct_qty").asInt(0),
            )
        }
}

private fun String.toBigDecimal(): BigDecimal = if (isBlank()) BigDecimal.ZERO else BigDecimal(trim())
