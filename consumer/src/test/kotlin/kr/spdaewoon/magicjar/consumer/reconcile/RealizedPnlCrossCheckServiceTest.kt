package kr.spdaewoon.magicjar.consumer.reconcile

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderExecutionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderExecutionJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaRepository
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitPort
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitRow
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitSnapshot
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitSummary
import kr.spdaewoon.magicjar.domain.account.port.KisRealizedProfitPort
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * RealizedPnlCrossCheckService 단위 테스트 — TTTC8715R(1차 권위) 대조 + net/gross 실측 판정.
 */
class RealizedPnlCrossCheckServiceTest {
    private val execRepo = mockk<ConsumerOrderExecutionJpaRepository>()
    private val reqRepo = mockk<ConsumerOrderRequestJpaRepository>()
    private val periodPort = mockk<KisPeriodTradeProfitPort>()
    private val balancePort = mockk<KisRealizedProfitPort>(relaxed = true)
    private val service = RealizedPnlCrossCheckService(periodPort, balancePort, execRepo, reqRepo)

    private val kst = ZoneId.of("Asia/Seoul")
    private val userId = "74683223"

    private fun todayAt(
        hour: Int,
        minute: Int,
    ): Instant =
        LocalDate
            .now(kst)
            .atTime(hour, minute)
            .atZone(kst)
            .toInstant()

    private fun req(
        id: Long,
        code: String,
        side: OrderSide,
    ): ConsumerOrderRequestJpaEntity =
        ConsumerOrderRequestJpaEntity(
            id = id,
            signalId = 1L,
            userId = userId,
            code = code,
            side = side,
            qty = BigDecimal.TEN,
            priceType = OrderPriceType.MARKET,
            mode = TradingMode.REAL,
            idempotencyKey = "k$id",
            requestedAt = todayAt(9, 0),
        )

    private fun exec(
        orderId: Long,
        qty: String,
        price: String,
        fee: String = "0",
        tax: String = "0",
        at: Instant = todayAt(10, 0),
    ): ConsumerOrderExecutionJpaEntity =
        ConsumerOrderExecutionJpaEntity(
            id = orderId * 100,
            orderId = orderId,
            executedQty = BigDecimal(qty),
            executedPrice = BigDecimal(price),
            executedAt = at,
            fee = BigDecimal(fee),
            tax = BigDecimal(tax),
        )

    private fun periodSnapshot(
        rows: List<KisPeriodTradeProfitRow>,
        totalRealizedPnl: BigDecimal?,
        sellFee: BigDecimal? = null,
        sellTax: BigDecimal? = null,
    ): KisPeriodTradeProfitSnapshot {
        val today = LocalDate.now(kst)
        return KisPeriodTradeProfitSnapshot(
            userId = userId,
            mode = TradingMode.REAL,
            from = today,
            to = today,
            rows = rows,
            summary =
                KisPeriodTradeProfitSummary(
                    totalRealizedPnl = totalRealizedPnl,
                    sellFeeTotal = sellFee,
                    sellTaxTotal = sellTax,
                    totalFee = sellFee,
                    totalTax = sellTax,
                    totalProfitRate = null,
                ),
        )
    }

    private fun row(
        code: String,
        name: String,
        rlzt: String,
        fee: String? = null,
        tax: String? = null,
    ): KisPeriodTradeProfitRow =
        KisPeriodTradeProfitRow(
            tradeDate = "20260622",
            code = code,
            name = name,
            sellQty = BigDecimal.TEN,
            sellAmount = null,
            realizedPnl = BigDecimal(rlzt),
            profitRate = null,
            fee = fee?.let { BigDecimal(it) },
            tax = tax?.let { BigDecimal(it) },
            loanInterest = null,
        )

    @Test
    fun `KIS rlzt_pfls 가 gross 정의면 GROSS 일치 — 전량청산 종목도 잡힘`() {
        // 005930 매수 10@1000(fee 100), 매도 10@1100(fee 110 tax 220). gross=(1100-1000)*10=1000.
        val buy = req(1, "005930", OrderSide.BUY)
        val sell = req(2, "005930", OrderSide.SELL)
        every { execRepo.findByExecutedAtBeforeOrderByExecutedAtAsc(any()) } returns
            listOf(
                exec(1, "10", "1000", fee = "100", at = todayAt(9, 30)),
                exec(2, "10", "1100", fee = "110", tax = "220", at = todayAt(13, 0)),
            )
        every { reqRepo.findAllById(any<Iterable<Long>>()) } returns listOf(buy, sell)
        // TTTC8715R: 전량청산 종목도 매도 발생 행으로 잡힘. KIS rlzt_pfls=1000(gross 정의 가정).
        every { periodPort.fetchPeriodTradeProfit(userId, TradingMode.REAL, any(), any()) } returns
            periodSnapshot(rows = listOf(row("005930", "삼성전자", "1000")), totalRealizedPnl = BigDecimal("1000"))

        val result = service.crossCheck(userId, "DAY")

        assertThat(result.ourGrossRealizedPnl).isEqualByComparingTo(BigDecimal("1000"))
        // net: 평단 (10*1000+100)/10=1010, net=(1100-1010)*10-(110+220)=900-330=570
        assertThat(result.ourNetRealizedPnl).isEqualByComparingTo(BigDecimal("570"))
        assertThat(result.kisPeriodTotalRealizedPnl).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(result.periodDriftGross).isEqualByComparingTo(BigDecimal("0"))
        assertThat(result.matched).isTrue()
        assertThat(result.matchedDefinition).isEqualTo("GROSS")
        val r = result.perCode.single { it.code == "005930" }
        assertThat(r.presentInKis).isTrue()
        assertThat(r.matched).isTrue()
    }

