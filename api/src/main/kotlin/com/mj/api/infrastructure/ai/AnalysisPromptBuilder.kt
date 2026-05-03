package com.mj.api.infrastructure.ai

import com.mj.domain.ai.analysis.AnalysisRequest

/**
 * [AnalysisRequest] sealed → (system, user) 프롬프트 페어 변환.
 *
 * - system: [AnalysisPromptLoader.SYSTEM_BASE_PATH] (공통 한국어·면책 룰)
 * - user: 타입별 `*.st` + 변수 치환
 */
class AnalysisPromptBuilder(
    private val loader: AnalysisPromptLoader,
) {
    fun build(req: AnalysisRequest): Pair<String, String> {
        val systemPrompt = loader.systemBase()
        val userPrompt =
            when (req) {
                is AnalysisRequest.SymbolCommentary -> renderSymbolCommentary(req)
                is AnalysisRequest.CompanyAnalysis -> renderCompanyAnalysis(req)
                is AnalysisRequest.ChartInterpretation -> renderChartInterpretation(req)
                is AnalysisRequest.IndicatorMeaning -> renderIndicatorMeaning(req)
                is AnalysisRequest.JournalRetrospective -> renderJournalRetrospective(req)
            }
        return systemPrompt to userPrompt
    }

    private fun renderSymbolCommentary(req: AnalysisRequest.SymbolCommentary): String {
        val ctx = req.contextSnapshot
        val newsBlock =
            if (ctx.recentNewsHeadlines.isEmpty()) {
                "(최근 헤드라인 없음)"
            } else {
                ctx.recentNewsHeadlines.take(MAX_NEWS).joinToString("\n") { "- $it" }
            }
        return loader.render(
            AnalysisPromptLoader.SYMBOL_COMMENTARY_PATH,
            mapOf(
                "code" to req.code,
                "nameKr" to (ctx.nameKr ?: "(미상)"),
                "sector" to (ctx.sector ?: "(미상)"),
                "lastClose" to ctx.lastClose?.toString().orElseDataMissing(),
                "change1d" to ctx.change1d?.toString().orElseDataMissing(),
                "change5d" to ctx.change5d?.toString().orElseDataMissing(),
                "patterns" to ctx.patterns.joinToString(",").ifBlank { "(없음)" },
                "recentNews" to newsBlock,
            ),
        )
    }

    private fun renderCompanyAnalysis(req: AnalysisRequest.CompanyAnalysis): String {
        val f = req.financialsHint
        return loader.render(
            AnalysisPromptLoader.COMPANY_ANALYSIS_PATH,
            mapOf(
                "code" to req.code,
                "per" to f?.per?.toString().orElseDataMissing(),
                "pbr" to f?.pbr?.toString().orElseDataMissing(),
                "roe" to f?.roe?.toString().orElseDataMissing(),
                "marketCap" to f?.marketCap?.toString().orElseDataMissing(),
            ),
        )
    }

    private fun renderChartInterpretation(req: AnalysisRequest.ChartInterpretation): String {
        val candles =
            req.candles
                .takeLast(MAX_CANDLES)
                .joinToString("\n") {
                    "${it.time} | O=${it.open} H=${it.high} L=${it.low} C=${it.close} V=${it.volume}"
                }.ifBlank { "(캔들 데이터 없음)" }
        val indicators =
            req.indicators.entries
                .joinToString("\n") { "- ${it.key}: ${it.value}" }
                .ifBlank { "(표시 지표 없음)" }
        return loader.render(
            AnalysisPromptLoader.CHART_INTERPRETATION_PATH,
            mapOf(
                "code" to req.code,
                "interval" to req.interval,
                "candleTable" to candles,
                "indicatorTable" to indicators,
            ),
        )
    }

    private fun renderIndicatorMeaning(req: AnalysisRequest.IndicatorMeaning): String {
        val params =
            req.params.entries
                .joinToString(", ") { "${it.key}=${it.value}" }
                .ifBlank { "(기본값)" }
        return loader.render(
            AnalysisPromptLoader.INDICATOR_MEANING_PATH,
            mapOf(
                "indicatorId" to req.indicatorId,
                "params" to params,
            ),
        )
    }

    private fun renderJournalRetrospective(req: AnalysisRequest.JournalRetrospective): String {
        val j = req.journalEntry
        return loader.render(
            AnalysisPromptLoader.JOURNAL_RETROSPECTIVE_PATH,
            mapOf(
                "symbol" to j.symbol,
                "side" to j.side,
                "entryPrice" to j.entryPrice.toString(),
                "exitPrice" to j.exitPrice?.toString().orElseDataMissing(),
                "pnlPct" to j.pnlPct?.toString().orElseDataMissing(),
                "rationale" to (j.rationale ?: "(미기재)"),
                "openedAt" to j.openedAt.toString(),
                "closedAt" to j.closedAt?.toString().orElseDataMissing(),
            ),
        )
    }

    private fun String?.orElseDataMissing(): String = this ?: "(데이터 부족)"

    companion object {
        const val MAX_NEWS = 5
        const val MAX_CANDLES = 30
    }
}
