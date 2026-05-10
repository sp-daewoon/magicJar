package kr.spdaewoon.magicjar.domain.pattern

/**
 * 패턴 인식 예외 sealed class (spec §6-3 LOCK).
 *
 * PatternController에서 → 400 Bad Request 매핑.
 */
sealed class PatternException(
    message: String,
) : RuntimeException(message) {
    /**
     * catalog 57에 없는 패턴 요청.
     * REST 응답에서는 미감지(빈 matches)로 graceful 처리 (KIS L52 정합).
     * DSL evaluator에서는 WARNING 로그.
     */
    class UnknownPatternException(
        id: String,
    ) : PatternException("Unknown pattern '$id' (not in catalog 57)")

    /**
     * 캔들 부족 — PatternDetector.minCandles 미달.
     * KIS detect_pattern L48 `len(df) < 5: return 0` 정합.
     */
    class InsufficientCandlesException(
        required: Int,
        actual: Int,
    ) : PatternException("Insufficient candles: required=$required, actual=$actual")
}
