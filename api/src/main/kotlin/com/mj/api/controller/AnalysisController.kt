package com.mj.api.controller

import com.mj.api.application.ai.AnalysisQueryService
import com.mj.api.application.ai.AnalysisUnavailableException
import com.mj.api.controller.dto.AnalysisResponse
import com.mj.api.controller.dto.ChartInterpretationRequest
import com.mj.api.controller.dto.CompanyAnalysisRequest
import com.mj.api.controller.dto.IndicatorMeaningRequest
import com.mj.api.controller.dto.JournalRetrospectiveRequest
import com.mj.api.controller.dto.SymbolCommentaryRequest
import com.mj.api.controller.dto.toResponse
import com.mj.domain.ai.analysis.AnalysisRequest
import com.mj.domain.ai.analysis.port.AnalysisAssistant
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * AI 분석 보조 — 사용자 트리거 한정 (T16, ADR 0046).
 *
 * **AI 옵션 — 매매 결정권 0.** 어떤 응답도 자동으로 BUY/SELL 트리거를 발생시키지 않는다.
 * 모든 endpoint는 사용자 명시 클릭에서만 호출되며, 응답 본문에 면책 문구가 포함된다.
 *
 * graceful degradation:
 * - assistant 빈 미등록 (mock-mode=real + 키 없음, 또는 enabled=false) → 503
 * - LLM 호출 실패 → 503 + audit 로그 INSERT
 */
