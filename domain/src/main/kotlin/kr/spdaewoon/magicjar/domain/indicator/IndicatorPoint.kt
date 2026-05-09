package kr.spdaewoon.magicjar.domain.indicator

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 지표 시계열 단건 (ADR 0021 §2-1).
 *
 * @param time   캔들 시각 (kotlinx.datetime.Instant)
 * @param values 지표 값 맵 — 단일 출력: {name: value}, 복합 출력: {macd, signal, histogram} 등
 *               BigDecimal 사용 (Float·Double 금지 — §8-3 정밀도 룰)
 */
data class IndicatorPoint(
    val time: Instant,
    val values: Map<String, BigDecimal>,
)
