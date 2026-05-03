package com.mj.api.application.backtest

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

@Schema(description = "백테스트 자산곡선 1점.")
data class EquityPointDto(
    @field:Schema(description = "시퀀스 (시계열 인덱스)", example = "0")
    val sequence: Int,
    @field:Schema(description = "봉 시작 시각 (ISO 8601)", example = "2024-01-02T00:00:00Z")
    val at: String,
    @field:Schema(description = "자산 평가액 (원, mark-to-market)", example = "10125000.00")
    val equity: BigDecimal,
    @field:Schema(description = "현금 잔고 (원)", example = "8125000.00")
    val cash: BigDecimal,
    @field:Schema(description = "보유 포지션 평가액 (원)", example = "2000000.00")
    val positionValue: BigDecimal,
)

@Schema(description = "백테스트 자산곡선 응답.")
data class EquityCurveResponse(
    @field:Schema(description = "백테스트 실행 ID")
    val runId: String,
    @field:Schema(description = "자산곡선 점 개수")
    val count: Int,
    @field:Schema(description = "자산곡선 시계열")
    val points: List<EquityPointDto>,
)

@Schema(description = "백테스트 라운드트립 거래 1건.")
data class TradeDto(
    @field:Schema(description = "trade ID (UUID)")
    val tradeId: String,
    @field:Schema(description = "시퀀스", example = "0")
    val sequence: Int,
    @field:Schema(description = "종목 코드", example = "005930")
    val symbol: String,
    @field:Schema(description = "진입 시각 (ISO 8601)")
    val entryAt: String,
    @field:Schema(description = "청산 시각 (ISO 8601)")
    val exitAt: String,
    @field:Schema(description = "진입가 (원)", example = "71800.0000")
    val entryPrice: BigDecimal,
    @field:Schema(description = "청산가 (원)", example = "73500.0000")
    val exitPrice: BigDecimal,
    @field:Schema(description = "주식 수량", example = "100")
    val shares: Int,
    @field:Schema(description = "순손익 (원, 비용 차감 후)", example = "168000.00")
    val pnl: BigDecimal,
    @field:Schema(description = "수익률 (예: 0.0234 = 2.34%)", example = "0.02340000")
    val pnlPercent: BigDecimal,
    @field:Schema(description = "진입 수수료 (원)", example = "1077.00")
    val entryCommission: BigDecimal,
    @field:Schema(description = "청산 수수료 (원)", example = "1102.00")
    val exitCommission: BigDecimal,
    @field:Schema(description = "청산 거래세 (원)", example = "13230.00")
    val exitTax: BigDecimal,
    @field:Schema(description = "청산 사유", example = "SIGNAL_EXIT")
    val exitReason: String,
)

@Schema(description = "백테스트 거래 목록 응답.")
data class TradesResponse(
    @field:Schema(description = "백테스트 실행 ID")
    val runId: String,
    @field:Schema(description = "거래 건수")
    val count: Int,
    @field:Schema(description = "거래 목록 (sequence 오름차순)")
    val trades: List<TradeDto>,
)
