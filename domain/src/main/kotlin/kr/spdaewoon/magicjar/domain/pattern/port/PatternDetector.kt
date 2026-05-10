package kr.spdaewoon.magicjar.domain.pattern.port

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult

/**
 * 단일 패턴 감지 포트 — 57개 @Component가 구현 (spec §6-3 LOCK).
 *
 * JvmPatternRecognizer가 Spring auto-collect → registry Map 등록.
 * R1 10건 active, R2 47건 stub (Direction.NEUTRAL 반환).
 *
 * Spring 의존 0 (interface only — @Component는 구현체 레이어).
 */
interface PatternDetector {
    /**
     * 패턴 catalog id (예: "doji", "hammer", "engulfing").
     * PatternCatalog.ALL[*].id 와 1:1 매핑 의무.
     */
    val id: String

    /**
     * 최소 캔들 수 — KIS L48 `len(df) < 5: return 0` 정합.
     * 대부분 5. triple+ 중 특수 패턴은 override 가능.
     */
    val minCandles: Int get() = 5

    /**
     * 패턴 감지.
     *
     * @param candles 시계열 캔들 (ts ASC 정렬 의무). candles.size >= minCandles 보장 (호출자 책임).
     * @return DetectorResult — direction=NEUTRAL이면 JvmPatternRecognizer가 결과 미포함.
     */
    fun detect(candles: List<Candle>): DetectorResult
}
