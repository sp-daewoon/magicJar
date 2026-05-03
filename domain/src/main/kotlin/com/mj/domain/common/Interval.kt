package com.mj.domain.common

/**
 * 캔들 영속 interval — 스토리지(DB) 단위만 정의 (ADR 0048, candle-data-policy-v2.md).
 *
 * 영속 대상:
 *   - D1: 일봉 SoT (영구 보존)
 *   - M1: 1분봉 SoT (LRU 1년)
 *
 * N분봉(M5/M15/M30/H1/임의 N)은 영속하지 않음.
 * 합성은 api 모듈 CandleAggregator가 M1→N분봉으로 어플리케이션 레벨에서 수행.
 *
 * @property minutes 봉 단위 (분). 일봉=1440 (하루).
 */
enum class Interval(
    val minutes: Int,
) {
    D1(1440),
    M1(1),
}
