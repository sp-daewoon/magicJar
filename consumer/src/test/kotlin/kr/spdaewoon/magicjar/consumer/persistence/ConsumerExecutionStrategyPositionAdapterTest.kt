package kr.spdaewoon.magicjar.consumer.persistence

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant as JInstant

/**
 * ConsumerExecutionStrategyPositionAdapter upsert 수식 단위 테스트 (ADR 0110, 분할매수 R2-2).
 *
 * 평단 가중평균·realizedPnl·exit stage 마킹·전량청산 reset·MANUAL skip 회귀 가드(자본 path 수식 LOCK).
 * drift 검증·DB I/O 는 통합 영역 — 본 테스트는 port upsert 결과(StrategyPosition)를 capture 해 수식만 단언.
 */
class ConsumerExecutionStrategyPositionAdapterTest {
    private val port = CapturingStrategyPositionPort()
    private val jpaRepo: StrategyPositionJpaRepository = mock()
    private val positionRepo: PositionJpaRepository = mock()
    private val orderRequestRepo: ConsumerOrderRequestJpaRepository = mock()
    private val signalRepo: ConsumerSignalJpaRepository = mock()

    @Suppress("UNCHECKED_CAST")
    private val systemEventProvider: ObjectProvider<kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger> =
        mock<ObjectProvider<kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger>>().also {
            whenever(it.ifAvailable).thenReturn(null)
        }

    private val adapter =
        ConsumerExecutionStrategyPositionAdapter(
            strategyPositionPort = port,
            strategyPositionJpaRepository = jpaRepo,
            positionJpaRepository = positionRepo,
            orderRequestJpaRepository = orderRequestRepo,
            signalJpaRepository = signalRepo,
            systemEventLoggerProvider = systemEventProvider,
            // 본 테스트는 applyExecution upsert 수식만 검증한다 — drift 보정은 별도 reconcile 테스트(아래 클래스).
            // 보정 off 로 두어 reconcileDrift 가 lastUpsert 를 덮어쓰지 않게 한다(수식 단언 격리).
            driftAutoCorrectEnabled = false,
            clock = Clock.System,
        )

    @Test
    fun `BUY 체결 — 평단 가중평균 + filledLots 증가`() {
        // 기존 10주 @10000 → 추가 10주 @12000 체결 = 20주 @11000
        port.preset = strategyPosition(quantity = 10, avgPrice = "10000", filledLots = 1)

        adapter.applyExecution(
            request(side = OrderSide.BUY, strategyId = 100L),
            execution(quantity = 10, price = "12000"),
        )

        val saved = port.lastUpsert!!
        assertThat(saved.quantity).isEqualTo(20)
        assertThat(saved.avgPrice).isEqualByComparingTo(BigDecimal("11000"))
        assertThat(saved.filledLots).isEqualTo(2)
    }

    @Test
    fun `BUY 첫 진입 — 기존 행 없으면 신규 평단`() {
        port.preset = null

        adapter.applyExecution(
            request(side = OrderSide.BUY, strategyId = 100L),
            execution(quantity = 5, price = "10000"),
        )

        val saved = port.lastUpsert!!
        assertThat(saved.quantity).isEqualTo(5)
        assertThat(saved.avgPrice).isEqualByComparingTo(BigDecimal("10000"))
        assertThat(saved.filledLots).isEqualTo(1)
    }

    @Test
    fun `SELL 부분 청산 — realizedPnl 누적 + quantity 감소 + 평단 유지`() {
        // 20주 @10000 → 5주 @12000 매도 = realizedPnl += (12000-10000)*5 = 10000
        port.preset = strategyPosition(quantity = 20, avgPrice = "10000", realizedPnl = "0")

        adapter.applyExecution(
            request(side = OrderSide.SELL, strategyId = 100L, signalId = 0L),
            execution(quantity = 5, price = "12000"),
        )

        val saved = port.lastUpsert!!
        assertThat(saved.quantity).isEqualTo(15)
        assertThat(saved.avgPrice).isEqualByComparingTo(BigDecimal("10000"))
        assertThat(saved.realizedPnl).isEqualByComparingTo(BigDecimal("10000"))
    }

