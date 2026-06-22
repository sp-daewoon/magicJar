package kr.spdaewoon.magicjar.consumer.reconcile

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderExecutionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderExecutionJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaRepository
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitPort
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitRow
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitSnapshot
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
 * ExecutionCostReconcileService 단위 테스트 — KIS 실비용(TTTC8715R) 안분 영속 + 멱등 + net 정합 (follow-up H).
 */
class ExecutionCostReconcileServiceTest {
    private val periodPort = mockk<KisPeriodTradeProfitPort>()
    private val execRepo = mockk<ConsumerOrderExecutionJpaRepository>()
    private val reqRepo = mockk<ConsumerOrderRequestJpaRepository>()
    private val service = ExecutionCostReconcileService(periodPort, execRepo, reqRepo)

    private val kst = ZoneId.of("Asia/Seoul")
    private val userId = "74683223"
    private val today = LocalDate.now(kst)

    private fun at(
        hour: Int,
        minute: Int,
    ): Instant = today.atTime(hour, minute).atZone(kst).toInstant()

    private fun req(
        id: Long,
        code: String,
        side: OrderSide,
        mode: TradingMode = TradingMode.REAL,
    ): ConsumerOrderRequestJpaEntity =
        ConsumerOrderRequestJpaEntity(
            id = id,
            signalId = 1L,
            userId = userId,
            code = code,
            side = side,
            qty = BigDecimal.TEN,
            priceType = OrderPriceType.MARKET,
            mode = mode,
            idempotencyKey = "k$id",
            requestedAt = at(9, 0),
        )

    private fun exec(
        id: Long,
        orderId: Long,
        qty: String,
        price: String,
        fee: String = "0",
        tax: String = "0",
    ): ConsumerOrderExecutionJpaEntity =
        ConsumerOrderExecutionJpaEntity(
            id = id,
            orderId = orderId,
            executedQty = BigDecimal(qty),
            executedPrice = BigDecimal(price),
            executedAt = at(10, 0),
            fee = BigDecimal(fee),
            tax = BigDecimal(tax),
        )

    private fun snapshot(rows: List<KisPeriodTradeProfitRow>): KisPeriodTradeProfitSnapshot =
        KisPeriodTradeProfitSnapshot(userId, TradingMode.REAL, today, today, rows, null)

    private fun kisRow(
        code: String,
        fee: String,
        tax: String,
    ): KisPeriodTradeProfitRow =
        KisPeriodTradeProfitRow(
            tradeDate = today.toString().replace("-", ""),
            code = code,
            name = "종목$code",
            sellQty = BigDecimal.TEN,
            sellAmount = BigDecimal("100000"),
            realizedPnl = BigDecimal("5000"),
            profitRate = null,
            fee = BigDecimal(fee),
            tax = BigDecimal(tax),
            loanInterest = null,
        )

    @Test
    fun `KIS 종목 비용을 매도 체결에 안분해 fee tax 를 영속한다`() {
        val sells = listOf(exec(100, 10, "5", "1000"), exec(200, 20, "5", "1000"))
        every { periodPort.fetchPeriodTradeProfit(userId, TradingMode.REAL, today, today) } returns
            snapshot(listOf(kisRow("005930", fee = "30", tax = "90")))
        every { execRepo.findByExecutedAtGreaterThanEqualAndExecutedAtLessThanOrderByExecutedAtAsc(any(), any()) } returns sells
        every { reqRepo.findAllById(any()) } returns listOf(req(10, "005930", OrderSide.SELL), req(20, "005930", OrderSide.SELL))

        val feeSlot = slot<BigDecimal>()
        val taxSlot = slot<BigDecimal>()
        val captured = mutableListOf<Triple<Long, BigDecimal, BigDecimal>>()
        every { execRepo.fillCostIfAbsent(any(), capture(feeSlot), capture(taxSlot)) } answers {
            captured += Triple(firstArg<Long>(), feeSlot.captured, taxSlot.captured)
            1
        }

        val result = service.reconcile(userId, today, today)

        assertThat(result.kisCallOk).isTrue()
        assertThat(result.updatedRows).isEqualTo(2)
        // 두 체결 금액 동일(5*1000) → fee 30 안분 15/15, tax 90 안분 45/45. 합계 = KIS 비용 정확 일치.
        assertThat(captured.sumOf { it.second }).isEqualByComparingTo("30")
        assertThat(captured.sumOf { it.third }).isEqualByComparingTo("90")
        assertThat(result.appliedFee).isEqualByComparingTo("30.00")
        assertThat(result.appliedTax).isEqualByComparingTo("90.00")
    }

