package com.mj.consumer.application.aggregator

import com.mj.consumer.messaging.producer.CandleMinutePublisher
import com.mj.domain.market.event.CandleMinuteAggregatedEvent
import com.mj.domain.market.event.TickEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * TickToMinuteAggregator 단위 테스트 (7건).
 *
 * MockK로 CandleMinutePublisher를 mock, publisher.publish 호출 여부·내용을 검증.
 * 실제 Kafka 연결 없이 순수 알고리즘만 테스트.
 */
class TickToMinuteAggregatorTest {
    private lateinit var publisher: CandleMinutePublisher
    private lateinit var aggregator: TickToMinuteAggregator

    // 2026-05-04 09:00:00 KST = UTC 2026-05-04 00:00:00
    private val base: Instant = Instant.parse("2026-05-04T00:00:00Z")
    private val min0: Instant = base // 09:00
    private val min1: Instant = base.plus(1, ChronoUnit.MINUTES) // 09:01
    private val min2: Instant = base.plus(2, ChronoUnit.MINUTES) // 09:02

    @BeforeEach
    fun setUp() {
        publisher = mockk(relaxed = true)
        aggregator = TickToMinuteAggregator(publisher)
    }

    // ─────────────────────────────────────────
    // TC-1: 첫 tick → bucket 생성, publish 없음
    // ─────────────────────────────────────────
    @Test
    fun `첫 tick 도착 시 버킷이 생성되고 발행은 없어야 한다`() {
        aggregator.onTick(tick("005930", price = 70_000, at = min0))

        verify(exactly = 0) { publisher.publish(any()) }
    }

    // ─────────────────────────────────────────
    // TC-2: 같은 분 추가 tick → OHLCV 정확히 갱신
    // ─────────────────────────────────────────
    @Test
    fun `같은 분 tick 여러 개 → high·low·close·volume 정확히 갱신되고 발행은 없어야 한다`() {
        aggregator.onTick(tick("005930", 70_000, min0.plusSeconds(0)))
        aggregator.onTick(tick("005930", 71_000, min0.plusSeconds(15)))
        aggregator.onTick(tick("005930", 69_500, min0.plusSeconds(30)))
        aggregator.onTick(tick("005930", 70_300, min0.plusSeconds(45)))

        verify(exactly = 0) { publisher.publish(any()) }
        // 분 경계 tick으로 flush 유도
        val captured = slot<CandleMinuteAggregatedEvent>()
        every { publisher.publish(capture(captured)) } returns Unit

        aggregator.onTick(tick("005930", 70_500, min1))

        verify(exactly = 1) { publisher.publish(any()) }
        val event = captured.captured
        assertThat(event.code).isEqualTo("005930")
        assertThat(event.open).isEqualByComparingTo("70000")
        assertThat(event.high).isEqualByComparingTo("71000")
        assertThat(event.low).isEqualByComparingTo("69500")
        assertThat(event.close).isEqualByComparingTo("70300")
        assertThat(event.volume).isEqualTo(400L) // 4 ticks × 100 each
        assertThat(event.tradeCount).isEqualTo(4L)
    }

    // ─────────────────────────────────────────
    // TC-3: 분 경계 tick → 이전 버킷 flush + 새 버킷 생성
    // ─────────────────────────────────────────
    @Test
    fun `분 경계 tick 도착 시 이전 버킷이 flush되고 새 버킷이 생성된다`() {
        aggregator.onTick(tick("005930", 70_000, min0))
        aggregator.onTick(tick("005930", 70_100, min0.plusSeconds(30)))

        val captured = slot<CandleMinuteAggregatedEvent>()
        every { publisher.publish(capture(captured)) } returns Unit

        aggregator.onTick(tick("005930", 70_200, min1))

        verify(exactly = 1) { publisher.publish(any()) }
        assertThat(captured.captured.openTime).isEqualTo(min0)
        assertThat(captured.captured.close).isEqualByComparingTo("70100")

        // min1 버킷의 close는 70200
        // min2 tick으로 두 번째 flush 유도
        val captured2 = slot<CandleMinuteAggregatedEvent>()
        every { publisher.publish(capture(captured2)) } returns Unit

        aggregator.onTick(tick("005930", 70_300, min2))

        verify(exactly = 2) { publisher.publish(any()) }
        assertThat(captured2.captured.openTime).isEqualTo(min1)
        assertThat(captured2.captured.close).isEqualByComparingTo("70200")
    }

