package com.mj.api.application.backtest

import com.mj.api.infrastructure.persistence.backtest.BacktestEquityPointRepository
import com.mj.api.infrastructure.persistence.backtest.BacktestRunRepository
import com.mj.api.infrastructure.persistence.backtest.BacktestTradeRepository
import org.springframework.stereotype.Service

/**
 * 백테스트 상세 조회 서비스.
 *
 * - equity curve: 봉 단위 자산곡선 (track9 R1).
 * - trades: 라운드트립 거래 목록.
 *
 * runId가 존재하지 않으면 null 반환 → 컨트롤러 404 매핑.
 * runId는 존재하지만 데이터가 없으면 빈 리스트 응답.
 */
@Service
class BacktestDetailQueryService(
    private val runRepository: BacktestRunRepository,
    private val tradeRepository: BacktestTradeRepository,
    private val equityRepository: BacktestEquityPointRepository,
) {
    fun getEquityCurve(runId: String): EquityCurveResponse? {
        runRepository.findByRunId(runId) ?: return null
        val points = equityRepository.findByIdRunIdOrderByIdSequenceAsc(runId)
        return EquityCurveResponse(
            runId = runId,
            count = points.size,
            points =
                points.map {
                    EquityPointDto(
                        sequence = it.id.sequence,
                        at = it.at.toString(),
                        equity = it.equity,
                        cash = it.cash,
                        positionValue = it.positionValue,
                    )
                },
        )
    }

    fun getTrades(runId: String): TradesResponse? {
        runRepository.findByRunId(runId) ?: return null
        val trades = tradeRepository.findByRunIdOrderBySequenceAsc(runId)
        return TradesResponse(
            runId = runId,
            count = trades.size,
            trades =
                trades.map {
                    TradeDto(
                        tradeId = it.tradeId,
                        sequence = it.sequence,
                        symbol = it.symbol,
                        entryAt = it.entryAt.toString(),
                        exitAt = it.exitAt.toString(),
                        entryPrice = it.entryPrice,
                        exitPrice = it.exitPrice,
                        shares = it.shares,
                        pnl = it.pnl,
                        pnlPercent = it.pnlPercent,
                        entryCommission = it.entryCommission,
                        exitCommission = it.exitCommission,
                        exitTax = it.exitTax,
                        exitReason = it.exitReason,
                    )
                },
        )
    }
}