    @Test
    fun `SELL 전량 청산 — avgPrice·filledLots·executedExitStages reset(행 유지)`() {
        port.preset =
            strategyPosition(
                quantity = 10,
                avgPrice = "10000",
                filledLots = 3,
                executedExitStages = setOf(0, 1),
            )

        adapter.applyExecution(
            request(side = OrderSide.SELL, strategyId = 100L, signalId = 0L),
            execution(quantity = 10, price = "11000"),
        )

        val saved = port.lastUpsert!!
        assertThat(saved.quantity).isEqualTo(0)
        assertThat(saved.avgPrice).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(saved.filledLots).isEqualTo(0)
        assertThat(saved.executedExitStages).isEmpty()
        // 실현손익은 reset 안 함 — (11000-10000)*10 = 10000 누적.
        assertThat(saved.realizedPnl).isEqualByComparingTo(BigDecimal("10000"))
    }

    @Test
    fun `SELL PARTIAL_TAKE_PROFIT#1 reason — executedExitStages에 단계 1 마킹(체결 시점)`() {
        port.preset = strategyPosition(quantity = 20, avgPrice = "10000", executedExitStages = setOf(0))
        whenever(signalRepo.findById(any())).thenReturn(
            java.util.Optional.of(signalEntity(reason = "PARTIAL_TAKE_PROFIT#1")),
        )

        adapter.applyExecution(
            request(side = OrderSide.SELL, strategyId = 100L, signalId = 55L),
            execution(quantity = 5, price = "11000"),
        )

        val saved = port.lastUpsert!!
        assertThat(saved.quantity).isEqualTo(15)
        assertThat(saved.executedExitStages).containsExactlyInAnyOrder(0, 1)
    }

    // ── ADR 0128 ①② R2 — base 고정·부분매수 잔여·전량청산 reset ──────────────────

    @Test
    fun `① BUY 체결 — 박제된 scaleInBaseBudget 은 회차 매수에도 보존된다(고정 base 불변)`() {
        // 2회차 체결 — 1차에서 박제된 base 100만이 회차 매수로 사라지면 안 된다(2·3차 산정 기준).
        port.preset =
            strategyPosition(quantity = 9, avgPrice = "17283", filledLots = 1)
                .copy(scaleInBaseBudget = BigDecimal("1000000"))

        adapter.applyExecution(
            request(side = OrderSide.BUY, strategyId = 100L, orderedQty = 4),
            execution(quantity = 4, price = "17283"),
        )

        val saved = port.lastUpsert!!
        assertThat(saved.filledLots).isEqualTo(2)
        assertThat(saved.scaleInBaseBudget).isEqualByComparingTo(BigDecimal("1000000"))
    }

    @Test
    fun `② BUY 완전 체결 — 잔여 0 (주문수량 == 체결수량)`() {
        port.preset =
            strategyPosition(quantity = 10, avgPrice = "10000", filledLots = 1)
                .copy(scaleInBaseBudget = BigDecimal("1000000"), scaleInRemainderQty = 0)

        adapter.applyExecution(
            request(side = OrderSide.BUY, strategyId = 100L, orderedQty = 10),
            execution(quantity = 10, price = "10000"),
        )

        val saved = port.lastUpsert!!
        assertThat(saved.scaleInRemainderQty).isEqualTo(0)
    }

    @Test
    fun `② BUY 부분 체결 — 못 산 잔여(주문 10 - 체결 7 = 3)를 기록한다`() {
        port.preset =
            strategyPosition(quantity = 0, avgPrice = "0", filledLots = 0)
                .copy(scaleInBaseBudget = BigDecimal("1000000"))

        adapter.applyExecution(
            request(side = OrderSide.BUY, strategyId = 100L, orderedQty = 10),
            execution(quantity = 7, price = "10000"),
        )

        val saved = port.lastUpsert!!
        assertThat(saved.quantity).isEqualTo(7)
        assertThat(saved.scaleInRemainderQty).isEqualTo(3)
    }

