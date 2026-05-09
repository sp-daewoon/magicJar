package kr.spdaewoon.magicjar.consumer.application.strategy

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component

/**
 * T-PAT 미진입 1차 stub.
 *
 * Source: spec §3-6 + §6-3 + §8-4 (T-PAT 미진입 graceful).
 *
 * `consecutive(up) >= 5` · `pattern("BULLISH_ENGULFING")` 같은 T-PAT 의존 식이 등록 시:
 * - **등록 시**: KisYamlDslParser 화이트리스트 통과 (`consecutive`·`pattern`은 ALLOWED) — 등록 자체 OK
 * - **평가 시**: PatternRecognizerStub.recognize() / consecutive() → false / 0 반환
 *   → JvmStrategyEvaluator가 `EvalResult.HOLD(reason="T-PAT not yet implemented")` 반환
 *
 * T-PAT 합류 시 진짜 PatternRecognizer bean(name="patternRecognizer") 등록 →
 * `@ConditionalOnMissingBean`에 의해 Stub 자동 비활성화. JvmStrategyEvaluator 코드 변경 0건 (1줄 교체로 활성).
 */
@Component
@ConditionalOnMissingBean(name = ["patternRecognizer"])
class PatternRecognizerStub {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * `pattern("BULLISH_ENGULFING")` 같은 패턴 인식 호출.
     *
     * @return 항상 false — T-PAT 미진입 시 graceful HOLD.
     */
    fun recognize(
        name: String,
        candles: List<Candle>,
    ): Boolean {
        log.warn(
            "PatternRecognizerStub.recognize('{}') called — T-PAT not yet implemented (candles={}). Returning false.",
            name,
            candles.size,
        )
        return false
    }

    /**
     * `consecutive("up") >= 5` 같은 연속 횟수 호출.
     *
     * @return 항상 0 — T-PAT 미진입 시 graceful HOLD (5 미만이라 비교 false).
     */
    fun consecutive(
        direction: String,
        candles: List<Candle>,
    ): Int {
        log.warn(
            "PatternRecognizerStub.consecutive('{}') called — T-PAT not yet implemented (candles={}). Returning 0.",
            direction,
            candles.size,
        )
        return 0
    }
}
