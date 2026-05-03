package com.mj.domain.market

import java.math.BigDecimal

/**
 * 일자별 외국인·기관·개인 동향 도메인 모델.
 *
 * KIS inquire-investor (FHKST01010900) 응답 기반.
 * output[]는 최근 30 영업일 동향 리스트.
 *
 * 실측 검증 완료 (2026-05-04, 005930 기준):
 * - stck_bsop_date: 영업일 (yyyyMMdd, 예: "20260430")
 * - stck_clpr: 종가 (원)
 * - prdy_vrss: 전일 대비 (원, 부호 포함)
 * - prdy_vrss_sign: 전일 대비 부호 (1~5)
 * - prsn_ntby_qty/frgn_ntby_qty/orgn_ntby_qty: 개인/외국인/기관 순매수 수량
 * - prsn_ntby_tr_pbmn/frgn_ntby_tr_pbmn/orgn_ntby_tr_pbmn: 순매수 금액 (백만원)
 * - prsn_shnu_vol/frgn_shnu_vol/orgn_shnu_vol: 개인/외국인/기관 매수 수량
 * - prsn_seln_vol/frgn_seln_vol/orgn_seln_vol: 매도 수량
 */
data class InvestorTrend(
    /** 종목 코드 */
    val code: String,
    /** 영업일 (yyyyMMdd, 예: "20260430") */
    val bizDate: String,
    /** 종가 (원) */
    val closePrice: BigDecimal,
    /** 전일 대비 (원, 부호 포함) */
    val priceChange: BigDecimal,
    /** 전일 대비 부호 (1=상한/2=상승/3=보합/4=하한/5=하락) */
    val priceChangeSign: String,
    /** 개인 순매수 수량 (주, 음수=순매도) */
    val personNetBuyQty: Long,
    /** 외국인 순매수 수량 (주, 음수=순매도) */
    val foreignNetBuyQty: Long,
    /** 기관 순매수 수량 (주, 음수=순매도) */
    val orgNetBuyQty: Long,
    /** 개인 순매수 금액 (백만원) */
    val personNetBuyAmt: BigDecimal,
    /** 외국인 순매수 금액 (백만원) */
    val foreignNetBuyAmt: BigDecimal,
    /** 기관 순매수 금액 (백만원) */
    val orgNetBuyAmt: BigDecimal,
    /** 개인 매수 수량 */
    val personBuyVol: Long,
    /** 외국인 매수 수량 */
    val foreignBuyVol: Long,
    /** 기관 매수 수량 */
    val orgBuyVol: Long,
    /** 개인 매도 수량 */
    val personSellVol: Long,
    /** 외국인 매도 수량 */
    val foreignSellVol: Long,
    /** 기관 매도 수량 */
    val orgSellVol: Long,
)