    @Test
    fun `①② SELL 전량 청산 — base·잔여도 reset(다음 진입 새 base 박제)`() {
        port.preset =
            strategyPosition(quantity = 10, avgPrice = "10000", filledLots = 3)
                .copy(scaleInBaseBudget = BigDecimal("1000000"), scaleInRemainderQty = 2)

        adapter.applyExecution(
            request(side = OrderSide.SELL, strategyId = 100L, signalId = 0L, orderedQty = 10),
            execution(quantity = 10, price = "11000"),
        )

        val saved = port.lastUpsert!!
        assertThat(saved.quantity).isEqualTo(0)
        assertThat(saved.scaleInBaseBudget).isNull()
        assertThat(saved.scaleInRemainderQty).isEqualTo(0)
    }

    @Test
    fun `①② SELL 부분 청산 — base·잔여 보존(전량 청산 아님)`() {
        port.preset =
            strategyPosition(quantity = 20, avgPrice = "10000", filledLots = 2)
                .copy(scaleInBaseBudget = BigDecimal("1000000"), scaleInRemainderQty = 5)

        adapter.applyExecution(
            request(side = OrderSide.SELL, strategyId = 100L, signalId = 0L, orderedQty = 5),
            execution(quantity = 5, price = "12000"),
        )

        val saved = port.lastUpsert!!
        assertThat(saved.quantity).isEqualTo(15)
        assertThat(saved.scaleInBaseBudget).isEqualByComparingTo(BigDecimal("1000000"))
        assertThat(saved.scaleInRemainderQty).isEqualTo(5)
    }

    @Test
    fun `MANUAL sentinel(strategyId=0) — upsert skip`() {
        adapter.applyExecution(
            request(side = OrderSide.BUY, strategyId = 0L),
            execution(quantity = 5, price = "10000"),
        )

        assertThat(port.lastUpsert).isNull()
    }

