package kr.spdaewoon.magicjar.domain.pattern.port

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.PatternMatch

/**
 * 패턴 인식 포트 — REST·DSL evaluator 공용 (spec §6-2 LOCK).
 *
 * T-DSL PatternRecognizerStub과 호환 시그니처 의무 — JvmStrategyEvaluator 코드 변경 0건.
 *
 * 구현체: consumer/.../pattern/JvmPatternRecognizer @Component(name="patternRecognizer")
 *         (T-DSL stub @ConditionalOnMissingBean 자동 비활성화)
 *
 * Spring 의존 0 (interface only).
 */
interface PatternRecognizer {
    /**
     * 다중 패턴 일괄 감지 — REST endpoint·frontend overlay 용.
     *
     * @param candles ts ASC 정렬 캔들 시계열 (size < 5이면 빈 리스트 — KIS L48 정합)
     * @param patterns 요청 패턴 ID Set
     * @return matches — direction=NEUTRAL 또는 미감지는 미포함 (KIS L52 정합)
     */
    fun recognize(
        candles: List<Candle>,
        patterns: Set<String>,
    ): List<PatternMatch>

    /**
     * 단일 패턴 Boolean 감지 — DSL `pattern("name")` 호환 (PR-36 stub 시그니처 정합).
     *
     * @return true if pattern 인식 (BULL 또는 BEAR), false otherwise
     */
    fun recognize(
        name: String,
        candles: List<Candle>,
    ): Boolean

    /**
     * 연속 방향 횟수 — DSL `consecutive("up"|"down")` 호환 (PR-36 stub 시그니처 정합).
     * magicJar own — KIS 미존재.
     *
     * @param direction "up" (close > open) | "down" (close < open)
     * @return 마지막 캔들부터 직전으로 같은 방향 연속 횟수
     */
    fun consecutive(
        direction: String,
        candles: List<Candle>,
    ): Int
}
