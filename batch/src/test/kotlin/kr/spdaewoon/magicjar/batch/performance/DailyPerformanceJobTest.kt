package kr.spdaewoon.magicjar.batch.performance

import kr.spdaewoon.magicjar.batch.job.performance.BatchMarketIndexDailyRollup
import kr.spdaewoon.magicjar.batch.job.performance.DailyPerformanceJob
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchMarketIndexDailyJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchMarketIndexDailyJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchOrderExecutionJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchOrderExecutionJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchOrderRequestJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchOrderRequestJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchPerformanceSummaryJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchPnlDailyJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchStrategyDailyPerformanceJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchStrategyDailyPerformanceJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchStrategyJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.performance.BatchStrategyJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional

/**
 * DailyPerformanceJobTest — 3건 GREEN 의무 (ADR 0072 spec §3-2 정합).
 *
 * 1. 정상 cron 1 사용자 + N 거래 → pnl_daily INSERT 1 row + summary 5종 UPSERT
 * 2. empty user (활성 사용자 없음) → 0 skip
 * 3. 기존 row 재계산 UPSERT — 중복 실행 멱등성
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
class DailyPerformanceJobTest {
    private val orderRequestJpaRepository: BatchOrderRequestJpaRepository = mock()
    private val orderExecutionJpaRepository: BatchOrderExecutionJpaRepository = mock()
    private val pnlDailyJpaRepository: BatchPnlDailyJpaRepository = mock()
    private val summaryJpaRepository: BatchPerformanceSummaryJpaRepository = mock()
    private val redis: StringRedisTemplate = mock()

    // ADR 0126 — 전략 성과 누적 원장 확장 의존성.
    private val strategyDailyPerformanceJpaRepository: BatchStrategyDailyPerformanceJpaRepository = mock()
    private val strategyJpaRepository: BatchStrategyJpaRepository = mock()
    private val marketIndexDailyJpaRepository: BatchMarketIndexDailyJpaRepository = mock()
    private val marketIndexDailyRollup: BatchMarketIndexDailyRollup = mock()

    private val sut =
        DailyPerformanceJob(
            orderRequestJpaRepository,
            orderExecutionJpaRepository,
            pnlDailyJpaRepository,
            summaryJpaRepository,
            redis,
            strategyDailyPerformanceJpaRepository,
            strategyJpaRepository,
            marketIndexDailyJpaRepository,
            marketIndexDailyRollup,
        )

    private val userId = "user_test_001"
    private val kst = ZoneId.of("Asia/Seoul")
    private val targetDate = LocalDate.of(2026, 5, 13)

    private fun makeOrderRequest(
        id: Long = 1L,
        code: String = "005930",
        side: String = "BUY",
        strategyId: Long = 0L,
    ) = BatchOrderRequestJpaEntity(id = id, userId = userId, code = code, side = side, strategyId = strategyId)

    private fun makeExecution(
        id: Long = 1L,
        orderRequest: BatchOrderRequestJpaEntity,
        tradeDate: LocalDate = targetDate,
        qty: BigDecimal = BigDecimal.ONE,
        price: BigDecimal,
    ): BatchOrderExecutionJpaEntity =
        BatchOrderExecutionJpaEntity(
            id = id,
            orderRequest = orderRequest,
            executedQty = qty,
            executedPrice = price,
            executedAt = tradeDate.atTime(10, 0).atZone(kst).toInstant(),
            fee = BigDecimal.ZERO,
            tax = BigDecimal.ZERO,
        )

    @Test
    fun `정상 1 사용자 N 거래 — 체결 이력 replay로 pnl_daily INSERT + summary 5종 UPSERT`() {
        val buyRequest = makeOrderRequest(id = 1L, side = "BUY")
        val sellRequest = makeOrderRequest(id = 2L, side = "SELL")
        val executions =
            listOf(
                makeExecution(
                    id = 1L,
                    orderRequest = buyRequest,
                    tradeDate = targetDate.minusDays(1),
                    qty = BigDecimal("2"),
                    price = BigDecimal("10000"),
                ),
                makeExecution(id = 2L, orderRequest = sellRequest, price = BigDecimal("12000")),
                makeExecution(id = 3L, orderRequest = sellRequest, price = BigDecimal("9000")),
            )

        whenever(orderRequestJpaRepository.findDistinctUserIds()).thenReturn(listOf(userId))
        whenever(orderExecutionJpaRepository.findByUserIdAndExecutedAtBefore(eq(userId), any<Instant>()))
            .thenReturn(executions)
        whenever(pnlDailyJpaRepository.findByUserIdAndTradeDate(eq(userId), any())).thenReturn(null)
        whenever(pnlDailyJpaRepository.save(any())).thenAnswer { it.getArgument(0) }
        whenever(pnlDailyJpaRepository.findByUserIdAndTradeDateBetweenOrderByTradeDateAsc(eq(userId), any(), any()))
            .thenReturn(emptyList())
        whenever(summaryJpaRepository.findByUserIdAndPeriodAndPeriodStartAndPeriodEnd(any(), any(), any(), any()))
            .thenReturn(null)
        whenever(summaryJpaRepository.save(any())).thenAnswer { it.getArgument(0) }
        whenever(redis.keys(any<String>())).thenReturn(emptySet())

        sut.run(targetDate)

        val pnlCaptor = argumentCaptor<kr.spdaewoon.magicjar.batch.persistence.performance.BatchPnlDailyJpaEntity>()
        verify(pnlDailyJpaRepository).save(pnlCaptor.capture())

        val saved = pnlCaptor.firstValue
        assertThat(saved.userId).isEqualTo(userId)
        assertThat(saved.tradeDate).isEqualTo(targetDate)
        assertThat(saved.realizedPnl).isEqualByComparingTo(BigDecimal("1000.00"))
        assertThat(saved.winCount).isEqualTo(1)
        assertThat(saved.lossCount).isEqualTo(1)
        assertThat(saved.tradeCount).isEqualTo(2)
    }

    @Test
    fun `empty user — 활성 사용자 없음 skip`() {
        whenever(orderRequestJpaRepository.findDistinctUserIds()).thenReturn(emptyList())

        sut.run(targetDate)

        verify(orderExecutionJpaRepository, never()).findByUserIdAndExecutedAtBefore(any(), any())
        verify(pnlDailyJpaRepository, never()).save(any())
    }

    @Test
    fun `기존 row 재계산 UPSERT — 멱등성 DELETE + INSERT`() {
        val existingPnl = mock<kr.spdaewoon.magicjar.batch.persistence.performance.BatchPnlDailyJpaEntity>()
        val buyRequest = makeOrderRequest(id = 1L, side = "BUY")
        val sellRequest = makeOrderRequest(id = 2L, side = "SELL")
        val executions =
            listOf(
                makeExecution(
                    id = 1L,
                    orderRequest = buyRequest,
                    tradeDate = targetDate.minusDays(1),
                    price = BigDecimal("10000"),
                ),
                makeExecution(id = 2L, orderRequest = sellRequest, price = BigDecimal("11000")),
            )

        whenever(orderRequestJpaRepository.findDistinctUserIds()).thenReturn(listOf(userId))
        whenever(orderExecutionJpaRepository.findByUserIdAndExecutedAtBefore(any(), any())).thenReturn(executions)
        whenever(pnlDailyJpaRepository.findByUserIdAndTradeDate(eq(userId), any())).thenReturn(existingPnl)
        whenever(pnlDailyJpaRepository.save(any())).thenAnswer { it.getArgument(0) }
        whenever(pnlDailyJpaRepository.findByUserIdAndTradeDateBetweenOrderByTradeDateAsc(any(), any(), any()))
            .thenReturn(emptyList())
        whenever(summaryJpaRepository.findByUserIdAndPeriodAndPeriodStartAndPeriodEnd(any(), any(), any(), any()))
            .thenReturn(null)
        whenever(summaryJpaRepository.save(any())).thenAnswer { it.getArgument(0) }
        whenever(redis.keys(any<String>())).thenReturn(emptySet())

        sut.run(targetDate)

        // 기존 row DELETE 확인
        verify(pnlDailyJpaRepository).delete(existingPnl)
        verify(pnlDailyJpaRepository).flush()
        // 재삽입 확인
        verify(pnlDailyJpaRepository).save(any())
    }

    // ========================================================================
    // ADR 0126 — 전략별 왕복 집계 (후성식 3왕복 → 행 1개 roundTripCount=3 · alpha 채움)
    // ========================================================================

    @Test
    fun `전략 후성식 3왕복 — strategy_daily_performance 행 1건 roundTripCount=3 + alpha 채움`() {
        val strategyId = 77L
        val code = "093370" // 후성
        val buy = makeOrderRequest(id = 10L, code = code, side = "BUY", strategyId = strategyId)
        val sell = makeOrderRequest(id = 11L, code = code, side = "SELL", strategyId = strategyId)

        // 같은 날 3왕복 (매수→완전청산 0복귀 × 3). 각 왕복 1주, 매수 10000 / 매도 11000 → 왕복당 +1000.
        var execId = 100L
        val executions = mutableListOf<BatchOrderExecutionJpaEntity>()
        repeat(3) {
            executions +=
                makeExecution(
                    id = execId++,
                    orderRequest = buy,
                    qty = BigDecimal.ONE,
                    price = BigDecimal("10000"),
                )
            executions +=
                makeExecution(
                    id = execId++,
                    orderRequest = sell,
                    qty = BigDecimal.ONE,
                    price = BigDecimal("11000"),
                )
        }

        whenever(orderRequestJpaRepository.findDistinctUserIds()).thenReturn(listOf(userId))
        whenever(orderExecutionJpaRepository.findByUserIdAndExecutedAtBefore(eq(userId), any<Instant>()))
            .thenReturn(executions)
        // 기존 user pnl_daily 흐름은 정상 mock (회귀 0 확인 목적 아님 — 전략 집계 검증).
        whenever(pnlDailyJpaRepository.findByUserIdAndTradeDate(eq(userId), any())).thenReturn(null)
        whenever(pnlDailyJpaRepository.save(any())).thenAnswer { it.getArgument(0) }
        whenever(pnlDailyJpaRepository.findByUserIdAndTradeDateBetweenOrderByTradeDateAsc(eq(userId), any(), any()))
            .thenReturn(emptyList())
        whenever(summaryJpaRepository.findByUserIdAndPeriodAndPeriodStartAndPeriodEnd(any(), any(), any(), any()))
            .thenReturn(null)
        whenever(summaryJpaRepository.save(any())).thenAnswer { it.getArgument(0) }
        whenever(redis.keys(any<String>())).thenReturn(emptySet())

        // 전략 universe_market = KOSPI → benchmark 0001.
        whenever(strategyJpaRepository.findById(strategyId))
            .thenReturn(Optional.of(BatchStrategyJpaEntity(id = strategyId, universeMarket = "KOSPI")))
        // 벤치마크 일봉 (코스피 0001): change_pct +0.50%, regime SIDEWAYS.
        whenever(marketIndexDailyJpaRepository.findByIndexCodeAndTradeDate(eq("0001"), eq(targetDate)))
            .thenReturn(
                BatchMarketIndexDailyJpaEntity(
                    indexCode = "0001",
                    tradeDate = targetDate,
                    changePct = BigDecimal("0.5000"),
                    regime = "SIDEWAYS",
                ),
            )
        whenever(
            strategyDailyPerformanceJpaRepository.findByUserIdAndStrategyIdAndTradeDate(any(), any(), any()),
        ).thenReturn(null)
        whenever(strategyDailyPerformanceJpaRepository.save(any())).thenAnswer { it.getArgument(0) }

        sut.run(targetDate)

        val captor = argumentCaptor<BatchStrategyDailyPerformanceJpaEntity>()
        verify(strategyDailyPerformanceJpaRepository).save(captor.capture())

        val saved = captor.firstValue
        assertThat(saved.userId).isEqualTo(userId)
        assertThat(saved.strategyId).isEqualTo(strategyId)
        assertThat(saved.tradeDate).isEqualTo(targetDate)
        assertThat(saved.roundTripCount).isEqualTo(3)
        assertThat(saved.winCount).isEqualTo(3)
        assertThat(saved.lossCount).isEqualTo(0)
        // 왕복당 (11000-10000)×1 = 1000, 3왕복 = 3000.
        assertThat(saved.realizedPnl).isEqualByComparingTo(BigDecimal("3000"))
        // 진입금액 = 평균단가 10000 × 1 × 3왕복 = 30000.
        assertThat(saved.totalCostBasis).isEqualByComparingTo(BigDecimal("30000"))
        // 수익률 = 3000/30000 × 100 = 10.0000%.
        assertThat(saved.returnPct).isEqualByComparingTo(BigDecimal("10.0000"))
        assertThat(saved.benchmarkIndexCode).isEqualTo("0001")
        assertThat(saved.benchmarkChangePct).isEqualByComparingTo(BigDecimal("0.5000"))
        // alpha = return_pct(10.0000) − benchmark(0.5000) = 9.5000.
        assertThat(saved.alphaPct).isEqualByComparingTo(BigDecimal("9.5000"))
        assertThat(saved.marketRegime).isEqualTo("SIDEWAYS")
    }

    @Test
    fun `MANUAL 주문(strategy_id=0)은 전략 집계에서 제외 — strategy_daily_performance 미저장`() {
        val buy = makeOrderRequest(id = 20L, side = "BUY", strategyId = 0L) // MANUAL
        val sell = makeOrderRequest(id = 21L, side = "SELL", strategyId = 0L)
        val executions =
            listOf(
                makeExecution(id = 200L, orderRequest = buy, price = BigDecimal("10000")),
                makeExecution(id = 201L, orderRequest = sell, price = BigDecimal("11000")),
            )

        whenever(orderRequestJpaRepository.findDistinctUserIds()).thenReturn(listOf(userId))
        whenever(orderExecutionJpaRepository.findByUserIdAndExecutedAtBefore(eq(userId), any<Instant>()))
            .thenReturn(executions)
        whenever(pnlDailyJpaRepository.findByUserIdAndTradeDate(eq(userId), any())).thenReturn(null)
        whenever(pnlDailyJpaRepository.save(any())).thenAnswer { it.getArgument(0) }
        whenever(pnlDailyJpaRepository.findByUserIdAndTradeDateBetweenOrderByTradeDateAsc(eq(userId), any(), any()))
            .thenReturn(emptyList())
        whenever(summaryJpaRepository.findByUserIdAndPeriodAndPeriodStartAndPeriodEnd(any(), any(), any(), any()))
            .thenReturn(null)
        whenever(summaryJpaRepository.save(any())).thenAnswer { it.getArgument(0) }
        whenever(redis.keys(any<String>())).thenReturn(emptySet())

        sut.run(targetDate)

        // MANUAL 만 있으므로 전략 일자행 저장 0건.
        verify(strategyDailyPerformanceJpaRepository, never()).save(any())
    }
}
