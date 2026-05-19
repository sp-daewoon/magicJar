package kr.spdaewoon.magicjar.domain.market

import java.math.BigDecimal

/**
 * 종목 재무 메타 지표 — PER·PBR·EPS·BPS·시총·업종.
 *
 * KIS-first 출처: open-trading-api inquire-price (FHKST01010100)
 * - per    → output.per   (PER — 주가수익비율)
 * - pbr    → output.pbr   (PBR — 주가순자산비율)
 * - eps    → output.eps   (EPS — 주당순이익)
 * - bps    → output.bps   (BPS — 주당순자산)
 * - marketCap → output.hts_avls (HTS 시가총액 — 단위: 억원)
 * - industry  → output.bstp_kor_isnm (업종 한글명)
 * - name      → output.hts_kor_isnm (종목명)
 * - market    → output.rprs_mrkt_kor_name (대표시장 한글명)
 * - currentPrice → output.stck_prpr (주식 현재가) — 장중=실시간, 장외=당일 종가
 * - previousClose → output.stck_sdpr (주식 기준가 — 전일 종가)
 * - priceChange → output.prdy_vrss (전일 대비 금액)
 * - changeRate → output.prdy_ctrt (전일 대비 등락률 %)
 * - changeSign → output.prdy_vrss_sign (전일 대비 부호: 1=상한,2=상승,3=보합,4=하한,5=하락)
 * - high      → output.stck_hgpr (주식 최고가)
 * - low       → output.stck_lwpr (주식 최저가)
 * - open      → output.stck_oprc (주식 시가)
 * - volume    → output.acml_vol (누적 거래량)
 *
 * 의무: currentPrice 매핑은 SymbolMetaService Redis quote:last fallback 정합 — 매핑 누락 시
 * previousClose가 currentPrice로 표시되는 결함 발생 (SK하이닉스 1,840,000 vs HTS 1,719,000 사례).
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
