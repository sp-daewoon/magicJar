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
        buyFee: BigDecimal? = null,
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
                    buyFeeTotal = buyFee,
                    totalFee = (sellFee ?: BigDecimal.ZERO).add(buyFee ?: BigDecimal.ZERO),
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
    fun `KIS rlzt_pfls 가 gross 와 같으면(비용행 없음) GROSS 보조 축으로 일치`() {
        // 005930 매수 10@1000(fee 100), 매도 10@1100(fee 110 tax 220). gross=(1100-1000)*10=1000.
        val buy = req(1, "005930", OrderSide.BUY)
        val sell = req(2, "005930", OrderSide.SELL)
        every { execRepo.findByExecutedAtBeforeOrderByExecutedAtAsc(any()) } returns
            listOf(
                exec(1, "10", "1000", fee = "100", at = todayAt(9, 30)),
                exec(2, "10", "1100", fee = "110", tax = "220", at = todayAt(13, 0)),
            )
        every { reqRepo.findAllById(any<Iterable<Long>>()) } returns listOf(buy, sell)
        // KIS rlzt_pfls=1000, KIS 비용행 부재(fee/tax null) → ourNetKisCost=gross=1000 도 KIS 와 일치(KIS_COST=GROSS 동치).
        every { periodPort.fetchPeriodTradeProfit(userId, TradingMode.REAL, any(), any()) } returns
            periodSnapshot(rows = listOf(row("005930", "삼성전자", "1000")), totalRealizedPnl = BigDecimal("1000"))

        val result = service.crossCheck(userId, "DAY")

        assertThat(result.ourGrossRealizedPnl).isEqualByComparingTo(BigDecimal("1000"))
        // net: 평단 (10*1000+100)/10=1010, net=(1100-1010)*10-(110+220)=900-330=570
        assertThat(result.ourNetRealizedPnl).isEqualByComparingTo(BigDecimal("570"))
        assertThat(result.kisPeriodTotalRealizedPnl).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(result.periodDriftGross).isEqualByComparingTo(BigDecimal("0"))
        // KIS 비용행이 없어 ourNetKisCost=gross → KIS_COST 축이 1차로 잡힘(잔차 0).
        assertThat(result.periodDriftKisCost).isEqualByComparingTo(BigDecimal("0"))
        assertThat(result.matched).isTrue()
        assertThat(result.matchedDefinition).isEqualTo("KIS_COST")
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
    fun `우리 fee tax 0 미캡처여도 KIS 실비용 보정 net 으로 matched=true (개선 E 핵심)`() {
        // 우리 체결 fee/tax=0(미캡처) → gross=net=1000. KIS rlzt=550(매도fee110+매도tax220+매수fee100 반영).
        // ourNetKisCost = gross(1000) - (매도110+매도220+매수100=430) = 570? → KIS 550 과 일치하려면 비용 450.
        // 실데이터 정합: 우리 gross - KIS총비용 = KIS net. 매도fee150+매도tax200+매수fee100=450 → 1000-450=550=KIS.
        val buy = req(1, "005930", OrderSide.BUY)
        val sell = req(2, "005930", OrderSide.SELL)
        every { execRepo.findByExecutedAtBeforeOrderByExecutedAtAsc(any()) } returns
            listOf(
                exec(1, "10", "1000", at = todayAt(9, 30)),
                exec(2, "10", "1100", at = todayAt(13, 0)),
            )
        every { reqRepo.findAllById(any<Iterable<Long>>()) } returns listOf(buy, sell)
        // KIS rlzt_pfls=550(매도비용+매수fee 반영 net). output2: 매도fee150+매도tax200+매수fee100=450.
        every { periodPort.fetchPeriodTradeProfit(userId, TradingMode.REAL, any(), any()) } returns
            periodSnapshot(
                rows = listOf(row("005930", "삼성전자", "550", fee = "150", tax = "200")),
                totalRealizedPnl = BigDecimal("550"),
                sellFee = BigDecimal("150"),
                sellTax = BigDecimal("200"),
                buyFee = BigDecimal("100"),
            )

        val result = service.crossCheck(userId, "DAY")

        // 우리 net=gross=1000 (fee/tax 0 미캡처)
        assertThat(result.ourNetRealizedPnl).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(result.ourTotalCost).isEqualByComparingTo(BigDecimal("0"))
        // KIS 실비용 보정 net = 1000 - (150+200+100) = 550 = KIS 총실현손익 → 정확 일치
        assertThat(result.kisCostApplied).isEqualByComparingTo(BigDecimal("450"))
        assertThat(result.ourNetKisCost).isEqualByComparingTo(BigDecimal("550"))
        assertThat(result.periodDriftKisCost).isEqualByComparingTo(BigDecimal("0"))
        assertThat(result.matched).isTrue()
        assertThat(result.matchedDefinition).isEqualTo("KIS_COST")
        assertThat(result.driftCauses).anyMatch { it.contains("MATCHED_KIS_COST") }
        // 종목별 KIS fee/tax 캡처
        val r = result.perCode.single { it.code == "005930" }
        assertThat(r.kisFee).isEqualByComparingTo(BigDecimal("150"))
        assertThat(r.kisTax).isEqualByComparingTo(BigDecimal("200"))
    }

    @Test
    fun `buy_fee_smtl 미수신이면 매수fee 만큼 잔차 분해 (KIS_COST_RESIDUAL)`() {
        // 우리 fee/tax=0. KIS rlzt=550, output2 매도fee150+매도tax200=350 (매수fee buy_fee_smtl 미수신).
        // ourNetKisCost = 1000 - 350 = 650. KIS 550 → driftKisCost=550-650=-100(매수fee 100). matched=false.
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
                rows = listOf(row("005930", "삼성전자", "550", fee = "150", tax = "200")),
                totalRealizedPnl = BigDecimal("550"),
                sellFee = BigDecimal("150"),
                sellTax = BigDecimal("200"),
                // buyFee 미수신 (null)
            )

        val result = service.crossCheck(userId, "DAY")

        assertThat(result.kisCostApplied).isEqualByComparingTo(BigDecimal("350"))
        assertThat(result.periodDriftKisCost).isEqualByComparingTo(BigDecimal("-100"))
        assertThat(result.matched).isFalse()
        // 잔차를 매수fee 로 명시 분해
        assertThat(result.driftCauses).anyMatch { it.contains("KIS_COST_RESIDUAL") && it.contains("매수fee") }
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
