package com.mj.domain.common

/**
 * 캔들 영속 interval — 스토리지(DB) 단위만 정의 (ADR 0048, candle-data-policy-v2.md).
 *
 * 영속 대상:
 *   - D1: 일봉 SoT (영구 보존)
 *   - M1: 1분봉 SoT (LRU 1년)
 *
 * KIS direct fetch (ADR 0050 §2-D):
 *   - WEEK:  주봉 — KIS inquire-daily-itemchartprice FID_PERIOD_DIV_CODE=W
 *   - MONTH: 월봉 — KIS inquire-daily-itemchartprice FID_PERIOD_DIV_CODE=M
 *
 * N분봉(M5/M15/M30/H1/임의 N)은 영속하지 않음.
 * 합성은 api 모듈 CandleAggregator가 M1→N분봉으로 어플리케이션 레벨에서 수행.
 *
 * @property minutes 봉 단위 (분). 일봉=1440 (하루), 주봉=10080, 월봉=43200 (근사값).
 */
enum class Interval(
    val minutes: Int,
) {
    D1(1440),
    M1(1),

    /** 주봉 — KIS direct fetch (FID_PERIOD_DIV_CODE=W). ADR 0050 R3-4. */
    WEEK(10080),

    /** 월봉 — KIS direct fetch (FID_PERIOD_DIV_CODE=M). ADR 0050 R3-4. */
    MONTH(43200),
}
