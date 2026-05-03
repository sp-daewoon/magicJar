package com.mj.domain.strategy.indicator

/**
 * 지표 메타 정보.
 *
 * @param id        소문자 식별자 (예: "ma", "rsi", "macd")
 * @param name      표시 이름 (예: "이동평균", "RSI")
 * @param category  분류 (trend / momentum / volume / volatility / oscillator / other)
 * @param params    파라미터 정의 목록
 * @param outputKeys 계산 결과 values Map의 키 목록 (단일 지표 = listOf(id), MACD = listOf("macd","signal","histogram"))
 */
data class IndicatorDescriptor(
    val id: String,
    val name: String,
    val category: IndicatorCategory,
    val params: List<IndicatorParamDef> = emptyList(),
    val outputKeys: List<String> = listOf(id),
) {
    /** defaultParams: 파라미터 정의에서 기본값을 추출한 Map */
    fun defaultParams(): Map<String, Any> =
        params
            .filter { it.defaultValue != null }
            .associate { it.key to it.defaultValue!! }
}

enum class IndicatorCategory {
    TREND,
    MOMENTUM,
    VOLUME,
    VOLATILITY,
    OSCILLATOR,
    OTHER,
}

/**
 * 파라미터 정의.
 *
 * @param key           파라미터 키 (예: "period", "fast", "slow")
 * @param type          타입 (INT / FLOAT / STRING)
 * @param defaultValue  기본값 (없으면 null = 필수)
 * @param description   설명
 */
data class IndicatorParamDef(
    val key: String,
    val type: ParamType,
    val defaultValue: Any? = null,
    val description: String = "",
)

enum class ParamType { INT, FLOAT, STRING }
