package kr.spdaewoon.magicjar.domain.pattern

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 패턴 인식 결과 단건 — REST·DSL evaluator 응답 공용 (spec §6-3 LOCK).
 *
 * @property pattern 패턴 catalog id (예: "hammer", "engulfing")
 * @property startTime 패턴 시작 캔들 ts (multi-candle 패턴: 첫 캔들, single: 마지막 캔들)
 * @property endTime 패턴 종료 캔들 ts (single 패턴: startTime 동일)
 * @property direction BULL / BEAR (NEUTRAL은 JvmPatternRecognizer에서 미포함 — KIS L52 정합)
 * @property confidence 인식 신뢰도 (1.0 default — post-MVP ML-based 그라데이션 예정)
 * @property params detector 내부 파라미터 (optional — R3 overlay 표시용)
 */
data class PatternMatch(
    val pattern: String,
    val startTime: Instant,
    val endTime: Instant,
    val direction: Direction,
    val confidence: BigDecimal,
    val params: Map<String, BigDecimal>? = null,
)
