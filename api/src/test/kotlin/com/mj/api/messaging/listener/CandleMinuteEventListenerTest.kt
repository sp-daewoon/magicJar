package com.mj.api.messaging.listener

import com.mj.api.infrastructure.persistence.market.CandleJpa
import com.mj.api.infrastructure.persistence.market.CandleJpaRepository
import com.mj.domain.common.Interval
import com.mj.domain.market.event.CandleMinuteAggregatedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.time.Instant

/**
 * CandleMinuteEventListener 단위 테스트.
 *
 * Kafka 연결 없이 순수 로직(INSERT / 중복 무시)만 검증.
 */
class CandleMinuteEventListenerTest {
    private lateinit var repository: CandleJpaRepository
    private lateinit var listener: CandleMinuteEventListener

    @BeforeEach
    fun setUp() {
        repository = mockk(relaxed = true)
        listener = CandleMinuteEventListener(repository)
    }

    // ─────────────────────────────────────────
    // TC-1: 정상 이벤트 → repository.save 호출, M1 interval 확인
    // ─────────────────────────────────────────
    @Test
    fun `정상 이벤트 수신 시 CandleJpaRepository save가 호출되고 interval이 M1이어야 한다`() {
        val event = candleEvent("005930", Instant.parse("2026-05-04T00:00:00Z"))
        val captured = slot<CandleJpa>()
        every { repository.save(capture(captured)) } returns mockk()

        listener.onCandleMinuteAggregated(event)

        verify(exactly = 1) { repository.save(any()) }
        val saved = captured.captured
        assertThat(saved.code).isEqualTo("005930")
        assertThat(saved.interval).isEqualTo(Interval.M1)
        assertThat(saved.openTime).isEqualTo(event.openTime)
        assertThat(saved.open).isEqualByComparingTo("70000")
        assertThat(saved.high).isEqualByComparingTo("71000")
        assertThat(saved.low).isEqualByComparingTo("69500")
        assertThat(saved.close).isEqualByComparingTo("70300")
        assertThat(saved.volume).isEqualTo(400L)
        assertThat(saved.tradeCount).isEqualTo(4L)
    }

    // ─────────────────────────────────────────
    // TC-2: 중복 INSERT → DataIntegrityViolationException 무시
    // ─────────────────────────────────────────
    @Test
    fun `동일 (code, interval, openTime) 중복 INSERT는 예외 없이 무시되어야 한다`() {
        val event = candleEvent("005930", Instant.parse("2026-05-04T00:00:00Z"))
        every { repository.save(any<CandleJpa>()) } throws DataIntegrityViolationException("unique violation")

        // 예외 없이 정상 종료 확인
        listener.onCandleMinuteAggregated(event)

        verify(exactly = 1) { repository.save(any()) }
    }

    // ─────────────────────────────────────────
    // TC-3: 복수 종목 이벤트 각각 독립 INSERT
    // ─────────────────────────────────────────
    @Test
    fun `복수 종목 이벤트는 각각 독립적으로 INSERT되어야 한다`() {
        val openTime = Instant.parse("2026-05-04T00:01:00Z")
        val event1 = candleEvent("005930", openTime)
        val event2 = candleEvent("000660", openTime)

        every { repository.save(any<CandleJpa>()) } returns mockk()

        listener.onCandleMinuteAggregated(event1)
        listener.onCandleMinuteAggregated(event2)

        verify(exactly = 2) { repository.save(any()) }
    }

    // ─────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────
    private fun candleEvent(
        code: String,
        openTime: Instant,
    ): CandleMinuteAggregatedEvent =
        CandleMinuteAggregatedEvent(
            code = code,
            openTime = openTime,
            open = BigDecimal("70000"),
            high = BigDecimal("71000"),
            low = BigDecimal("69500"),
            close = BigDecimal("70300"),
            volume = 400L,
            tradeCount = 4L,
        )
}
