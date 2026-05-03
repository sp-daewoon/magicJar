package com.mj.api.infrastructure.persistence.journal

import com.mj.domain.journal.performance.port.RealizedTradeRepository
import com.mj.domain.strategy.backtest.Trade
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * [RealizedTradeRepository] 1차 stub adapter (R1).
 *
 * orders + order_events FILL 매핑 layer는 R2에서 본격 구현. 1차는:
 * - findClosedTrades: 빈 list 반환 (실거래 없음을 가정)
 * - findInitialCapital: user_trading_config 또는 default 1000만원
 *
 * R2에서 이 클래스를 `JpaRealizedTradeRepositoryAdapter`로 교체:
 * - OrderRepository.findByUserAndDateRange + OrderEventRepository.findByEventType=FILLED
 * - 종목별 FIFO 매칭으로 BUY-SELL 라운드트립 생성
 * - T9 [Trade.create] 호출로 pnl·pnlPercent 계산
 *
 * Profile 분기 없이 default 등록. R2 실 어댑터 도입 시 @Profile 분리.
 */
@Component
class StubRealizedTradeRepositoryAdapter : RealizedTradeRepository {
    override fun findClosedTrades(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<Trade> = emptyList()

    override fun findInitialCapital(userId: UUID): BigDecimal = BigDecimal("10000000")
}
