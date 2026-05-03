package com.mj.domain.market.index

import java.math.BigDecimal

/**
 * 시장 지수 도메인 모델 (T21-T1).
 *
 * KOSPI (code=0001) / KOSDAQ (code=1001) 지수를 표현한다.
 * Redis 캐시 전용 (key: market:index:{code}, TTL 1분).
 */
data class MarketIndex(
    /** KIS 지수 코드 (0001=KOSPI, 1001=KOSDAQ) */
    val code: String,
    /** 지수명 (KOSPI / KOSDAQ) */
    val name: String,
    /** 현재 지수값 */
    val currentValue: BigDecimal,
    /** 전일 종가 */
    val prevClose: BigDecimal,
    /** 등락률 (%) */
    val changePercent: BigDecimal,
    /** 누적 거래량 */
    val volume: Long,
    /** 시장 상태 (장전/장중/장마감 등) */
    val session: String? = null,
    /** 거래대금 (원) */
    val tradingValue: BigDecimal? = null,
)