    @Test
    fun `KIS rlzt_pfls 가 net 정의면 NET 일치`() {
        val buy = req(1, "005930", OrderSide.BUY)
        val sell = req(2, "005930", OrderSide.SELL)
        every { execRepo.findByExecutedAtBeforeOrderByExecutedAtAsc(any()) } returns
            listOf(
                exec(1, "10", "1000", fee = "100", at = todayAt(9, 30)),
                exec(2, "10", "1100", fee = "110", tax = "220", at = todayAt(13, 0)),
            )
        every { reqRepo.findAllById(any<Iterable<Long>>()) } returns listOf(buy, sell)
        // KIS rlzt_pfls=570(우리 net 과 동일 — KIS 가 net 정의)
        every { periodPort.fetchPeriodTradeProfit(userId, TradingMode.REAL, any(), any()) } returns
            periodSnapshot(
                rows = listOf(row("005930", "삼성전자", "570", fee = "110", tax = "220")),
                totalRealizedPnl = BigDecimal("570"),
                sellFee = BigDecimal("110"),
                sellTax = BigDecimal("220"),
            )

        val result = service.crossCheck(userId, "DAY")

        assertThat(result.periodDriftNet).isEqualByComparingTo(BigDecimal("0"))
        assertThat(result.matched).isTrue()
        assertThat(result.matchedDefinition).isEqualTo("NET")
    }

    @Test
    fun `우리 fee tax 0 미캡처면 OUR_COST_ZERO 원인 분류 + KIS 비용 안내`() {
        // 우리 체결 fee/tax=0(미캡처) → net=gross=1000. KIS net=570(비용 330 반영) → drift 430.
        val buy = req(1, "005930", OrderSide.BUY)
        val sell = req(2, "005930", OrderSide.SELL)
        every { execRepo.findByExecutedAtBeforeOrderByExecutedAtAsc(any()) } returns
            listOf(
                exec(1, "10", "1000", at = todayAt(9, 30)),
                exec(2, "10", "1100", at = todayAt(13, 0)),
            )
        every { reqRepo.findAllById(any<Iterable<Long>>()) } returns listOf(buy, sell)
        every { periodPort.fetchPeriodTradeProfit(userId, TradingMode.REAL, any(), any()) } returns
            periodSnapshot(
                rows = listOf(row("005930", "삼성전자", "570", fee = "110", tax = "220")),
                totalRealizedPnl = BigDecimal("570"),
                sellFee = BigDecimal("110"),
                sellTax = BigDecimal("220"),
            )

        val result = service.crossCheck(userId, "DAY")

        // 우리 net=gross=1000 (fee/tax 0)
        assertThat(result.ourNetRealizedPnl).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(result.ourTotalCost).isEqualByComparingTo(BigDecimal("0"))
        assertThat(result.matched).isFalse()
        assertThat(result.driftCauses).anyMatch { it.contains("OUR_COST_ZERO") }
        // KIS 매도비용 330 안내 포함
        assertThat(result.driftCauses).anyMatch { it.contains("330") }
        // 종목별 KIS fee/tax 캡처
        val r = result.perCode.single { it.code == "005930" }
        assertThat(r.kisFee).isEqualByComparingTo(BigDecimal("110"))
        assertThat(r.kisTax).isEqualByComparingTo(BigDecimal("220"))
    }

    @Test
    fun `KIS 호출 실패 시 우리 계산은 보존하고 kisCallOk=false`() {
        val buy = req(1, "005930", OrderSide.BUY)
        val sell = req(2, "005930", OrderSide.SELL)
        every { execRepo.findByExecutedAtBeforeOrderByExecutedAtAsc(any()) } returns
            listOf(exec(1, "10", "1000", at = todayAt(9, 30)), exec(2, "10", "1100", at = todayAt(13, 0)))
        every { reqRepo.findAllById(any<Iterable<Long>>()) } returns listOf(buy, sell)
        every { periodPort.fetchPeriodTradeProfit(userId, TradingMode.REAL, any(), any()) } throws
            IllegalStateException("KIS 자격증명 없음")

        val result = service.crossCheck(userId, "DAY")

        assertThat(result.kisCallOk).isFalse()
        assertThat(result.matched).isFalse()
        assertThat(result.ourGrossRealizedPnl).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(result.driftCauses).anyMatch { it.contains("KIS_CALL_FAILED") }
    }
}
