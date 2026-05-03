package com.mj.api.infrastructure.ai

import com.mj.domain.ai.analysis.AnalysisRequest
import com.mj.domain.ai.analysis.AnalysisResult
import com.mj.domain.ai.analysis.port.AnalysisAssistant

/**
 * mock 환경 또는 ANTHROPIC_API_KEY 미설정 시 등록되는 fixture 어댑터.
 *
 * - LLM 호출 0 — 비용 0
 * - 항상 동일 형태의 한국어 안내 메시지 반환 (UI는 "분석 옵션이 비활성된 상태입니다." 표시)
 * - 면책 문구 포함, [Result.success]만 반환 (graceful)
 */
class MockAnalysisAssistant : AnalysisAssistant {
    override val providerName: String = "mock"

    override fun analyze(request: AnalysisRequest): Result<AnalysisResult> {
        val typeLabel = labelOf(request)
        val text =
            buildString {
                appendLine("[$typeLabel] 이 종목은 분석 옵션이 비활성된 상태입니다.")
                appendLine("AI 분석 보조를 사용하려면 운영자에게 ANTHROPIC_API_KEY 설정 또는 magicjar.ai.enabled=true 활성을 요청하세요.")
                appendLine("현재는 본체(룰 기반 시그널·매매일지·차트) 흐름만 활용 가능합니다.")
                appendLine()
                append(AnalysisResult.DISCLAIMER)
            }.trim()

        return Result.success(
            AnalysisResult(
                text = text,
                confidence = 0.0,
                model = "mock",
                promptVersion = AnalysisPromptLoader.PROMPT_VERSION,
                tokensUsed = AnalysisResult.TokensUsed(input = 0, output = 0),
                costEstimate = 0.0,
                durationMs = 0,
            ),
        )
    }

    private fun labelOf(req: AnalysisRequest): String =
        when (req) {
            is AnalysisRequest.SymbolCommentary -> "종목 코멘트"
            is AnalysisRequest.CompanyAnalysis -> "기업 분석"
            is AnalysisRequest.ChartInterpretation -> "차트 해석"
            is AnalysisRequest.IndicatorMeaning -> "지표 의미"
            is AnalysisRequest.JournalRetrospective -> "매매 복기"
        }
}
