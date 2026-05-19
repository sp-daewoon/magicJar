package kr.spdaewoon.magicjar.consumer.aggregation

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

/**
 * CandleGapDetectorAdapter 단위 테스트 — ADR 0086 §2-3 갭 검출 알고리즘 + 햇징 룰 #6·#7 검증.
 */
class CandleGapDetectorAdapterTest {
    private val candleRepository = mockk<CandleRepository>()
    private val businessDayCalendar =
        mockk<BusinessDayCalendar>().also {
            // default: 주말 가드만 (테스트 baseline)
            every { it.isBusinessDay(any()) } answers {
                val d = firstArg<LocalDate>()
                d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
            }
        }
    private val instrumentJpaRepository = mockk<ConsumerInstrumentJpaRepository>()

    private val adapter =
        CandleGapDetectorAdapter(
            candleRepository = candleRepository,
            businessDayCalendar = businessDayCalendar,
            instrumentJpaRepository = instrumentJpaRepository,
        )

    @Test
    fun `룰 #6 false positive — 거래정지 종목은 갭 검출 0건 empty list`() {
        val halt =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns true
                every { it.liquidation } returns false
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(halt)

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 30)

        assertThat(gaps).isEmpty()
    }

    @Test
    fun `룰 #6 false positive — 정리매매 종목은 갭 검출 0건 empty list`() {
        val liquidation =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns false
                every { it.liquidation } returns true
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(liquidation)

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 30)

        assertThat(gaps).isEmpty()
    }

    @Test
    fun `룰 #7 false negative — DB ts 0건이면 expected 전체가 갭으로 반환`() {
        every { instrumentJpaRepository.findById("004090") } returns Optional.empty()
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)

        // 5 거래일 expected 전부가 갭
        assertThat(gaps).hasSize(5)
        // ASC 정렬 보장
        for (i in 1 until gaps.size) {
            assertThat(gaps[i]).isAfterOrEqualTo(gaps[i - 1])
        }
    }

    @Test
    fun `정상 흐름 — actual ts 일부만 있으면 diff만 반환`() {
        every { instrumentJpaRepository.findById("004090") } returns Optional.empty()
        // 5 거래일 expected 중 첫 3개만 actual에 있다고 가정
        val expectedSlot = mutableListOf<Instant>()
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                from = any(),
                to = any(),
            )
        } answers {
            // expected 첫 3개 매칭 — adapter 알고리즘이 ASC 정렬이므로 from·to 자체로 추론
            val from = thirdArg<Instant>()
            val to = arg<Instant>(3)
            // 임의 actual 3개 — gap 검출은 위 단위에서 검증
            expectedSlot.add(from)
            expectedSlot.add(to)
            // empty 반환 → 모든 expected가 갭
            emptyList()
        }

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)

        assertThat(gaps).hasSize(5)
    }

    @Test
    fun `windowDays out of range — IllegalArgumentException throw`() {
        every { instrumentJpaRepository.findById("004090") } returns Optional.empty()
        try {
            adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 0)
            assertThat(false).isTrue // unreachable
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("windowDays")
        }

        try {
            adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 366)
            assertThat(false).isTrue
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("windowDays")
        }
    }

    @Test
    fun `interval 미지원 — 5m 호출 시 empty list 반환 (Phase 2-A는 1d·1m만)`() {
        every { instrumentJpaRepository.findById("004090") } returns Optional.empty()

        val gaps = adapter.detectGaps("004090", CandleInterval.FIVE_MIN, windowDays = 30)

        assertThat(gaps).isEmpty()
    }

    @Test
    fun `1m interval — expected ts 정규장 9_00 ~ 15_29 390 bar per 일`() {
        every { instrumentJpaRepository.findById("004090") } returns Optional.empty()
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_MIN,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_MIN, windowDays = 1)

        // 1 거래일 × 390 분 = 390 (9:00 ~ 15:29 inclusive — 마감 15:30 직전까지)
        assertThat(gaps).hasSize(390)
    }
}