    @Test
    fun `체결 수량 0 — upsert skip`() {
        adapter.applyExecution(
            request(side = OrderSide.BUY, strategyId = 100L),
            execution(quantity = 0, price = "10000"),
        )

        assertThat(port.lastUpsert).isNull()
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun strategyPosition(
        quantity: Int,
        avgPrice: String,
        filledLots: Int = 1,
        realizedPnl: String = "0",
        executedExitStages: Set<Int> = emptySet(),
    ): StrategyPosition =
        StrategyPosition(
            id = 1L,
            userId = "user-A",
            strategyId = 100L,
            code = "005930",
            quantity = quantity,
            avgPrice = BigDecimal(avgPrice),
            filledLots = filledLots,
            realizedPnl = BigDecimal(realizedPnl),
            executedExitStages = executedExitStages,
            openedAt = NOW,
            updatedAt = NOW,
        )

    private fun request(
        side: OrderSide,
        strategyId: Long,
        signalId: Long = 1L,
        orderedQty: Int = 10,
    ): OrderRequest =
        OrderRequest(
            id = 7L,
            userId = "user-A",
            signalId = signalId,
            strategyId = strategyId,
            code = "005930",
            side = side,
            priceType = OrderPriceType.MARKET,
            quantity = orderedQty,
            limitPrice = null,
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            idempotencyKey = "k-$side-$strategyId-$signalId",
            requestedAt = JInstant.EPOCH,
        )

    private fun execution(
        quantity: Int,
        price: String,
    ): OrderExecution =
        OrderExecution(
            id = 1L,
            orderId = 7L,
            userId = "user-A",
            kisOrderNo = "ODNO1",
            executedQuantity = quantity,
            executedPrice = BigDecimal(price),
            executedAt = JInstant.EPOCH,
            createdAt = JInstant.EPOCH,
        )

    private fun signalEntity(reason: String): ConsumerSignalJpaEntity =
        ConsumerSignalJpaEntity(
            id = 55L,
            strategyId = 100L,
            userId = "user-A",
            code = "005930",
            interval = "5m",
            ts = JInstant.EPOCH,
            side = "SELL",
            strength = 1,
            reason = reason,
            price = BigDecimal("11000"),
        )

    private companion object {
        val NOW: kotlinx.datetime.Instant = kotlinx.datetime.Instant.fromEpochSeconds(1_736_640_000L)
    }

    /** find/upsert 결과를 capture 하는 in-memory port (수식만 검증). */
    private class CapturingStrategyPositionPort : StrategyPositionPort {
        var preset: StrategyPosition? = null
        var lastUpsert: StrategyPosition? = null

        override fun findByUserStrategyCode(
            userId: String,
            strategyId: Long,
            code: String,
        ): StrategyPosition? = preset

        override fun findByUserCode(
            userId: String,
            code: String,
        ): List<StrategyPosition> = preset?.let { listOf(it) } ?: emptyList()

        override fun upsert(strategyPosition: StrategyPosition): StrategyPosition {
            lastUpsert = strategyPosition
            return strategyPosition
        }
    }
}

/**
 * strategy_position 체결 직후 drift 근본 fix 단위 테스트 (2026-06-15).
 *
 * 운영 실측 회귀: 005930·전략 88 이 15:10 매수 333주 체결됐는데 strategy_position 88 이 0 으로 잔존.
 * 근본 원인 — applyExecution → applyBuy(+) → reconcileDrift 즉시 호출 시, position(원장) quantity 가 아직
 * 0(원장은 BalanceReconcile 주기에 뒤늦게 채워짐) → legs > authority(0) = 초과 → Adjust 로 방금 더한 물량 삭제.
 * 기존 가드(authorityRow==null)는 "행 부재"만 막아 "행 존재 + quantity=0"(체결 직후)은 뚫렸다.
 *
 * 2026-06-19 확장(분할 N회차 stale 양수 잔고 P0): 권위가 0 이 아니라 양수라도 체결 직후엔 stale(직전 회차값) 일 수 있다.
 * 분할 2회차 실측(전략 47, 11:17): applyBuy 가 nextQty=13 정확 산정했으나 KIS 잔고는 아직 1회차값 9 → legSum(13) >
 * authority(9) 초과 Adjust 로 13→9 깎임 → 익절 4주 누락. 같은 뿌리(원장 미반영) 의 양수판이므로 postExecution 에서
 * 권위<legSum(깎는 방향) 전체를 skip 한다.
 *
 * 검증:
 * 1. 체결 직후 원장 0(행 존재) 빈틈 — strategy_position 이 깎이지 않고 보존된다(다음 주기 회복).
 * 2. 체결 직후 stale 양수(원장 9, leg 13) — nextQty(13) 보존(초과 깎기 금지 — 익절 누락 방지).
 * 3. 주기 reconcile 정합(원장 13) — 무동작 / 진짜 drift(원장 9·0) — 정상 초과 정정.
 */
class ConsumerExecutionStrategyPositionDriftGuardTest {
    private val port = ReconcileCapturingPort()
    private val jpaRepo: StrategyPositionJpaRepository = mock()
    private val positionRepo: PositionJpaRepository = mock()
    private val orderRequestRepo: ConsumerOrderRequestJpaRepository = mock()
    private val signalRepo: ConsumerSignalJpaRepository = mock()

    @Suppress("UNCHECKED_CAST")
    private val systemEventProvider: ObjectProvider<kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger> =
        mock<ObjectProvider<kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger>>().also {
            whenever(it.ifAvailable).thenReturn(null)
        }

    private val adapter =
        ConsumerExecutionStrategyPositionAdapter(
            strategyPositionPort = port,
            strategyPositionJpaRepository = jpaRepo,
            positionJpaRepository = positionRepo,
            orderRequestJpaRepository = orderRequestRepo,
            signalJpaRepository = signalRepo,
            systemEventLoggerProvider = systemEventProvider,
            // drift 보정 ON — 가드가 실제로 초과 Adjust 를 막는지 검증.
            driftAutoCorrectEnabled = true,
            // ledger rebuild OFF — 본 테스트는 증분 drift 가드만 격리(rebuild 는 별도 진실원 의존).
            ledgerRebuildEnabled = false,
            clock = Clock.System,
        )

