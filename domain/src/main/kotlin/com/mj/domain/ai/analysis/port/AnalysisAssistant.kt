package com.mj.domain.ai.analysis.port

import com.mj.domain.ai.analysis.AnalysisRequest
import com.mj.domain.ai.analysis.AnalysisResult

/**
 * AI 분석 보조 — 사용자 트리거 한정 (ADR 0046).
 *
 * 어댑터 구현 의무:
 * - **graceful failure**: 호출 실패·타임아웃은 [Result.failure] 반환. 예외 throw 금지.
 * - **AI 매매 결정권 0**: 응답 본문에 [AnalysisResult.DISCLAIMER] 면책 포함.
 * - **자동 호출 금지**: KafkaListener·Scheduled 흐름에서 직접 의존 금지 (REST 사용자 트리거에서만 의존).
 * - **한국어 응답**: 모든 [AnalysisResult.text]는 한국어로 작성.
 *
 * mock 환경 또는 API key 미설정 시 [com.mj.domain.ai.analysis.port.AnalysisAssistant] 빈은
 * `MockAnalysisAssistant` 형태로 등록되어 고정 fixture 응답을 반환한다.
 */
interface AnalysisAssistant {
    /** provider 식별자 — `anthropic` · `mock` · `gemini` 등. log·audit·UI 표시용. */
    val providerName: String

    /**
     * 분석 요청 1건 처리.
     *
     * @return [AnalysisResult] 성공 또는 [Throwable] 실패. 호출자는 본체 흐름을 차단하지 말 것.
     */
    fun analyze(request: AnalysisRequest): Result<AnalysisResult>
}
