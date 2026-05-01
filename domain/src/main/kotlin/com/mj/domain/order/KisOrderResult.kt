package com.mj.domain.order

/**
 * KIS placeOrder/cancel/modify 응답. spec §5.1, §5.2.
 * 정정/취소 호출에 필요한 KRX_FWDG_ORD_ORGNO + ODNO 보관.
 */
data class KisOrderResult(
    val kisOrderNo: String,
    val krxFwdgOrdOrgno: String,
    val orderTime: String, // HHMMSS
    val raw: Map<String, String> = emptyMap(),
)
