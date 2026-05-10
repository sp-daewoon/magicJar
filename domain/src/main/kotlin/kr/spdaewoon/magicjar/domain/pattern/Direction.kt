package kr.spdaewoon.magicjar.domain.pattern

/**
 * 캔들스틱 패턴 방향성 (ADR 0020 §3-1 정합).
 *
 * - BULL: 상승 신호 (양봉 반전·지속 패턴)
 * - BEAR: 하락 신호 (음봉 반전·지속 패턴)
 * - NEUTRAL: 방향 미확정 (도지 계열·양방향 패턴) 또는 R2 stub
 *
 * REST 응답에서 NEUTRAL은 미포함 (KIS L52 graceful 정합).
 */
enum class Direction {
    BULL,
    BEAR,
    NEUTRAL,
}
