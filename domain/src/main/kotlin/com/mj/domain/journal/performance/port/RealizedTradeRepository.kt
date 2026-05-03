package com.mj.domain.journal.performance.port

import com.mj.domain.strategy.backtest.Trade
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 실거래 매매일지 trade 조회 port.
 *
 * orders + order_events fill 거래를 BUY-SELL 라운드트립으로 매핑한 Trade 목록을 반환.
 *
 * 1차(R1)는 stub adapter(빈 list)로 인프라 골격만 완성.
 * 매핑 layer 본격 구현은 R2.
 */
interface RealizedTradeRepository {
    fun findClosedTrades(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<Trade>

    fun findInitialCapital(userId: UUID): BigDecimal
}
