package kr.spdaewoon.magicjar.domain.pattern

import java.math.BigDecimal

/**
 * 개별 PatternDetector 내부 반환값 (spec §6-3 LOCK).
 *
 * 57개 @Component detector가 [detect] 호출 결과로 반환.
 * JvmPatternRecognizer가 PatternMatch로 변환 후 응답.
 *
 * @property direction NEUTRAL이면 REST 응답 미포함 (KIS L52 graceful 정합).
 * @property confidence KIS는 0/1 binary — BigDecimal.ONE default (post-MVP ML-based 그라데이션 예정).
 * @property params detector 내부 비율 파라미터 (예: bodyRatio) — 디버깅·R3 overlay용.
 */
data class DetectorResult(
    val direction: Direction,
    val confidence: BigDecimal = BigDecimal.ONE,
    val params: Map<String, BigDecimal>? = null,
)
