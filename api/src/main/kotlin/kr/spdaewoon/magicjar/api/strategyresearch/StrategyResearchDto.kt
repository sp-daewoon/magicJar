package kr.spdaewoon.magicjar.api.strategyresearch

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal

@Schema(description = "전략 리서치 후보 생성 요청")
data class StrategyResearchRequestDto(
    @field:Schema(description = "사용자 ID", example = "74683223")
    @field:NotBlank
    val userId: String,
    @field:Schema(description = "종목코드 6자리", example = "038110")
    @field:Pattern(regexp = "\\d{6}")
    val code: String,
    @field:Schema(description = "캔들 interval", example = "1m")
    @field:NotBlank
    val interval: String,
    @field:Schema(description = "분석 시작 시각 ISO-8601", example = "2026-04-28T00:00:00Z")
    @field:NotBlank
    val from: String,
    @field:Schema(description = "분석 종료 시각 ISO-8601", example = "2026-06-02T06:30:00Z")
    @field:NotBlank
    val to: String,
    @field:Schema(description = "목표 익절 퍼센트", example = "1.5")
    @field:DecimalMin("0.1")
    @field:DecimalMax("10.0")
    val targetProfitPct: Double = 1.5,
    @field:Schema(description = "손절 퍼센트", example = "0.8")
    @field:DecimalMin("0.1")
    @field:DecimalMax("10.0")
    val stopLossPct: Double = 0.8,
    @field:Schema(description = "최대 보유 candle 수", example = "12")
    @field:Min(1)
    @field:Max(120)
    val maxHoldBars: Int = 12,
)

data class StrategyResearchReportDto(
    val request: StrategyResearchRequestEchoDto,
    val candleCount: Int,
    val marketRegime: StrategyResearchMarketRegimeDto,
    val warnings: List<String>,
    val candles: List<StrategyResearchCandleDto>,
    val candidates: List<StrategyResearchCandidateDto>,
)

data class StrategyResearchRequestEchoDto(
    val userId: String,
    val code: String,
    val interval: String,
    val from: String,
    val to: String,
    val targetProfitPct: Double,
    val stopLossPct: Double,
    val maxHoldBars: Int,
)

data class StrategyResearchMarketRegimeDto(
    val type: String,
    val trendPct: Double,
    val volatilityPct: Double,
    val explanation: String,
)

data class StrategyResearchCandleDto(
    val ts: String,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
)

data class StrategyResearchCandidateDto(
    val key: String,
    val title: String,
    val summary: String,
    val marketFit: String,
    val entryRules: List<String>,
    val exitRules: List<String>,
    val riskRules: List<String>,
    val entryDslDraft: String,
    val exitDslDraft: String,
    val stats: StrategyResearchStatsDto,
    val trades: List<StrategyResearchTradeDto>,
    val score: Double,
)

data class StrategyResearchStatsDto(
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val totalReturnPct: Double,
    val avgTradeReturnPct: Double,
    val bestTradePct: Double,
    val worstTradePct: Double,
    val maxDrawdownPct: Double,
    val maxConsecutiveLosses: Int,
    val profitFactor: Double?,
)

data class StrategyResearchTradeDto(
    val signalTime: String,
    val entryTime: String,
    val exitTime: String,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val returnPct: Double,
    val exitReason: String,
    val barsHeld: Int,
    val reason: String,
)

@Schema(description = "Claude CLI 기반 AI 종목발굴 요청")
data class StrategyResearchAiDiscoveryRequestDto(
    @field:Schema(description = "사용자 ID", example = "74683223")
    @field:NotBlank
    val userId: String,
    @field:Schema(description = "찾고 싶은 종목/테마 자연어", example = "반도체 중 거래량이 붙을 만한 국내주")
    @field:NotBlank
    @field:Size(max = 500)
    val query: String,
    @field:Schema(description = "시장 필터", example = "KOSPI")
    @field:Pattern(regexp = "KOSPI|KOSDAQ|KONEX")
    val market: String? = null,
    @field:Schema(description = "후보 수", example = "6")
    @field:Min(1)
    @field:Max(12)
    val limit: Int = 6,
    @field:Schema(description = "후보별 candle 가용량 판정용 interval (옵션). 없으면 candleCount는 null.", example = "1m")
    val interval: String? = null,
    @field:Schema(description = "candle 가용량 판정 시작 시각 ISO-8601 (옵션)", example = "2026-04-28T00:00:00Z")
    val from: String? = null,
    @field:Schema(description = "candle 가용량 판정 종료 시각 ISO-8601 (옵션)", example = "2026-06-02T06:30:00Z")
    val to: String? = null,
)

data class StrategyResearchAiDiscoveryLatestDto(
    val report: StrategyResearchAiDiscoveryResponseDto?,
)

data class StrategyResearchAiDiscoveryResponseDto(
    val query: String,
    val market: String?,
    val limit: Int,
    val provider: String,
    val generatedAt: String,
    val warnings: List<String>,
    val items: List<StrategyResearchAiDiscoveryItemDto>,
)

enum class StrategyResearchAiDiscoveryItemStatus {
    NEW,
    KEPT,
    DISMISSED,
}

data class StrategyResearchAiDiscoveryItemStatusRequestDto(
    @field:Schema(description = "사용자 ID", example = "74683223")
    @field:NotBlank
    val userId: String,
    @field:Schema(description = "후보 식별 키", example = "CODE:005930")
    @field:NotBlank
    val itemKey: String,
    @field:Schema(description = "후보 상태", example = "KEPT")
    val status: StrategyResearchAiDiscoveryItemStatus,
)

data class StrategyResearchAiDiscoveryItemDto(
    val itemKey: String,
    val code: String?,
    val name: String,
    val market: String?,
    val sector: String?,
    val theme: String?,
    val aiReason: String,
    val themes: List<String>,
    val confidence: Double,
    val matched: Boolean,
    val rawName: String?,
    val rawCode: String?,
    val tradingHalt: Boolean,
    val managementStock: Boolean,
    val status: StrategyResearchAiDiscoveryItemStatus = StrategyResearchAiDiscoveryItemStatus.NEW,
    @field:Schema(description = "선택 interval·기간 내 저장된 candle 수. interval/from/to 미지정이면 null.", example = "390")
    val candleCount: Long? = null,
    @field:Schema(description = "선택 interval·기간에 리서치 가능한 candle 보유 여부. candleCount 미산정 시 null.", example = "true")
    val hasResearchCandles: Boolean? = null,
)
