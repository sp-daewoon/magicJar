package com.mj.domain.market

import java.math.BigDecimal

/**
 * 체결 단건 도메인 모델.
 *
 * KIS inquire-ccnl (FHKST01010300) 응답 기반.
 * output[]는 최근 30건 체결 리스트.
 *
 * 실측 검증 완료 (2026-05-04, 005930 기준):
 * - stck_cntg_hour: 체결 시각 (HHmmss, 예: "155959")
 * - stck_prpr: 체결 가격 (원)
 * - cntg_vol: 체결 수량 (주)
 * - prdy_vrss: 전일 대비 (원, 부호 포함)
 * - prdy_vrss_sign: 전일 대비 부호 (1=상한/2=상승/3=보합/4=하한/5=하락)
 * - prdy_ctrt: 전일 대비율 (%)
 */
data class RecentTrade(
    /** 종목 코드 */
    val code: String,
    /** 체결 시각 문자열 (KIS HHmmss, 예: "155959") */
    val tradeTime: String,
    /** 체결 가격 (원) */
    val price: BigDecimal,
    /** 체결 수량 (주) */
    val volume: Long,
    /** 전일 대비 (원, 부호 포함) */
    val priceChange: BigDecimal,
    /**
     * 전일 대비 부호 코드.
     * 1=상한, 2=상승, 3=보합, 4=하한, 5=하락
     */
    val priceChangeSign: String,
    /** 전일 대비율 (%) */
    val changeRate: BigDecimal,
)
