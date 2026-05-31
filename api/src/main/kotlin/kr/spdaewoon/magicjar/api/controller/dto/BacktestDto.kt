package kr.spdaewoon.magicjar.api.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

// ─── 요청 DTO ────────────────────────────────────────────────────────────────

/**
 * 백테스트 실행 요청 DTO (ADR 0056 §5-1 LOCK — Swagger 4단 어노테이션 정합).
 */
@Schema(description = "백테스트 실행 요청")
data class BacktestRunRequest(
    @field:Schema(description = "전략 ID", example = "12", required = true)
    val strategyId: Long,
    @field:Schema(description = "유니버스 종목코드 리스트", example = "[\"005930\",\"000660\"]", required = true)
    val universe: List<String>,
    @field:Schema(description = "캔들 인터벌 (1m · 3m · 5m · 15m · 30m · 90m · 1h · 4h · 12h · 1d · 1wk · 1mo · 1y)", example = "1d")
    val interval: String = "1d",
    @field:Schema(description = "시작일 (포함, yyyy-MM-dd)", example = "2024-01-01", required = true)
    val from: String,
    @field:Schema(description = "종료일 (포함, yyyy-MM-dd)", example = "2025-01-01", required = true)
    val to: String,
    @field:Schema(description = "초기 자본 (KRW)", example = "10000000", required = true)
    val initialCapital: BigDecimal,
    @field:Schema(description = "세금 모델 (korean_default · none)", example = "korean_default")
    val taxModel: String = "korean_default",
    @field:Schema(description = "수수료 모델 (kis_direct · none)", example = "kis_direct")
    val commissionModel: String = "kis_direct",
    @field:Schema(description = "슬리피지 모델 (fixed_0.05 · none)", example = "fixed_0.05")
    val slippageModel: String = "fixed_0.05",
    @field:Schema(description = "가격 모드 (RAW · ADJUSTED)", example = "ADJUSTED")
    val priceAdjustmentMode: String = "ADJUSTED",
)

// ─── 응답 DTO ────────────────────────────────────────────────────────────────

/**
 * 백테스트 실행 응답 DTO (ADR 0056 §5-1 LOCK).
 */
@Schema(description = "백테스트 실행 응답")
data class BacktestRunResponse(
    @field:Schema(description = "결과 상태 (SUCCESS · ABORTED)", example = "SUCCESS")
    val status: String,
    @field:Schema(description = "매매 기록")
    val trades: List<BacktestTradeDto>,
    @field:Schema(description = "포지션 기록 (종료 + 미종료)")
    val positions: List<BacktestPositionDto>,
    @field:Schema(description = "최종 자본 (KRW)", example = "11500000")
    val finalCapital: BigDecimal,
    @field:Schema(description = "성과 지표")
    val performance: BacktestPerformanceDto?,
    @field:Schema(description = "abort 사유 (status=ABORTED 시)", example = "TIMEOUT_QUOTA_EXCEEDED")
    val abortReason: String? = null,
    @field:Schema(description = "실행 소요 시간 (ms)", example = "3210")
    val durationMs: Long,
)

/**
 * 매매 기록 DTO.
 */
@Schema(description = "백테스트 매매 기록")
data class BacktestTradeDto(
    @field:Schema(description = "종목코드", example = "005930")
    val code: String,
    @field:Schema(description = "매매 방향 (BUY · SELL)", example = "BUY")
    val side: String,
    @field:Schema(description = "거래 수량", example = "10")
    val quantity: Long,
    @field:Schema(description = "슬리피지 적용 체결가", example = "70350.00")
    val executedPrice: BigDecimal,
    @field:Schema(description = "슬리피지 적용 전 캔들 종가", example = "70000.00")
    val rawPrice: BigDecimal,
    @field:Schema(description = "체결 시각 (ISO 8601)", example = "2024-01-03T00:00:00Z")
    val executedAt: String,
    @field:Schema(description = "수수료 (KRW)", example = "1155")
    val commission: BigDecimal,
    @field:Schema(description = "세금 (KRW)", example = "1260")
    val tax: BigDecimal,
    @field:Schema(description = "슬리피지 금액 (KRW)", example = "3500")
    val slippage: BigDecimal,
)

/**
 * 포지션 DTO.
 */
@Schema(description = "백테스트 포지션")
data class BacktestPositionDto(
    @field:Schema(description = "종목코드", example = "005930")
    val code: String,
    @field:Schema(description = "보유 수량", example = "10")
    val quantity: Long,
    @field:Schema(description = "평균 매수가", example = "70350.00")
    val avgEntryPrice: BigDecimal,
    @field:Schema(description = "오픈 시각", example = "2024-01-03T00:00:00Z")
    val openedAt: String,
    @field:Schema(description = "종료 시각 (null=현재 보유)", example = "2024-06-01T00:00:00Z")
    val closedAt: String?,
    @field:Schema(description = "실현 손익 (KRW)", example = "125000")
    val realizedPnl: BigDecimal,
)

/**
 * 성과 지표 DTO.
 */
@Schema(description = "백테스트 성과 지표")
data class BacktestPerformanceDto(
    @field:Schema(description = "총수익률", example = "0.1500")
    val totalReturn: BigDecimal,
    @field:Schema(description = "연환산 수익률 (CAGR)", example = "0.1260")
    val cagr: BigDecimal,
    @field:Schema(description = "최대 낙폭 MDD (양수)", example = "0.0820")
    val maxDrawdown: BigDecimal,
    @field:Schema(description = "Sharpe 비율 (n<30 시 null)", example = "1.2340")
    val sharpeRatio: BigDecimal?,
    @field:Schema(description = "승률", example = "0.6000")
    val winRate: BigDecimal,
    @field:Schema(description = "총 거래 수", example = "15")
    val totalTrades: Int,
    @field:Schema(description = "이익 거래 수", example = "9")
    val winningTrades: Int,
    @field:Schema(description = "손실 거래 수", example = "6")
    val losingTrades: Int,
    @field:Schema(description = "평균 이익 금액 (KRW)", example = "250000")
    val avgWin: BigDecimal,
    @field:Schema(description = "평균 손실 금액 (KRW, 양수)", example = "120000")
    val avgLoss: BigDecimal,
    @field:Schema(description = "Profit Factor (sumLoss==0 시 null)", example = "3.1250")
    val profitFactor: BigDecimal?,
    @field:Schema(description = "매매 빈도 = 거래 수 / 영업일수", example = "0.0600")
    val tradingFrequency: BigDecimal,
)
