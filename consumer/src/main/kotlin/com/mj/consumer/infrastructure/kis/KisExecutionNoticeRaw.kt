package com.mj.consumer.infrastructure.kis

import java.math.BigDecimal

/**
 * KIS H0STCNI0/9 체결통보 페이로드 26 컬럼 (spec §5.5).
 *
 * `^` 구분 raw 문자열을 파싱한 데이터 클래스. CNTG_YN으로 라이프사이클 분기:
 * - "1" = 주문/정정/취소/거부 접수통보 (RECEIPT_ACCEPTED)
 * - "2" = 체결통보 (EXECUTED)
 */
data class KisExecutionNoticeRaw(
    val custId: String,
    val acntNo: String,
    val orderNo: String,
    val originalOrderNo: String,
    val sellBuyClass: String, // 1: 매도, 2: 매수
    val rectifyClass: String, // 0: 정상, 1: 정정, 2: 취소
    val orderKind: String,
    val orderCondition: String,
    val stockCode: String,
    val executedQty: Int,
    val executedUnitPrice: BigDecimal,
    val executedTime: String, // HHMMSS
    val refusalYn: String,
    val cntgYn: String, // 1: 접수통보, 2: 체결통보
    val acceptYn: String,
    val krxFwdgOrdOrgno: String,
    val orderQty: Int,
    val rest: List<String>,
) {
    companion object {
        fun fromColumns(c: List<String>): KisExecutionNoticeRaw =
            KisExecutionNoticeRaw(
                custId = c[0],
                acntNo = c[1],
                orderNo = c[2],
                originalOrderNo = c[3],
                sellBuyClass = c[4],
                rectifyClass = c[5],
                orderKind = c[6],
                orderCondition = c[7],
                stockCode = c[8],
                executedQty = c[9].toIntOrNull() ?: 0,
                executedUnitPrice = c[10].toBigDecimalOrNull() ?: BigDecimal.ZERO,
                executedTime = c[11],
                refusalYn = c[12],
                cntgYn = c[13],
                acceptYn = c[14],
                krxFwdgOrdOrgno = c.getOrNull(15) ?: "",
                orderQty = c.getOrNull(16)?.toIntOrNull() ?: 0,
                rest = if (c.size > 17) c.subList(17, c.size) else emptyList(),
            )
    }
}