    @Test
    fun `이미 비용이 채워진 행은 멱등 — UPDATE 0건 skip`() {
        val sells = listOf(exec(100, 10, "10", "1000"))
        every { periodPort.fetchPeriodTradeProfit(userId, TradingMode.REAL, today, today) } returns
            snapshot(listOf(kisRow("005930", fee = "30", tax = "90")))
        every { execRepo.findByExecutedAtGreaterThanEqualAndExecutedAtLessThanOrderByExecutedAtAsc(any(), any()) } returns sells
        every { reqRepo.findAllById(any()) } returns listOf(req(10, "005930", OrderSide.SELL))
        // fee=0&tax=0 가드로 이미 채워진 행은 0 반환.
        every { execRepo.fillCostIfAbsent(any(), any(), any()) } returns 0

        val result = service.reconcile(userId, today, today)

        assertThat(result.updatedRows).isZero()
        assertThat(result.skippedRows).isEqualTo(1)
        assertThat(result.appliedFee).isEqualByComparingTo("0.00")
    }

    @Test
    fun `매수 체결과 MOCK 체결은 비용 안분 대상에서 제외`() {
        val rows =
            listOf(
                exec(100, 10, "10", "1000"), // REAL SELL — 대상
                exec(200, 20, "10", "1000"), // REAL BUY — 제외
                exec(300, 30, "10", "1000"), // MOCK SELL — 제외
            )
        every { periodPort.fetchPeriodTradeProfit(userId, TradingMode.REAL, today, today) } returns
            snapshot(listOf(kisRow("005930", fee = "30", tax = "90")))
        every { execRepo.findByExecutedAtGreaterThanEqualAndExecutedAtLessThanOrderByExecutedAtAsc(any(), any()) } returns rows
        every { reqRepo.findAllById(any()) } returns
            listOf(
                req(10, "005930", OrderSide.SELL),
                req(20, "005930", OrderSide.BUY),
                req(30, "005930", OrderSide.SELL, TradingMode.MOCK),
            )
        val captured = mutableListOf<Long>()
        every { execRepo.fillCostIfAbsent(any(), any(), any()) } answers {
            captured += firstArg<Long>()
            1
        }

        service.reconcile(userId, today, today)

        // SELL REAL 체결(id=100)만 안분 — 매수·MOCK 제외.
        assertThat(captured).containsExactly(100L)
    }

    @Test
    fun `KIS 호출 실패 시 kisCallOk false 로 graceful`() {
        every { periodPort.fetchPeriodTradeProfit(userId, TradingMode.REAL, today, today) } throws RuntimeException("rt_cd=1")

        val result = service.reconcile(userId, today, today)

        assertThat(result.kisCallOk).isFalse()
        assertThat(result.updatedRows).isZero()
        assertThat(result.errorMessage).contains("rt_cd=1")
    }

    @Test
    fun `안분 합계는 반올림 잔차 없이 KIS 비용과 정확히 일치 — 마지막 체결 잔여 몰아주기`() {
        // 금액 비율이 나누어떨어지지 않는 3 체결(7:11:13)에서도 합계 잔차 0 검증.
        val sells =
            listOf(
                exec(100, 10, "7", "1000"),
                exec(200, 10, "11", "1000"),
                exec(300, 10, "13", "1000"),
            )
        val allocations = service.allocate(sells, BigDecimal("100"), BigDecimal("333"))

        assertThat(allocations.sumOf { it.fee }).isEqualByComparingTo("100")
        assertThat(allocations.sumOf { it.tax }).isEqualByComparingTo("333")
    }
}
