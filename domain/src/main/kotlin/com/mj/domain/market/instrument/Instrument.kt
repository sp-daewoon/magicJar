package com.mj.domain.market.instrument

import com.mj.domain.common.Market
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 종목 마스터 도메인 모델 (ADR 0037).
 *
 * - 글로벌 (user 비종속) — ADR 0043
 * - 상장폐지 종목 보존 의무 — delistedAt만 set, 행 삭제 X (생존자 편향 차단)
 * - 거래정지·상폐 시 active=false
 */
data class Instrument(
    /** KIS 단축코드 (6자리) */
    val code: String,
    /** 국제증권식별번호 (12자리) */
    val isin: String?,
    /** 한글 종목명 */
    val name: String,
    /** 영문 종목명 */
    val nameEn: String?,
    /** 상장 시장 */
    val market: Market,
    /** 상품 유형 */
    val type: InstrumentType,
    /** 업종 (KRX 대분류) */
    val sector: String?,
    /** 상장일 */
    val listedAt: LocalDate,
    /** 상장폐지일 (null = 상장 중) */
    val delistedAt: LocalDate?,
    /** 액면가 */
    val parValue: BigDecimal?,
    /** 거래 수량 단위 */
    val tradingUnit: Int,
    /** 거래 가능 여부 (거래정지·상폐 시 false) */
    val active: Boolean,
)
