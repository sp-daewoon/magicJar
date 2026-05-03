package com.mj.api.controller.dto

import com.mj.domain.ai.analysis.AnalysisResult
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(
    description = """
        AI 분석 보조 응답 (T16, ADR 0046).

        **분석 보조용 — 매매 결정·추천이 아닙니다.**
        본 시스템의 AI는 사용자 명시 트리거에서만 호출되며 어떤 응답도 자동으로 BUY/SELL 트리거를 발생시키지 않습니다.
    """,
)
data class AnalysisResponse(
    @field:Schema(
        description = "한국어 자연어 본문 (마지막 줄에 면책 문구 포함)",
        example = "본 종목은 단기 추세가 우상향 흐름을 보이고 있습니다. ... [면책] 본 분석은 AI 옵션 보조용이며 ...",
    )
    val text: String,
    @field:Schema(description = "응답 신뢰도 (0.0~1.0). 어댑터 기본값 0.6, mock=0.0", example = "0.6")
    val confidence: Double,
    @field:Schema(description = "사용 모델 식별자", example = "claude-sonnet-4-5")
    val model: String,
    @field:Schema(description = "프롬프트 카탈로그 버전", example = "v1")
    val promptVersion: String,
    @field:Schema(description = "사용 토큰 (input + output)")
    val tokensUsed: TokensUsedDto,
    @field:Schema(description = "추정 비용 USD (mock = 0.0)", example = "0.0042")
    val costEstimate: Double,
    @field:Schema(description = "처리 소요 ms", example = "850")
    val durationMs: Long,
    @field:Schema(description = "응답 생성 시각 (UTC)")
    val generatedAt: Instant,
    @field:Schema(description = "캐시 hit 여부 (post-MVP)", example = "false")
    val cached: Boolean,
    @field:Schema(description = "provider 식별자", example = "anthropic")
    val provider: String,
)

@Schema(description = "토큰 사용량")
data class TokensUsedDto(
    @field:Schema(example = "120")
    val input: Int,
    @field:Schema(example = "80")
    val output: Int,
    @field:Schema(example = "200")
    val total: Int,
)

fun AnalysisResult.toResponse(provider: String): AnalysisResponse =
    AnalysisResponse(
        text = text,
        confidence = confidence,
        model = model,
        promptVersion = promptVersion,
        tokensUsed =
            TokensUsedDto(
                input = tokensUsed.input,
                output = tokensUsed.output,
                total = tokensUsed.total,
            ),
        costEstimate = costEstimate,
        durationMs = durationMs,
        generatedAt = generatedAt,
        cached = cached,
        provider = provider,
    )

// ---- 요청 DTO ----

@Schema(description = "종목 코멘트 요청 컨텍스트")
data class SymbolCommentaryRequest(
    @field:Schema(description = "사용자 ID (multi-tenant)", example = "1")
    val userId: Long,
    @field:Schema(description = "종목명(한글)", example = "삼성전자")
    val nameKr: String? = null,
    @field:Schema(description = "섹터", example = "반도체")
    val sector: String? = null,
    @field:Schema(description = "최근 종가", example = "72500")
    val lastClose: Double? = null,
    @field:Schema(description = "1일 등락률 %", example = "1.2")
    val change1d: Double? = null,
    @field:Schema(description = "5일 등락률 %", example = "3.4")
    val change5d: Double? = null,
    @field:Schema(description = "관찰 패턴 ID 목록", example = "[\"GOLDEN_CROSS\", \"VOLUME_SPIKE\"]")
    val patterns: List<String> = emptyList(),
    @field:Schema(description = "최근 뉴스 헤드라인 (최대 5개 사용)")
    val recentNewsHeadlines: List<String> = emptyList(),
)

@Schema(description = "기업 분석 요청 컨텍스트")
data class CompanyAnalysisRequest(
    @field:Schema(example = "1")
    val userId: Long,
    @field:Schema(description = "PER", example = "12.4")
    val per: Double? = null,
    @field:Schema(description = "PBR", example = "1.1")
    val pbr: Double? = null,
    @field:Schema(description = "ROE", example = "0.14")
    val roe: Double? = null,
    @field:Schema(description = "시가총액 (원)", example = "430000000000000")
    val marketCap: Long? = null,
)

@Schema(description = "차트 해석 요청 컨텍스트")
data class ChartInterpretationRequest(
    @field:Schema(example = "1")
    val userId: Long,
    @field:Schema(description = "봉 단위", example = "DAY")
    val interval: String,
    @field:Schema(description = "최근 캔들 (최대 30개 사용)")
    val candles: List<CandleSnapshotDto> = emptyList(),
    @field:Schema(description = "표시 지표 단일 값 묶음 (RSI14: 65.2 등)")
    val indicators: Map<String, Double> = emptyMap(),
)

@Schema(description = "캔들 스냅샷")
data class CandleSnapshotDto(
    @field:Schema(description = "시각 (KST yyyy-MM-dd HH:mm:ss 또는 yyyy-MM-dd)", example = "2026-04-30")
    val time: String,
    @field:Schema(example = "72000")
    val open: Double,
    @field:Schema(example = "73500")
    val high: Double,
    @field:Schema(example = "71800")
    val low: Double,
    @field:Schema(example = "72500")
    val close: Double,
    @field:Schema(example = "12345678")
    val volume: Long,
)

@Schema(description = "지표 의미 설명 요청")
data class IndicatorMeaningRequest(
    @field:Schema(example = "1")
    val userId: Long,
    @field:Schema(description = "지표 파라미터 (length=14 등)")
    val params: Map<String, String> = emptyMap(),
)

@Schema(description = "매매 복기 요청 (journal entry 컨텍스트)")
data class JournalRetrospectiveRequest(
    @field:Schema(example = "1")
    val userId: Long,
    @field:Schema(example = "005930")
    val symbol: String,
    @field:Schema(description = "BUY · SELL", example = "BUY")
    val side: String,
    @field:Schema(example = "70000")
    val entryPrice: Double,
    @field:Schema(example = "72500")
    val exitPrice: Double? = null,
    @field:Schema(description = "손익 %", example = "3.57")
    val pnlPct: Double? = null,
    @field:Schema(description = "진입 사유", example = "5/20 골든크로스 + 거래량 폭증")
    val rationale: String? = null,
    @field:Schema(description = "진입 시각 (ISO-8601)", example = "2026-04-25T09:00:00Z")
    val openedAt: Instant,
    @field:Schema(description = "청산 시각 (ISO-8601, 미청산 시 null)", example = "2026-04-30T14:30:00Z")
    val closedAt: Instant? = null,
)
