package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisQuoteCacheAdapter
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.candle.CandleSession
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * WatchlistPollingWorker 단위 테스트.
 *
 * 검증 목표:
 * 1. polling tick 시 Redis quote cache 갱신 + DB candle upsert(saveAll) 호출
 * 2. 동일 ts 재호출(idempotent) — saveAll 매번 호출 (멱등성은 insertIgnore SQL 수준에서 보장)
 * 3. 개장 전 today stub 일봉 적재 가드 (2026-06-11 hotfix) — today stub skip, 과거 행 적재
 *
 * hotfix 배경:
 *  - 2026-05-15: KIS WS 끊김 시 REST fallback candle 미영속 결함
 *  - 2026-06-11: KIS daily-itemchart 개장 전 today stub(vol=0·open==close=전일종가)이
 *    ON CONFLICT DO NOTHING 때문에 장중 실데이터 insert 를 막은 결함
 */
class WatchlistPollingWorkerTest {
    private val redisQuoteCacheAdapter = mockk<RedisQuoteCacheAdapter>(relaxed = true)
    private val kisRestPort = mockk<KisRestPort>()
    private val candleRepository = mockk<CandleRepository>(relaxed = true)

    private lateinit var worker: WatchlistPollingWorker

    private val sampleCandle =
        Candle(
            id = null,
            code = "005930",
            interval = CandleInterval.ONE_DAY,
            ts = Instant.fromEpochSeconds(1_747_267_200L), // 2026-05-15 00:00 KST (과거)
            openRaw = BigDecimal("74000"),
            highRaw = BigDecimal("75500"),
            lowRaw = BigDecimal("73500"),
            closeRaw = BigDecimal("75000"),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 12_000_000L,
            source = "kis",
        )

    @BeforeEach
    fun setUp() {
        worker =
            WatchlistPollingWorker(
                redisQuoteCacheAdapter = redisQuoteCacheAdapter,
                kisRestPort = kisRestPort,
                candleRepository = candleRepository,
            )
        worker.add("005930")
        worker.start()
    }

    @Test
    fun `polling tick 시 Redis quote cache 갱신 + DB candle saveAll 호출`() {
        // given
        every { kisRestPort.fetchCandles("005930", CandleInterval.ONE_DAY, 1) } returns listOf(sampleCandle)
        every { candleRepository.saveAll(any()) } returns 1

        // when
        worker.tick()

        // then — Redis 갱신
        verify(exactly = 1) {
            redisQuoteCacheAdapter.putQuote(
                code = "005930",
                price = BigDecimal("75000"),
                volume = 12_000_000L,
                source = "polling",
            )
        }
        // then — DB candle upsert (과거 candle 이므로 가드 미적용)
        verify(exactly = 1) { candleRepository.saveAll(listOf(sampleCandle)) }
    }

    @Test
    fun `동일 ts 재호출 시 saveAll 매번 호출 — 멱등성은 insertIgnore SQL 수준 보장`() {
        // given — 두 번 연속 동일 candle 반환
        every { kisRestPort.fetchCandles("005930", CandleInterval.ONE_DAY, 1) } returns listOf(sampleCandle)
        every { candleRepository.saveAll(any()) } returnsMany listOf(1, 0) // 첫 번째 INSERT, 두 번째 DO NOTHING

        // when — tick 2회
        worker.tick()
        worker.tick()

        // then — saveAll 2회 호출 (worker는 항상 호출, insertIgnore SQL이 중복 처리)
        verify(exactly = 2) { candleRepository.saveAll(listOf(sampleCandle)) }
    }

    // ----- 2026-06-11 hotfix: today stub 적재 가드 -----

