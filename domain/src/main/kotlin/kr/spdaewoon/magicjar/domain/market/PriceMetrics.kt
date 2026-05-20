package kr.spdaewoon.magicjar.domain.market

import java.math.BigDecimal

/**
 * 종목 재무 메타 지표 — PER·PBR·EPS·BPS·시총·업종.
 *
 * KIS-first 출처: open-trading-api inquire-daily-itemchartprice (FHKST03010100) output1
 * 2026-05-20 endpoint 교체: inquire-price(FHKST01010100)는 NXT 시간외 거래를 stck_prpr에 반영
 *   → SK하이닉스 좌측 시세창 1,745,000 vs MTS 정규장 종가 1,719,000 불일치 root cause.
 *   inquire-daily-itemchartprice output1은 candle adapter와 동일 source(정규장만) → MTS·candle DB 정합.
 *
 * - per    → output1.per   (PER — 주가수익비율)
 * - pbr    → output1.pbr   (PBR — 주가순자산비율)
 * - eps    → output1.eps   (EPS — 주당순이익)
 * - bps    → null (output1에 없음 — nullable graceful, 필요 시 별도 endpoint 도입)
 * - marketCap → output1.hts_avls (HTS 시가총액 — 단위: 억원)
 * - industry  → null (output1에 없음 — instrument 테이블 1순위 매핑 활용)
 * - name      → output1.hts_kor_isnm (종목명)
 * - market    → null (output1에 없음 — instrument 테이블 1순위 매핑 활용)
 * - currentPrice → output1.stck_prpr (주식 현재가) — 정규장 가격 source
 * - previousClose → output1.stck_prdy_clpr (정확한 전일 종가, 기준가 stck_sdpr 대체)
 * - priceChange → output1.prdy_vrss (전일 대비 금액)
 * - changeRate → output1.prdy_ctrt (전일 대비 등락률 %)
 * - changeSign → output1.prdy_vrss_sign (전일 대비 부호: 1=상한,2=상승,3=보합,4=하한,5=하락)
 * - high      → output1.stck_hgpr (당일 최고가)
 * - low       → output1.stck_lwpr (당일 최저가)
 * - open      → output1.stck_oprc (당일 시가)
 * - volume    → output1.acml_vol (누적 거래량)
 */
data class PriceMetrics(
    val per: BigDecimal?,
    val pbr: BigDecimal?,
    val eps: BigDecimal?,
    val bps: BigDecimal?,
    val marketCap: BigDecimal?, // 단위: 억원 (KIS hts_avls 정합)
    val industry: String?,
    val name: String?,
    val market: String?,
    val currentPrice: BigDecimal?,
    val previousClose: BigDecimal?,
    val priceChange: BigDecimal?,
    val changeRate: BigDecimal?,
    val changeSign: String?,
    val high: BigDecimal?,
    val low: BigDecimal?,
    val open: BigDecimal?,
    val volume: Long?,
)
