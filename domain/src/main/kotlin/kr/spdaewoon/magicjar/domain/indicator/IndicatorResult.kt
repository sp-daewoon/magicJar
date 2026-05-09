package kr.spdaewoon.magicjar.domain.indicator

/**
 * 지표 계산 결과 (ADR 0021 §2-1).
 *
 * @param spec   계산에 사용된 명세 (echo-back)
 * @param series 시계열 포인트 목록 (ts ASC 정렬)
 * @param source 계산 source 이름 ("native" | "kis-strategy-builder" | "ta-lib" | "krx")
 */
data class IndicatorResult(
    val spec: IndicatorSpec,
    val series: List<IndicatorPoint>,
    val source: String,
)