    @Test
    fun `체결 직후 원장 0(행 존재) — 방금 귀속한 strategy_position 이 보존된다(초과 삭제 금지)`() {
        // given — 전략 88, 첫 진입(기존 leg 없음). 원장 행은 존재하지만 quantity=0(체결 직후 미반영).
        port.preset = null
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any()))
            .thenReturn(positionRow(quantity = 0))

        // when — 1주 매수 체결 직후 reconcileDrift(postExecution=true) 가 같은 transaction 에서 호출됨.
        adapter.applyExecution(
            request(side = OrderSide.BUY, strategyId = 88L),
            execution(quantity = 1, price = "75000"),
        )

        // then — 마지막 upsert(=방금 귀속한 leg)가 보존(1주). drift 가 0 으로 깎지 않았다.
        val saved = port.lastUpsert!!
        assertThat(saved.strategyId).isEqualTo(88L)
        assertThat(saved.quantity).isEqualTo(1)
    }

    @Test
    fun `분할 2회차 체결 직후 stale 양수 잔고(원장 9) — nextQty(13) 보존(초과 깎기 금지)`() {
        // given — 전략 47, 1회차 9주 보유(leg). 2회차 4주 매수 체결 직후 leg=13 upsert 되지만,
        //   KIS 잔고(positionQty)는 아직 1회차값 9(2회차 4주 미반영 — 라이브 실측 11:17).
        port.preset =
            StrategyPosition(
                id = 1L,
                userId = "user-A",
                strategyId = 47L,
                code = "005930",
                quantity = 9,
                avgPrice = BigDecimal("17283"),
                filledLots = 1,
                realizedPnl = BigDecimal.ZERO,
                executedExitStages = emptySet(),
                openedAt = NOW,
                updatedAt = NOW,
            )
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any()))
            .thenReturn(positionRow(quantity = 9))

        // when — 2회차 4주 매수 체결 → applyBuy 가 9+4=13 upsert, 직후 reconcileDrift(postExecution=true) 호출.
        adapter.applyExecution(
            request(side = OrderSide.BUY, strategyId = 47L),
            execution(quantity = 4, price = "17283"),
        )

        // then — drift 가 legSum(13) > authority(9 stale) 를 초과로 깎지 않고 보존. 익절 4주 누락 방지.
        val saved = port.lastUpsert!!
        assertThat(saved.strategyId).isEqualTo(47L)
        assertThat(saved.quantity).isEqualTo(13)
    }

    @Test
    fun `주기 reconcile 진짜 보유(원장 13 반영 후) — leg 13 정합이면 무동작`() {
        // given — 분할 2회차 leg=13, 원장도 BalanceReconcile 이 13 으로 채운 뒤(정합).
        port.preset =
            StrategyPosition(
                id = 1L,
                userId = "user-A",
                strategyId = 47L,
                code = "005930",
                quantity = 13,
                avgPrice = BigDecimal("17283"),
                filledLots = 2,
                realizedPnl = BigDecimal.ZERO,
                executedExitStages = emptySet(),
                openedAt = NOW,
                updatedAt = NOW,
            )
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any()))
            .thenReturn(positionRow(quantity = 13))

        // when — 주기 reconcile(postExecution=false). 권위 13 == legSum 13.
        adapter.reconcileStrategyPositionDrift("user-A", "005930", TradingMode.MOCK.name)

        // then — 정합이라 보정 upsert 미발생(leg 13 보존).
        assertThat(port.lastUpsert).isNull()
    }

    @Test
    fun `주기 reconcile 진짜 drift(원장 9 반영 후 leg 13) — 초과 leg 가 9 로 정상 정정된다`() {
        // given — 원장이 BalanceReconcile 로 9 까지만 채워진 진짜 drift(예: 2회차 미체결·취소). 주기 경로는 양수 권위 신뢰.
        port.preset =
            StrategyPosition(
                id = 1L,
                userId = "user-A",
                strategyId = 47L,
                code = "005930",
                quantity = 13,
                avgPrice = BigDecimal("17283"),
                filledLots = 2,
                realizedPnl = BigDecimal.ZERO,
                executedExitStages = emptySet(),
                openedAt = NOW,
                updatedAt = NOW,
            )
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any()))
            .thenReturn(positionRow(quantity = 9))

        // when — 주기 reconcile(postExecution=false). 원장 9 = 권위 신뢰 → 진짜 초과 정정.
        adapter.reconcileStrategyPositionDrift("user-A", "005930", TradingMode.MOCK.name)

        // then — 유령 4주가 9 로 정정(원장 권위 정렬).
        val saved = port.lastUpsert!!
        assertThat(saved.strategyId).isEqualTo(47L)
        assertThat(saved.quantity).isEqualTo(9)
    }

    @Test
    fun `주기 reconcile 진짜 미보유(원장 0) — 초과 leg 가 정상 0 으로 정정된다`() {
        // given — strategy_position 88 에 유령 1주가 남아있고, 원장(BalanceReconcile 이 채운 뒤) 권위는 0.
        port.preset =
            StrategyPosition(
                id = 1L,
                userId = "user-A",
                strategyId = 88L,
                code = "005930",
                quantity = 1,
                avgPrice = BigDecimal("75000"),
                filledLots = 1,
                realizedPnl = BigDecimal.ZERO,
                executedExitStages = emptySet(),
                openedAt = NOW,
                updatedAt = NOW,
            )
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any()))
            .thenReturn(positionRow(quantity = 0))

        // when — 주기 reconcile(postExecution=false). 원장 0 = 진짜 미보유로 신뢰.
        adapter.reconcileStrategyPositionDrift("user-A", "005930", TradingMode.MOCK.name)

        // then — 유령 leg 가 0 으로 정정(전량 청산 reset).
        val saved = port.lastUpsert!!
        assertThat(saved.strategyId).isEqualTo(88L)
        assertThat(saved.quantity).isEqualTo(0)
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun request(
        side: OrderSide,
        strategyId: Long,
    ): OrderRequest =
        OrderRequest(
            id = 7L,
            userId = "user-A",
            signalId = 0L,
            strategyId = strategyId,
            code = "005930",
            side = side,
            priceType = OrderPriceType.MARKET,
            quantity = 1,
            limitPrice = null,
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            idempotencyKey = "k-$side-$strategyId",
            requestedAt = JInstant.EPOCH,
        )

    private fun execution(
        quantity: Int,
        price: String,
    ): OrderExecution =
        OrderExecution(
            id = 1L,
            orderId = 7L,
            userId = "user-A",
            kisOrderNo = "ODNO1",
            executedQuantity = quantity,
            executedPrice = BigDecimal(price),
            executedAt = JInstant.EPOCH,
            createdAt = JInstant.EPOCH,
        )

    private fun positionRow(quantity: Int): PositionJpaEntity =
        PositionJpaEntity(
            id = 9L,
            userId = "user-A",
            code = "005930",
            quantity = quantity,
            avgPrice = BigDecimal("75000"),
            source = TradingMode.MOCK.name,
        )

    private companion object {
        val NOW: kotlinx.datetime.Instant = kotlinx.datetime.Instant.fromEpochSeconds(1_736_640_000L)
    }

    /**
     * 증분 upsert 후 그 결과를 leg 조회에 반영하는 port — 체결 직후 reconcileDrift 가
     * "방금 귀속한 leg" 를 보도록 한다(실제 transaction 동작 모사).
     */
    private class ReconcileCapturingPort : StrategyPositionPort {
        var preset: StrategyPosition? = null
        var lastUpsert: StrategyPosition? = null

        override fun findByUserStrategyCode(
            userId: String,
            strategyId: Long,
            code: String,
        ): StrategyPosition? = lastUpsert ?: preset

        override fun findByUserCode(
            userId: String,
            code: String,
        ): List<StrategyPosition> = (lastUpsert ?: preset)?.let { listOf(it) } ?: emptyList()

        override fun upsert(strategyPosition: StrategyPosition): StrategyPosition {
            lastUpsert = strategyPosition
            return strategyPosition
        }
    }
}
