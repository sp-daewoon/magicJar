package com.mj.domain.strategy.indicator

/**
 * Source-agnostic 지표 명세.
 *
 * name은 소문자 id (예: "ma", "ema", "rsi", "macd", "bb").
 * params는 지표별 파라미터 (예: RSI → mapOf("period" to 14)).
 */
data class IndicatorSpec(
    val name: String,
    val params: Map<String, Any> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "IndicatorSpec.name must not be blank" }
    }

    /** 동일 name+params 조합의 캐시 키 파트 */
    fun cacheKey(): String = "$name:${params.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }}"
}
