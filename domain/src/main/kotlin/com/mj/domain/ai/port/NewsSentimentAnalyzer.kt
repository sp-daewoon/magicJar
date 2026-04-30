package com.mj.domain.ai.port

import com.mj.domain.ai.SentimentScore
import java.time.Instant
import java.util.UUID

/**
 * 단일 뉴스 한 건을 LLM으로 감성 분석하는 inbound port.
 *
 * - 구현체는 인프라 모듈에서 제공(예: Anthropic ChatClient).
 * - LLM 비활성·키 부재·parsing 실패 시 `null` 반환 — 호출 측은 결과 없이 진행 가능.
 * - 상위 application 레이어의 다중 뉴스 일괄 분석은 별도 service(`SentimentAnalyzer`)가 담당.
 */
interface NewsSentimentAnalyzer {
    fun analyze(news: NewsAnalysisInput): SentimentScore?

    data class NewsAnalysisInput(
        val newsId: UUID,
        val source: String,
        val publishedAt: Instant,
        val title: String,
        val body: String,
        val instruments: List<String> = emptyList(),
    )
}
