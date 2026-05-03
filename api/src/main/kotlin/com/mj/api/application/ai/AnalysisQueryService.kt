package com.mj.api.application.ai

import com.mj.api.infrastructure.ai.AnalysisAiProperties
import com.mj.api.infrastructure.persistence.ai.AiAnalysisLogEntity
import com.mj.api.infrastructure.persistence.ai.AiAnalysisLogJpaRepository
import com.mj.domain.ai.analysis.AnalysisRequest
import com.mj.domain.ai.analysis.AnalysisResult
import com.mj.domain.ai.analysis.port.AnalysisAssistant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

/**
 * controller ↔ domain 사이의 application service (T16 R1).
 *
 * 책임:
 * 1. [AnalysisAssistant.analyze] 호출 (assistant 빈 부재 시 [AnalysisUnavailableException] throw → controller 503)
 * 2. 모든 호출 결과를 [AiAnalysisLogEntity]에 audit (성공·실패 모두)
 * 3. graceful failure — 호출 실패는 사용자에게 [AnalysisUnavailableException]으로 명시 통보 (본체 흐름 차단 X)
 *
 * **사용처 제한**: 본 service는 controller (사용자 트리거) 에서만 호출. KafkaListener·Scheduled·Signal 흐름 호출 금지.
 */
@Service
class AnalysisQueryService(
    private val assistantProvider: ObjectProvider<AnalysisAssistant>,
    private val logRepository: AiAnalysisLogJpaRepository,
    private val props: AnalysisAiProperties,
) {
    private val log = LoggerFactory.getLogger(AnalysisQueryService::class.java)

    /**
     * 분석 요청 1건 처리 + audit.
     *
     * @throws AnalysisUnavailableException assistant 빈 미등록 (mock-mode=real + 키 없음, 또는 enabled=false).
     */
    @Transactional
    fun analyze(request: AnalysisRequest): AnalysisResult {
        val assistant =
            assistantProvider.getIfAvailable()
                ?: throw AnalysisUnavailableException(
                    "AI 분석 보조가 비활성 상태입니다 (magicjar.ai.enabled=${props.enabled}, mock-mode=${props.mockMode}).",
                )

        val outcome = assistant.analyze(request)
        val targetRef = targetRefOf(request)
        val typeName = typeNameOf(request)
        val inputSummary = summaryOf(request)

        outcome.fold(
            onSuccess = { result ->
                logRepository.save(
                    AiAnalysisLogEntity(
                        userId = request.userId,
                        requestType = typeName,
                        targetRef = targetRef,
                        inputSummary = inputSummary,
                        outputText = result.text,
                        provider = assistant.providerName,
                        model = result.model,
                        promptVersion = result.promptVersion,
                        tokensInput = result.tokensUsed.input,
                        tokensOutput = result.tokensUsed.output,
                        costUsd =
                            BigDecimal
                                .valueOf(result.costEstimate)
                                .setScale(6, RoundingMode.HALF_UP),
                        durationMs = result.durationMs,
                        success = true,
                        createdAt = OffsetDateTime.now(),
                    ),
                )
                return result
            },
            onFailure = { ex ->
                log.warn(
                    "AnalysisQueryService failure (userId={}, type={}): {}",
                    request.userId,
                    typeName,
                    ex.message,
                )
                logRepository.save(
                    AiAnalysisLogEntity(
                        userId = request.userId,
                        requestType = typeName,
                        targetRef = targetRef,
                        inputSummary = inputSummary,
                        outputText = "",
                        provider = assistant.providerName,
                        model = props.model,
                        promptVersion = "v1",
                        tokensInput = 0,
                        tokensOutput = 0,
                        costUsd = BigDecimal.ZERO,
                        durationMs = 0,
                        success = false,
                        errorMessage = ex.message?.take(MAX_ERROR_LEN),
                        createdAt = OffsetDateTime.now(),
                    ),
                )
                throw AnalysisUnavailableException(
                    "AI 분석이 일시적으로 불가합니다. 잠시 후 다시 시도해 주세요. (cause=${ex.javaClass.simpleName})",
                    ex,
                )
            },
        )
        // unreachable — fold returns/throws above.
        error("unreachable")
    }

    /** UI 히스토리 — 사용자별 최근 50건 (post-MVP에서 page·filter 보강). */
    @Transactional(readOnly = true)
    fun recentForUser(userId: Long): List<AiAnalysisLogEntity> = logRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId)

    private fun targetRefOf(req: AnalysisRequest): String =
        when (req) {
            is AnalysisRequest.SymbolCommentary -> req.code
            is AnalysisRequest.CompanyAnalysis -> req.code
            is AnalysisRequest.ChartInterpretation -> "${req.code}@${req.interval}"
            is AnalysisRequest.IndicatorMeaning -> req.indicatorId
            is AnalysisRequest.JournalRetrospective -> req.tradeId.toString()
        }

    private fun typeNameOf(req: AnalysisRequest): String =
        when (req) {
            is AnalysisRequest.SymbolCommentary -> "SYMBOL_COMMENTARY"
            is AnalysisRequest.CompanyAnalysis -> "COMPANY_ANALYSIS"
            is AnalysisRequest.ChartInterpretation -> "CHART_INTERPRETATION"
            is AnalysisRequest.IndicatorMeaning -> "INDICATOR_MEANING"
            is AnalysisRequest.JournalRetrospective -> "JOURNAL_RETROSPECTIVE"
        }

    private fun summaryOf(req: AnalysisRequest): String {
        val raw =
            when (req) {
                is AnalysisRequest.SymbolCommentary ->
                    "code=${req.code} name=${req.contextSnapshot.nameKr} sector=${req.contextSnapshot.sector} " +
                        "ch1d=${req.contextSnapshot.change1d} ch5d=${req.contextSnapshot.change5d} " +
                        "patterns=${req.contextSnapshot.patterns} news=${req.contextSnapshot.recentNewsHeadlines.size}"
                is AnalysisRequest.CompanyAnalysis ->
                    "code=${req.code} per=${req.financialsHint?.per} pbr=${req.financialsHint?.pbr} " +
                        "roe=${req.financialsHint?.roe} mc=${req.financialsHint?.marketCap}"
                is AnalysisRequest.ChartInterpretation ->
                    "code=${req.code} interval=${req.interval} candles=${req.candles.size} " +
                        "indicators=${req.indicators.keys.joinToString(",")}"
                is AnalysisRequest.IndicatorMeaning ->
                    "indicator=${req.indicatorId} params=${req.params}"
                is AnalysisRequest.JournalRetrospective ->
                    "tradeId=${req.tradeId} symbol=${req.journalEntry.symbol} side=${req.journalEntry.side} " +
                        "pnlPct=${req.journalEntry.pnlPct}"
            }
        return raw.take(MAX_SUMMARY_LEN)
    }

    companion object {
        const val MAX_SUMMARY_LEN = 500
        const val MAX_ERROR_LEN = 500
    }
}

/**
 * AI 분석 불가 — controller가 503 Service Unavailable로 변환.
 * 본체 흐름 차단 X (사용자에게 "분석 일시 불가" 안내).
 */
class AnalysisUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
