package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.indicator.IndicatorCatalog
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * DSL 식별자 → [IndicatorSpec] 매핑.
 *
 * Source: parser.py:L72-L107 (KIS DSL 식별자) · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §3 + spec §5-5 LOCK (자체 추론 0).
 *
 * ### 매핑 룰
 * - 1:1 매핑 (80지표): `ma` · `ema` · `rsi` · ... → IndicatorSpec(name = ref.name, params = paramsOf(ref))
 * - DSL alias (multi-output): `bb_upper` · `bb_lower` · `bb_middle` → IndicatorSpec(name = "bb", outputKey = "upper" | "lower" | "middle")
 * - DSL alias (multi-output): `macd_signal` · `macd_hist` → IndicatorSpec(name = "macd", outputKey = "signal" | "histogram")
 * - DSL alias: `stoch_k` · `stoch_d` → IndicatorSpec(name = "stochastic_k" | "stochastic_d")
 *
 * 알 수 없는 식별자는 [StrategyException.UnknownDslSymbolException] (KisYamlDslParser 화이트리스트 통과 후에도 안전망).
 */
@Component
class DslIdentifierMapper {
    /**
     * IndicatorRef → IndicatorSpec 변환.
     *
     * @throws StrategyException.UnknownDslSymbolException IndicatorEngine 등록 0건 식별자
     */
    fun toIndicatorSpec(ref: IndicatorRef): IndicatorSpec =
        when (ref.name) {
            // ===== DSL alias — multi-output =====
            "macd_signal", "macd_hist" -> IndicatorSpec("macd", paramsOfMacd(ref))
            "bb_upper", "bb_lower" -> IndicatorSpec("bb", paramsOfBb(ref))
            "bb_middle" -> IndicatorSpec("bb", mapOf("period" to (ref.params.firstOrNull()?.toIntOrDefault(20) ?: 20)))
            "stoch_k" -> IndicatorSpec("stochastic_k", paramsOfPeriod(ref, default = 14))
            "stoch_d" -> IndicatorSpec("stochastic_d", paramsOfPeriod(ref, default = 14))
            // ===== 1:1 매핑 (80지표 — IndicatorCatalog 등록 정합) =====
            else -> {
                if (ref.name !in CATALOG_IDS) {
                    throw StrategyException.UnknownDslSymbolException(ref.name)
                }
                IndicatorSpec(ref.name, paramsOf(ref))
            }
        }

    /**
     * IndicatorResult → 마지막 시점 BigDecimal 추출.
     *
     * multi-output indicator는 outputKey 기반 추출 (예: `bb_upper` → values["upper"]).
     */
    fun extractValue(
        result: IndicatorResult,
        ref: IndicatorRef,
    ): BigDecimal {
        val outputKey =
            when (ref.name) {
                "macd_signal" -> "signal"
                "macd_hist" -> "histogram"
                "bb_upper" -> "upper"
                "bb_lower" -> "lower"
                "bb_middle" -> "middle"
                "stoch_k" -> "k"
                "stoch_d" -> "d"
                else -> ref.name
            }
        val lastPoint: IndicatorPoint =
            result.series.lastOrNull()
                ?: throw IllegalStateException("Empty IndicatorResult series for ${ref.name}")
        return lastPoint.values[outputKey]
            ?: lastPoint.values.values.firstOrNull()
            ?: throw IllegalStateException("Output key '$outputKey' not found for ${ref.name} (available: ${lastPoint.values.keys})")
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun paramsOf(ref: IndicatorRef): Map<String, Any> {
        val first = ref.params.firstOrNull() ?: return emptyMap()
        return mapOf("period" to first.toIntOrDefault(20))
    }

    private fun paramsOfPeriod(
        ref: IndicatorRef,
        default: Int,
    ): Map<String, Any> =
        mapOf(
            "period" to (ref.params.firstOrNull()?.toIntOrDefault(default) ?: default),
        )

    private fun paramsOfMacd(ref: IndicatorRef): Map<String, Any> {
        val fast = ref.params.getOrNull(0)?.toIntOrDefault(12) ?: 12
        val slow = ref.params.getOrNull(1)?.toIntOrDefault(26) ?: 26
        val signal = ref.params.getOrNull(2)?.toIntOrDefault(9) ?: 9
        return mapOf("fast" to fast, "slow" to slow, "signal" to signal)
    }

    private fun paramsOfBb(ref: IndicatorRef): Map<String, Any> {
        val period = ref.params.getOrNull(0)?.toIntOrDefault(20) ?: 20
        val std = ref.params.getOrNull(1)?.toBigDecimalOrDefault(BigDecimal("2")) ?: BigDecimal("2")
        return mapOf("period" to period, "std" to std)
    }

    private fun Any.toIntOrDefault(default: Int): Int =
        when (this) {
            is Int -> this
            is Number -> toInt()
            is String -> toIntOrNull() ?: default
            else -> default
        }

    private fun Any.toBigDecimalOrDefault(default: BigDecimal): BigDecimal =
        when (this) {
            is BigDecimal -> this
            is Number -> BigDecimal.valueOf(toDouble())
            is String -> toBigDecimalOrNull() ?: default
            else -> default
        }

    companion object {
        /**
         * 80지표 ID set (IndicatorCatalog.ALL.id) — 빠른 lookup.
         */
        private val CATALOG_IDS: Set<String> = IndicatorCatalog.ALL.map { it.id }.toSet()
    }
}
