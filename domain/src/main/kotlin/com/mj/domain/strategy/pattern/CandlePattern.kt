package com.mj.domain.strategy.pattern

/**
 * 캔들스틱 패턴 메타데이터. 카탈로그(`PatternCatalog`)와 인식기(`PatternRecognizer`)에서 공유한다.
 *
 * 패턴 정의는 KIS Strategy Builder(57개) 정합. Lean 미지원 8개(Opening Marubozu, Tweezer Top/Bottom,
 * Meeting Lines, Matching High, Deliberation, Rising/Falling Three Methods)는 카탈로그에서 제외했다.
 *
 * `code`는 ENUM-friendly snake_case 식별자 (예: "morning_star"), `nameEn`/`nameKo`는 사용자
 * 표시용. `candleCount`는 매칭에 필요한 연속 캔들 수(1~5).
 *
 * @property code        ASCII snake_case 식별자, REST·DSL·DB 키로 사용
 * @property nameEn      영어 표기 (예: "Morning Star")
 * @property nameKo      한국어 표기 (예: "샛별")
 * @property category    PatternCategory 분류
 * @property defaultDirection 카테고리 정합 기본 방향. REVERSAL_DIRECTIONAL은 NEUTRAL이며 인식기가 +/-/0을 동적 산출
 * @property candleCount 매칭에 필요한 캔들 수 (sliding window 크기)
 * @property description 한국어 1줄 설명 (Swagger·UI 노출용)
 */
data class CandlePattern(
    val code: String,
    val nameEn: String,
    val nameKo: String,
    val category: PatternCategory,
    val defaultDirection: PatternDirection,
    val candleCount: Int,
    val description: String,
) {
    init {
        require(code.isNotBlank()) { "code must not be blank" }
        require(code.all { it.isLowerCase() || it.isDigit() || it == '_' }) {
            "code must be lowercase snake_case: $code"
        }
        require(candleCount in 1..5) { "candleCount must be 1..5: $candleCount" }
    }
}