    // ─────────────────────────────────────────
    // TC-4: tick 0 분 버킷 (결측) → flush 시 발행 안 함
    // ─────────────────────────────────────────
    @Test
    fun `tick이 하나도 없는 분의 버킷은 flush 시 발행하지 않는다`() {
        // 버킷 없는 상태에서 timeout flush 호출
        aggregator.flushExpiredBuckets()

        verify(exactly = 0) { publisher.publish(any()) }
    }

    // ─────────────────────────────────────────
    // TC-5: stale tick → 무시
    // ─────────────────────────────────────────
    @Test
    fun `stale=true tick은 버킷 집계에서 제외되어야 한다`() {
        val stale =
            TickEvent(
                code = "005930",
                at = min0,
                price = BigDecimal("70000"),
                volume = 100L,
                stale = true,
            )
        aggregator.onTick(stale)

        // stale tick 후 분 경계 tick 도착해도 flush 없어야 함 (버킷 자체가 없으므로)
        aggregator.onTick(tick("005930", 71_000, min1))

        verify(exactly = 0) { publisher.publish(any()) }
    }

    // ─────────────────────────────────────────
    // TC-6: 다중 종목 독립 버킷
    // ─────────────────────────────────────────
    @Test
    fun `서로 다른 종목의 버킷은 독립적으로 관리된다`() {
        aggregator.onTick(tick("005930", 70_000, min0))
        aggregator.onTick(tick("000660", 90_000, min0.plusSeconds(10)))

        val events = mutableListOf<CandleMinuteAggregatedEvent>()
        every { publisher.publish(capture(events)) } returns Unit

        aggregator.onTick(tick("005930", 70_100, min1))
        aggregator.onTick(tick("000660", 90_100, min1.plusSeconds(5)))

        verify(exactly = 2) { publisher.publish(any()) }
        val codes = events.map { it.code }.toSet()
        assertThat(codes).containsExactlyInAnyOrder("005930", "000660")
    }

    // ─────────────────────────────────────────
    // TC-7: timeout flush — 5분 이상 tick 없으면 자동 flush
    // ─────────────────────────────────────────
    @Test
    fun `마지막 tick 이후 5분 이상 경과 시 timeout flush가 발생한다`() {
        // 오래된 tick 시뮬레이션 — 6분 전에 tick이 왔던 버킷
        val oldAt = Instant.now().minus(6, ChronoUnit.MINUTES)
        aggregator.onTick(tick("005930", 70_000, oldAt.truncatedTo(ChronoUnit.MINUTES)))

        val captured = slot<CandleMinuteAggregatedEvent>()
        every { publisher.publish(capture(captured)) } returns Unit

        aggregator.flushExpiredBuckets()

        verify(exactly = 1) { publisher.publish(any()) }
        assertThat(captured.captured.code).isEqualTo("005930")
    }

    // ─────────────────────────────────────────
    // TC-8: out-of-order tick → 무시
    // ─────────────────────────────────────────
    @Test
    fun `현재 버킷보다 과거 분의 tick은 무시되어야 한다`() {
        aggregator.onTick(tick("005930", 70_000, min1)) // min1 버킷 생성
        aggregator.onTick(tick("005930", 69_000, min0)) // min0 = 과거 → 무시

        // min1 버킷 flush
        val captured = slot<CandleMinuteAggregatedEvent>()
        every { publisher.publish(capture(captured)) } returns Unit
        aggregator.onTick(tick("005930", 70_500, min2))

        verify(exactly = 1) { publisher.publish(any()) }
        // open은 min1 첫 tick 가격이어야 함 (min0 tick은 무시됨)
        assertThat(captured.captured.open).isEqualByComparingTo("70000")
    }

    // ─────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────
    private fun tick(
        code: String,
        price: Long,
        at: Instant,
        volume: Long = 100L,
    ): TickEvent =
        TickEvent(
            code = code,
            at = at,
            price = BigDecimal(price),
            volume = volume,
        )
}
