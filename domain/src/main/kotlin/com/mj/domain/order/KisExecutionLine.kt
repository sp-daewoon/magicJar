package com.mj.domain.order

import com.mj.domain.common.Side
import java.math.BigDecimal

/** spec §5.4 inquire-daily-ccld output1 매핑. REST sweep로 미체결·체결 ingest. */
data class KisExecutionLine(
    val orderDate: String, // YYYYMMDD
    val kisOrderNo: String,
    val originalOrderNo: String,
    val krxFwdgOrdOrgno: String,
    val code: String,
    val side: Side,
    val orderedQty: Int,
    val orderedPrice: BigDecimal?,
    val cumulativeExecutedQty: Int,
    val cumulativeExecutedAmount: BigDecimal,
    val canceled: Boolean,
    val remainingQty: Int,
    val rejectedQty: Int,
)
