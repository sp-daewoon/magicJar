package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * DSL 지표 참조 노드 — KIS raw parser.py L60 Indicator 1:1 port.
 *
 * KIS raw name: Indicator → IndicatorRef (rename — domain Indicator 충돌 회피, ADR 0021 정합).
 * Source: parser.py:L60 Indicator dataclass
 *
 * @param name 지표 식별자 (예: "ma", "rsi", "bb_upper")
 * @param params 파라미터 리스트 (예: [5], [14], [12, 26, 9])
 */
data class IndicatorRef(
    val name: String,
    val params: List<Any>,
) : DslNode() {
    /**
     * 평가에 필요한 최소 캔들 수 — KIS raw parser.py L283-L296 get_required_days() port.
     *
     * Source: parser.py:L283-L296 Condition.get_required_days()
     */
    fun requiredDays(): Int =
        when (name) {
            "macd", "macd_signal", "macd_hist" -> {
                val slow = (params.getOrNull(1) as? Number)?.toInt() ?: 26
                val signal = (params.getOrNull(2) as? Number)?.toInt() ?: 9
                slow + signal
            }
            else -> (params.firstOrNull() as? Number)?.toInt() ?: 0
        }
}
