package com.mj.domain.market

import java.math.BigDecimal
import java.time.Instant

/**
 * 호가창 도메인 모델.
 *
 * KIS inquire-asking-price-exp-ccn (FHKST01010200) 응답 기반.
 * 매도 10단 + 매수 10단 + 총잔량 + 예상체결가 포함.
 *
 * 실측 검증 완료 (2026-05-04, 005930 기준):
 * - output1: askp1~10(매도호가), bidp1~10(매수호가), askp_rsqn1~10(매도잔량), bidp_rsqn1~10(매수잔량)
 * - output1: total_askp_rsqn(총매도잔량), total_bidp_rsqn(총매수잔량)
 * - output2: stck_prpr(현재가), antc_cnpr(예상체결가), antc_vol(예상체결수량)
 */
data class Orderbook(
    /** 종목 코드 */
    val code: String,
    /** 호가 기준 시각 (KIS aspr_acpt_hour: HHmmss → 파싱 후 Instant) */
    val acceptedAt: Instant,
    /** 매도 호가 10단 (index 0=1호가, 9=10호가 — 낮은 가격부터) */
    val askLevels: List<OrderbookLevel>,
    /** 매수 호가 10단 (index 0=1호가, 9=10호가 — 높은 가격부터) */
    val bidLevels: List<OrderbookLevel>,
    /** 총 매도 잔량 */
    val totalAskQty: Long,
    /** 총 매수 잔량 */
    val totalBidQty: Long,
    /** 현재가 */
    val currentPrice: BigDecimal?,
    /** 예상체결가 */
    val anticipatedPrice: BigDecimal?,
    /** 예상체결수량 */
    val anticipatedVolume: Long?,
)

/**
 * 호가 단건 (가격 + 잔량).
 */
data class OrderbookLevel(
    /** 호가 가격 (원) */
    val price: BigDecimal,
    /** 잔량 (주) */
    val qty: Long,
    /** 잔량 증감 (전 호가 대비) */
    val qtyChange: Long,
)
