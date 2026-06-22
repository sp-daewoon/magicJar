package kr.spdaewoon.magicjar.consumer.reconcile

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderExecutionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderExecutionJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaRepository
import kr.spdaewoon.magicjar.domain.account.port.KisRealizedProfitItem
import kr.spdaewoon.magicjar.domain.account.port.KisRealizedProfitPort
import kr.spdaewoon.magicjar.domain.account.port.KisRealizedProfitSnapshot
import kr.spdaewoon.magicjar.domain.account.port.KisRealizedProfitSummary
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
 * RealizedPnlCrossCheckService 단위 테스트 — gross/net FIFO 정합 + KIS 대조 판정.
 */
class RealizedPnlCrossCheckServiceTest {
    private val execRepo = mockk<ConsumerOrderExecutionJpaRepository>()
    private val reqRepo = mockk<ConsumerOrderRequestJpaRepository>()
    private val kisPort = mockk<KisRealizedProfitPort>()
    private val service = RealizedPnlCrossCheckService(kisPort, execRepo, reqRepo)

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

    @Test
    fun `gross는 비용 미차감, net은 매도비용 차감 — KIS gross와 정확 일치`() {
        // 005930 매수 10@1000 (fee 100), 매도 10@1100 (fee 110 tax 220)
        val buy = req(1, "005930", OrderSide.BUY)
        val sell = req(2, "005930", OrderSide.SELL)
        val buyExec = exec(1, "10", "1000", fee = "100", at = todayAt(9, 30))
        val sellExec = exec(2, "10", "1100", fee = "110", tax = "220", at = todayAt(13, 0))

        every { execRepo.findByExecutedAtBeforeOrderByExecutedAtAsc(any()) } returns listOf(buyExec, sellExec)
        every { reqRepo.findAllById(any<Iterable<Long>>()) } returns listOf(buy, sell)

        // gross 실현손익 = (1100-1000)*10 = 1000. KIS 가 정확히 1000 이라고 응답.
        every { kisPort.fetchRealizedProfit(userId, TradingMode.REAL) } returns
            KisRealizedProfitSnapshot(
                userId = userId,
                mode = TradingMode.REAL,
                items =
                    listOf(
                        KisRealizedProfitItem(
                            code = "005930",
                            name = "삼성전자",
                            holdingQty = 0,
                            orderableQty = 0,
                            purchaseAmount = null,
                            evaluationAmount = null,
                            evaluationProfitLoss = null,
                            realizedProfitLoss = BigDecimal("1000"),
                            realizedReturnRate = BigDecimal("10.0"),
                            realEvaluationProfitLoss = null,
                        ),
                    ),
                summary =
                    KisRealizedProfitSummary(
                        cashBalance = null,
                        cashD1 = null,
                        cashD2 = null,
                        totalEvaluation = null,
                        netAssetValue = null,
                        totalEvaluationProfitLoss = null,
                        realizedProfitLoss = BigDecimal("1000"),
                        realizedReturnRate = null,
                        realEvaluationProfitLoss = null,
                        realEvaluationReturnRate = null,
                    ),
                fetchedAt = Instant.now(),
            )

        val result = service.crossCheck(userId, "DAY")

        // gross = (1100-1000)*10 = 1000
        assertThat(result.ourGrossRealizedPnl).isEqualByComparingTo(BigDecimal("1000"))
        // net: 매수 평단가 = (10*1000+100)/10 = 1010, net = (1100-1010)*10 - (110+220) = 900-330 = 570
        assertThat(result.ourNetRealizedPnl).isEqualByComparingTo(BigDecimal("570"))
        assertThat(result.ourTotalCost).isEqualByComparingTo(BigDecimal("330"))
        // KIS gross 1000 == 우리 gross 1000 → matched
        assertThat(result.kisAccountRealizedPnl).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(result.accountDriftGross).isEqualByComparingTo(BigDecimal("0"))
        assertThat(result.matched).isTrue()
        assertThat(result.kisCallOk).isTrue()
        val row = result.perCode.single { it.code == "005930" }
        assertThat(row.matched).isTrue()
        assertThat(row.presentInKis).isTrue()
    }

    @Test
    fun `KIS 호출 실패 시 우리 계산은 보존하고 kisCallOk=false`() {
        val buy = req(1, "005930", OrderSide.BUY)
        val sell = req(2, "005930", OrderSide.SELL)
        every { execRepo.findByExecutedAtBeforeOrderByExecutedAtAsc(any()) } returns
            listOf(exec(1, "10", "1000", at = todayAt(9, 30)), exec(2, "10", "1100", at = todayAt(13, 0)))
        every { reqRepo.findAllById(any<Iterable<Long>>()) } returns listOf(buy, sell)
        every { kisPort.fetchRealizedProfit(userId, TradingMode.REAL) } throws IllegalStateException("KIS 자격증명 없음")

        val result = service.crossCheck(userId, "DAY")

        assertThat(result.kisCallOk).isFalse()
        assertThat(result.matched).isFalse()
        assertThat(result.ourGrossRealizedPnl).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(result.driftCauses).anyMatch { it.contains("KIS_CALL_FAILED") }
    }

    @Test
    fun `전량 청산으로 KIS output1 행 부재 시 KIS_ROW_ABSENT 원인 분류`() {
        val buy = req(1, "005930", OrderSide.BUY)
        val sell = req(2, "005930", OrderSide.SELL)
        every { execRepo.findByExecutedAtBeforeOrderByExecutedAtAsc(any()) } returns
            listOf(exec(1, "10", "1000", at = todayAt(9, 30)), exec(2, "10", "1100", at = todayAt(13, 0)))
        every { reqRepo.findAllById(any<Iterable<Long>>()) } returns listOf(buy, sell)
        // KIS: 종목 행 없음(전량 청산 → 0800 잔고 행 부재), 합계도 0
        every { kisPort.fetchRealizedProfit(userId, TradingMode.REAL) } returns
            KisRealizedProfitSnapshot(
                userId = userId,
                mode = TradingMode.REAL,
                items = emptyList(),
                summary =
                    KisRealizedProfitSummary(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        realizedProfitLoss = BigDecimal("0"),
                        null,
                        null,
                        null,
                    ),
                fetchedAt = Instant.now(),
            )

        val result = service.crossCheck(userId, "DAY")

        assertThat(result.matched).isFalse()
        assertThat(result.driftCauses).anyMatch { it.contains("KIS_ROW_ABSENT") }
        val row = result.perCode.single { it.code == "005930" }
        assertThat(row.presentInKis).isFalse()
    }
}