@Tag(
    name = "AI Analysis",
    description = """
        **AI 옵션 — 매매 결정권 X (ADR 0046).**

        사용자 명시 클릭에서만 호출되는 분석 보조 API. 5종 endpoint:

        1. POST /api/analysis/symbol/{code}/commentary — 종목 코멘트
        2. POST /api/analysis/symbol/{code}/company — 기업 분석
        3. POST /api/analysis/symbol/{code}/chart — 차트 해석
        4. POST /api/analysis/indicator/{id}/explain — 지표 의미
        5. POST /api/analysis/journal/{tradeId}/retrospective — 매매 복기

        모든 응답 본문에는 자동으로 면책 문구가 포함된다.
        AI 비활성·실패 시 503 — 본체 흐름(시그널·주문·차트)은 영향 받지 않는다.
    """,
)
@RestController
@RequestMapping("/api/analysis")
class AnalysisController(
    private val service: AnalysisQueryService,
    /**
     * 빈 부재 여부 확인용 — controller가 503 응답 시 명시 메시지 생성에 사용.
     * (service는 ObjectProvider로 우회하지만 controller도 swagger·로그용으로 보유.)
     */
    private val assistantProvider: ObjectProvider<AnalysisAssistant>,
) {
    @Operation(
        summary = "종목 코멘트 (AI 옵션)",
        description = """
            현재 종목의 단기 추세·주의 포인트·장기 검토 지점을 한국어 평문으로 코멘트한다.
            **분석 보조용 — 매매 결정 X.**

            사용자 명시 클릭으로만 호출 (UI: /symbol/:code "AI 종목 분석" 버튼).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "분석 응답 (면책 포함)"),
            ApiResponse(responseCode = "503", description = "AI 비활성 또는 일시 호출 실패 — 본체 흐름은 정상"),
        ],
    )
    @PostMapping("/symbol/{code}/commentary")
    fun symbolCommentary(
        @Parameter(description = "6자리 종목 코드", example = "005930")
        @PathVariable code: String,
        @RequestBody body: SymbolCommentaryRequest,
    ): ResponseEntity<AnalysisResponse> {
        val req =
            AnalysisRequest.SymbolCommentary(
                userId = body.userId,
                code = code,
                contextSnapshot =
                    AnalysisRequest.SymbolContextSnapshot(
                        nameKr = body.nameKr,
                        sector = body.sector,
                        lastClose = body.lastClose,
                        change1d = body.change1d,
                        change5d = body.change5d,
                        patterns = body.patterns,
                        recentNewsHeadlines = body.recentNewsHeadlines,
                    ),
            )
        return ResponseEntity.ok(service.analyze(req).toResponse(currentProvider()))
    }

    @Operation(
        summary = "기업 분석 (AI 옵션)",
        description = """
            PER·PBR·ROE·시가총액 등 펀더멘털 단편을 바탕으로 일반적 관점의 코멘트.
            **분석 보조용 — 매매 결정 X.** 데이터 부재 시 LLM이 "데이터 부족"으로 명시한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "분석 응답"),
            ApiResponse(responseCode = "503", description = "AI 비활성 또는 일시 호출 실패"),
        ],
    )
    @PostMapping("/symbol/{code}/company")
    fun companyAnalysis(
        @Parameter(description = "6자리 종목 코드", example = "005930")
        @PathVariable code: String,
        @RequestBody body: CompanyAnalysisRequest,
    ): ResponseEntity<AnalysisResponse> {
        val req =
            AnalysisRequest.CompanyAnalysis(
                userId = body.userId,
                code = code,
                financialsHint =
                    if (body.per == null && body.pbr == null && body.roe == null && body.marketCap == null) {
                        null
                    } else {
                        AnalysisRequest.FinancialsHint(
                            per = body.per,
                            pbr = body.pbr,
                            roe = body.roe,
                            marketCap = body.marketCap,
                        )
                    },
            )
        return ResponseEntity.ok(service.analyze(req).toResponse(currentProvider()))
    }

    @Operation(
        summary = "차트 해석 (AI 옵션)",
        description = """
            현재 표시 중인 캔들·지표 컨텍스트로 추세·과열/과매도·다음 캔들 체크포인트 코멘트.
            **분석 보조용 — 매매 결정 X.** 단정·예언 금지 룰을 프롬프트에 강제.

            사용자 명시 클릭으로만 호출 (UI: /symbol/:code 차트 영역 "차트 해석" 버튼).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "분석 응답"),
            ApiResponse(responseCode = "503", description = "AI 비활성 또는 일시 호출 실패"),
        ],
    )
    @PostMapping("/symbol/{code}/chart")
    fun chartInterpretation(
        @Parameter(description = "6자리 종목 코드", example = "005930")
        @PathVariable code: String,
        @RequestBody body: ChartInterpretationRequest,
    ): ResponseEntity<AnalysisResponse> {
        val req =
            AnalysisRequest.ChartInterpretation(
                userId = body.userId,
                code = code,
                interval = body.interval,
                candles =
                    body.candles.map {
                        AnalysisRequest.CandleSnapshot(
                            time = it.time,
                            open = it.open,
                            high = it.high,
                            low = it.low,
                            close = it.close,
                            volume = it.volume,
                        )
                    },
                indicators = body.indicators,
            )
        return ResponseEntity.ok(service.analyze(req).toResponse(currentProvider()))
    }

    @Operation(
        summary = "지표 의미 설명 (AI 옵션)",
        description = """
            지표 ID + 파라미터를 받아 일반 투자자 관점에서 의미·시그널·한계를 한국어로 설명.
            **분석 보조용 — 매매 결정 X.** 교육 목적 코멘트.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "지표 설명"),
            ApiResponse(responseCode = "503", description = "AI 비활성 또는 일시 호출 실패"),
        ],
    )
    @PostMapping("/indicator/{id}/explain")
    fun indicatorMeaning(
        @Parameter(description = "지표 ID (예: RSI, MACD, BB)", example = "RSI")
        @PathVariable id: String,
        @RequestBody body: IndicatorMeaningRequest,
    ): ResponseEntity<AnalysisResponse> {
        val req =
            AnalysisRequest.IndicatorMeaning(
                userId = body.userId,
                indicatorId = id,
                params = body.params,
            )
        return ResponseEntity.ok(service.analyze(req).toResponse(currentProvider()))
    }

    @Operation(
        summary = "매매일지 복기 (AI 옵션)",
        description = """
            트레이드 1건의 진입 사유·결과·체크포인트를 한국어 평문으로 복기.
            **분석 보조용 — 매매 결정 X.** 인격적 비난·심리 단정 금지 룰을 프롬프트에 강제.

            사용자 명시 클릭으로만 호출 (UI: 매매일지 항목 "AI 복기" 버튼).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "복기 응답"),
            ApiResponse(responseCode = "503", description = "AI 비활성 또는 일시 호출 실패"),
        ],
    )
    @PostMapping("/journal/{tradeId}/retrospective")
    fun journalRetrospective(
        @Parameter(description = "trade row PK", example = "42")
        @PathVariable tradeId: Long,
        @RequestBody body: JournalRetrospectiveRequest,
    ): ResponseEntity<AnalysisResponse> {
        val req =
            AnalysisRequest.JournalRetrospective(
                userId = body.userId,
                tradeId = tradeId,
                journalEntry =
                    AnalysisRequest.JournalSnapshot(
                        symbol = body.symbol,
                        side = body.side,
                        entryPrice = body.entryPrice,
                        exitPrice = body.exitPrice,
                        pnlPct = body.pnlPct,
                        rationale = body.rationale,
                        openedAt = body.openedAt,
                        closedAt = body.closedAt,
                    ),
            )
        return ResponseEntity.ok(service.analyze(req).toResponse(currentProvider()))
    }

    private fun currentProvider(): String = assistantProvider.getIfAvailable()?.providerName ?: "unavailable"

    /** [AnalysisUnavailableException] → 503 + 본문에 명시 메시지. 본체 흐름 차단 X. */
    @ExceptionHandler(AnalysisUnavailableException::class)
    fun handleUnavailable(ex: AnalysisUnavailableException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            mapOf(
                "error" to "ANALYSIS_UNAVAILABLE",
                "message" to (ex.message ?: "AI 분석 보조가 일시 불가합니다."),
            ),
        )
}
