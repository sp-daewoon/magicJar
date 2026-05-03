package com.mj.domain.ai.analysis

import java.time.Instant

/**
 * AI 분석 응답 — 자연어(한국어) 코멘트 + 메타데이터.
 *
 * **분석 보조용 — 매매 결정 X.** [text] 본문에는 면책 문구가 자동 포함된다 (provider 어댑터 책임).
 */
data class AnalysisResult(
    /** 한국어 자연어 본문. 면책 문구 포함. */
    val text: String,
    /** 응답 신뢰도 (0.0~1.0). LLM이 직접 산출 또는 어댑터 기본값(0.5). */
    val confidence: Double,
    /** 사용 모델 식별자 (예: `claude-sonnet-4-5`, `mock`). */
    val model: String,
    /** 프롬프트 버전 (catalog 추적용). */
    val promptVersion: String,
    /** 사용 토큰 (input + output). 0 가능 (mock·실패). */
    val tokensUsed: TokensUsed,
    /** 추정 비용 USD. mock = 0. */
    val costEstimate: Double,
    /** 처리 소요 ms. */
    val durationMs: Long,
    /** 응답 생성 시각. */
    val generatedAt: Instant = Instant.now(),
    /** 캐시 hit 여부 (post-MVP — 현재는 항상 false). */
    val cached: Boolean = false,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be 0.0..1.0, got $confidence" }
        require(text.isNotBlank()) { "text must not be blank" }
    }

    /** UI·로그 노출용 짧은 요약 (text 첫 80자). */
    fun summary(maxChars: Int = 80): String =
        text.replace("\n", " ").take(maxChars).let {
            if (text.length > maxChars) "$it..." else it
        }

    data class TokensUsed(
        val input: Int = 0,
        val output: Int = 0,
    ) {
        val total: Int get() = input + output
    }

    companion object {
        /** 모든 응답에 자동 prepend되는 면책 문구. */
        const val DISCLAIMER =
            "[면책] 본 분석은 AI 옵션 보조용이며 매매 결정·추천이 아닙니다. " +
                "투자 책임은 사용자에게 있습니다."
    }
}
