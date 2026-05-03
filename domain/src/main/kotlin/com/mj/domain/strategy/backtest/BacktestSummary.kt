package com.mj.domain.strategy.backtest

/**
 * 백테스트 단일 종목 실행 결과 (도메인 순수 산출물).
 *
 * - `trades`: 종결된 라운드트립 거래 목록.
 * - `equityCurve`: 봉 단위 자산곡선.
 * - `metrics`: 종합 성과 지표.
 * - `openPosition`: 백테스트 종료 시점 미청산 포지션. END_OF_DATA로 가상 청산하지 않으려면 null.
 * - `skippedBars`: DSL 평가 실패 등으로 스킵된 봉 인덱스 목록(룩어헤드 검증·디버깅용).
 */
data class BacktestSummary(
    val symbol: String,
    val trades: List<Trade>,
    val equityCurve: List<EquityPoint>,
    val metrics: PerformanceMetrics,
    val openPosition: BacktestPosition? = null,
    val skippedBars: List<Int> = emptyList(),
)
