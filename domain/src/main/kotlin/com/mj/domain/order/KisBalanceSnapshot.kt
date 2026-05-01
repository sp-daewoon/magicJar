package com.mj.domain.order

import java.math.BigDecimal

/** spec §5.3 inquire-balance 응답 매핑. output1=종목별, output2=계좌 합계. */
data class KisBalanceSnapshot(
    val holdings: List<HoldingLine>,
    val account: AccountSummary,
)

data class HoldingLine(
    val code: String,
    val name: String,
    val heldQty: Int,
    val orderableQty: Int,
    val avgPurchasePrice: BigDecimal,
    val currentPrice: BigDecimal,
    val evaluationAmount: BigDecimal,
    val evaluationPnL: BigDecimal,
)

data class AccountSummary(
    val depositAmount: BigDecimal,
    val nextDayExchangeAmount: BigDecimal,
    val twoDayExchangeAmount: BigDecimal,
    val totalEvaluationAmount: BigDecimal,
    val netAssetAmount: BigDecimal,
    val totalPnL: BigDecimal,
)
