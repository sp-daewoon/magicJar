package com.mj.domain.ai.analysis

import java.time.Instant

/**
 * AI 분석 요청 — 사용자 명시 트리거 한정.
 *
 * - **AI 매매 결정권 0**: 어떤 응답도 BUY/SELL 트리거 X.
 * - **자동 호출 금지**: KafkaListener·Scheduled·Signal 흐름에서 본 타입 생성 금지.
 * - **graceful failure**: AI 실패 시 본체 흐름 차단 X (호출 측은 [Result.failure] 처리).
 *
 * 5종 sealed subclass — UI 버튼 1:1 매핑.
 */
sealed class AnalysisRequest {
    /** 호출 트레이싱·중복 캐시 키. */
    abstract val userId: Long
    abstract val requestedAt: Instant

    /** 종목 코멘트 — 시장 위치·재무 흐름·주의 사항 자연어. */
    data class SymbolCommentary(
        override val userId: Long,
        val code: String,
        val contextSnapshot: SymbolContextSnapshot,
        override val requestedAt: Instant = Instant.now(),
    ) : AnalysisRequest()

    /** 기업 분석 — 재무·섹터·경쟁 등 펀더멘털 코멘트. */
    data class CompanyAnalysis(
        override val userId: Long,
        val code: String,
        val financialsHint: FinancialsHint? = null,
        override val requestedAt: Instant = Instant.now(),
    ) : AnalysisRequest()

    /** 차트 해석 — 캔들·지표 컨텍스트로 추세·과열·과매도 코멘트. */
    data class ChartInterpretation(
        override val userId: Long,
        val code: String,
        val interval: String,
        val candles: List<CandleSnapshot>,
        val indicators: Map<String, Double> = emptyMap(),
        override val requestedAt: Instant = Instant.now(),
    ) : AnalysisRequest()

    /** 지표 의미 설명. */
    data class IndicatorMeaning(
        override val userId: Long,
        val indicatorId: String,
        val params: Map<String, String> = emptyMap(),
        override val requestedAt: Instant = Instant.now(),
    ) : AnalysisRequest()

    /** 매매일지 복기 — 트레이드 1건의 의사결정·결과 자연어 평가. */
    data class JournalRetrospective(
        override val userId: Long,
        val tradeId: Long,
        val journalEntry: JournalSnapshot,
        override val requestedAt: Instant = Instant.now(),
    ) : AnalysisRequest()

    // ---- 컨텍스트 데이터 ----

    data class SymbolContextSnapshot(
        val nameKr: String? = null,
        val sector: String? = null,
        val lastClose: Double? = null,
        val change1d: Double? = null,
        val change5d: Double? = null,
        val patterns: List<String> = emptyList(),
        val recentNewsHeadlines: List<String> = emptyList(),
    )

    data class FinancialsHint(
        val per: Double? = null,
        val pbr: Double? = null,
        val roe: Double? = null,
        val marketCap: Long? = null,
    )

    data class CandleSnapshot(
        val time: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
    )

    data class JournalSnapshot(
        val symbol: String,
        val side: String, // BUY · SELL
        val entryPrice: Double,
        val exitPrice: Double?,
        val pnlPct: Double?,
        val rationale: String?,
        val openedAt: Instant,
        val closedAt: Instant?,
    )
}
