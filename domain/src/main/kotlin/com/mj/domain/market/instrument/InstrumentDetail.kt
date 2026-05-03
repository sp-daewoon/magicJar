package com.mj.domain.market.instrument

import java.math.BigDecimal

/**
 * 종목 상세 메타 정보 도메인 모델 (T20-T1).
 *
 * KIS inquire-stock-info + DART 재무정보를 병합한 결과를 담는 불변 데이터 클래스.
 * Redis 캐시 전용 (key: instrument:detail:{code}, TTL 6h) — 별도 영속 저장소 없음.
 *
 * code/name 외 모든 필드는 nullable:
 * - KIS 키 미설정 → 기본정보 전부 null
 * - DART 키 미설정 또는 corp_code 미발견 → 재무정보 전부 null
 * - 개별 필드 미제공 시 null (graceful degradation)
 */
data class InstrumentDetail(
    /** KRX 종목 코드 6자리 */
    val code: String,
    /** 한글 종목명 */
    val name: String,
    /** 시장 구분 (KOSPI / KOSDAQ) */
    val market: String? = null,
    /** 업종명 */
    val sector: String? = null,
    /** 시가총액 (원) */
    val marketCap: BigDecimal? = null,
    /** 상장주식수 */
    val sharesOutstanding: Long? = null,
    /** 외국인 보유율 (%) */
    val foreignRatio: BigDecimal? = null,
    /** 기관 보유율 (%) */
    val institutionRatio: BigDecimal? = null,
    /** 매출액 (원) — 최근 연간 기준 */
    val revenue: BigDecimal? = null,
    /** 영업이익 (원) */
    val operatingIncome: BigDecimal? = null,
    /** 당기순이익 (원) */
    val netIncome: BigDecimal? = null,
    /** 주가수익비율 (PER) */
    val per: BigDecimal? = null,
    /** 주가순자산비율 (PBR) */
    val pbr: BigDecimal? = null,
    /** 주당순이익 (EPS, 원) */
    val eps: BigDecimal? = null,
    /** 주당순자산 (BPS, 원) */
    val bps: BigDecimal? = null,
    /** 배당수익률 (%) */
    val dividendYield: BigDecimal? = null,
    /** 최대주주 지분율 (%) */
    val majorShareholderRatio: BigDecimal? = null,
    /** 대표이사 이름 */
    val representativeName: String? = null,
    /** 업종 분류명 */
    val industry: String? = null,
)