    /**
     * (a) 개장 전 + today stub → today 행 skip, 과거 행은 적재.
     * 개장 전 여부는 KST now 가 결정하므로, 테스트는 "현재 시각이 개장 전인 날에만" 의미가 있다.
     * 모든 실행 시각을 커버하기 위해: 개장 전이면 today stub skip 을, 개장 후면 시그니처 가드를 통해
     * 동일하게 skip 됨을 검증한다(today stub 은 어느 경우든 skip).
     */
    @Test
    fun `today stub 행은 skip 되고 과거 실데이터 행은 적재된다`() {
        // given — today stub(vol=0·open==close) + 과거 실데이터
        val todayStub =
            sampleCandle.copy(
                ts = todayMidnightKst(),
                openRaw = BigDecimal("75000"),
                highRaw = BigDecimal("75000"),
                lowRaw = BigDecimal("75000"),
                closeRaw = BigDecimal("75000"),
                volume = 0L,
            )
        every { kisRestPort.fetchCandles("005930", CandleInterval.ONE_DAY, 1) } returns
            listOf(sampleCandle, todayStub)
        val savedSlot = slot<List<Candle>>()
        every { candleRepository.saveAll(capture(savedSlot)) } returns 1

        // when
        worker.tick()

        // then — 과거 행만 적재, today stub 은 제외
        verify(exactly = 1) { candleRepository.saveAll(any()) }
        assertThat(savedSlot.captured).containsExactly(sampleCandle)
        assertThat(savedSlot.captured).noneMatch { it.ts == todayStub.ts }
    }

    /**
     * (b) 장중 + today 실데이터(vol>0) → 적재.
     * 시간 가드는 now 가 장중일 때만 의미가 있으므로, now 가 개장 전인 실행 시각에는
     * today 행이 시간 가드로 skip 될 수 있다. 따라서 이 케이스는 now 가 장중일 때만 단언한다.
     */
    @Test
    fun `장중 today 실데이터(vol 양수)는 적재된다`() {
        // given — today 실데이터 (vol>0, open != close)
        val todayReal =
            sampleCandle.copy(
                ts = todayMidnightKst(),
                openRaw = BigDecimal("74000"),
                highRaw = BigDecimal("76000"),
                lowRaw = BigDecimal("73500"),
                closeRaw = BigDecimal("75800"),
                volume = 8_500_000L,
            )
        every { kisRestPort.fetchCandles("005930", CandleInterval.ONE_DAY, 1) } returns listOf(todayReal)
        val savedSlot = slot<List<Candle>>()
        every { candleRepository.saveAll(capture(savedSlot)) } returns 1

        // when
        worker.tick()

        // then — now 가 장중일 때만 적재 단언 (개장 전이면 시간 가드로 skip 되는 게 정상)
        if (isPreOpenNow()) {
            verify(exactly = 0) { candleRepository.saveAll(any()) }
        } else {
            verify(exactly = 1) { candleRepository.saveAll(listOf(todayReal)) }
        }
    }

    /**
     * (c) 장중 + today stub 시그니처(vol=0·open==close) → skip (시간 가드와 독립).
     * 개장 전이든 장중이든 today stub 은 항상 skip 되어야 한다.
     */
    @Test
    fun `today stub 시그니처 행은 장중에도 skip 된다`() {
        // given — today stub
        val todayStub =
            sampleCandle.copy(
                ts = todayMidnightKst(),
                openRaw = BigDecimal("75000"),
                highRaw = BigDecimal("75000"),
                lowRaw = BigDecimal("75000"),
                closeRaw = BigDecimal("75000"),
                volume = 0L,
            )
        every { kisRestPort.fetchCandles("005930", CandleInterval.ONE_DAY, 1) } returns listOf(todayStub)

        // when
        worker.tick()

        // then — persistable 비어 saveAll 미호출
        verify(exactly = 0) { candleRepository.saveAll(any()) }
    }

    private fun todayMidnightKst(): Instant {
        val epoch =
            LocalDate
                .now(KST)
                .atStartOfDay(KST)
                .toInstant()
                .epochSecond
        return Instant.fromEpochSeconds(epoch)
    }

    private fun isPreOpenNow(): Boolean {
        val nowKst = java.time.ZonedDateTime.now(KST)
        if (nowKst.dayOfWeek == java.time.DayOfWeek.SATURDAY || nowKst.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
            return true
        }
        val minuteOfDay = nowKst.toLocalTime().let { it.hour * 60 + it.minute }
        return minuteOfDay < CandleSession.NXT_INTEGRATED_OPEN_MIN
    }

    private companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
