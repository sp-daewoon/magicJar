package kr.spdaewoon.magicjar.domain.indicator

/**
 * 지표 계산 요청 명세 (ADR 0021 §2-1).
 *
 * @param name   지표 ID (예: "rsi", "macd", "bb" — indicator-catalog.md 80지표 ID 정합)
 * @param params 파라미터 맵 (예: mapOf("period" to 14))
 */
data class IndicatorSpec(
    val name: String,
    val params: Map<String, Any>,
) {
    /**
     * Redis 캐시 키 paramsHash (spec §5-3 LOCK).
     * 키 알파벳 정렬 + SHA-256 + 16자리 hex prefix.
     */
    fun paramsHash(): String {
        val sorted =
            params.entries
                .sortedBy { it.key }
                .joinToString("|") { "${it.key}=${it.value}" }
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(sorted.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
