package kr.spdaewoon.magicjar.consumer.application.market

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.aggregation.CandleBackfillWorker
import kr.spdaewoon.magicjar.consumer.config.MarketDailySnapshotProperties
import kr.spdaewoon.magicjar.consumer.config.TodayCloseAuthority
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisMultiPricePort
import kr.spdaewoon.magicjar.domain.market.port.MultiPriceQuote
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * MarketDailySnapshotScheduler — ADR 0118 R2 단위테스트.
 *
 * 검증 매트릭스 (PR 본문 evidence):
 *   1. 영업일 게이트 — 비영업일 skip(universe·KIS·upsert 0).
 *   2. universe 0건 — empty-universe skip(KIS 0).
 *   3. today 봉 DO UPDATE — sane 봉을 candleRepository.upsertAll로만 적재(공식값 덮기).
 *   4. ★ today-only 경계 — upsert 대상 모든 봉의 ts = today KST 00:00 단일 + interval=1d (과거봉 ts 생성 0).
 *   5. 과거봉 immutable — saveAll(DO NOTHING) 경로 호출 0 (upsertAll만).
 *   6. NXT 토글 — NXT_INTEGRATED → mrktDivCode "NX" + source ...-NXT.
 *   7. 정규장 토글 — KRX_REGULAR_WITH_FINALIZER_FALLBACK → mrktDivCode "J" + source ...-KRX.
 *   8. ~84콜 산정 — expectedCalls = ceil(universe/30).
 *   9. sanity drop — OHLC 위반 봉은 upsert 제외(과거봉 오염 방어).
 *   10. OHLC 결손 종목 graceful skip(mock 0).
 *
 * priority BG는 어댑터([kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisMultiPriceAdapter]) 내부
 * KisCallPriority.BACKFILL 고정 — 스케줄러는 어댑터 포트만 호출하므로 BG 보장은 어댑터 단위에서 검증.
 */
class MarketDailySnapshotSchedulerTest {
    private val zone = ZoneId.of("Asia/Seoul")

    private val universeProvider = mockk<MarketDailyUniverseProvider>()
    private val multiPricePort = mockk<KisMultiPricePort>()
    private val candleRepository = mockk<CandleRepository>(relaxed = true)
    private val backfillWorker = mockk<CandleBackfillWorker>()
    private val businessDayCalendar = mockk<BusinessDayCalendar>()

    private fun scheduler(props: MarketDailySnapshotProperties) =
        MarketDailySnapshotScheduler(
            marketDailyUniverseProvider = universeProvider,
            kisMultiPricePort = multiPricePort,
            candleRepository = candleRepository,
            candleBackfillWorker = backfillWorker,
            businessDayCalendar = businessDayCalendar,
            properties = props,
        )

    private fun quote(
        code: String,
        open: String? = "1000",
        high: String? = "1100",
        low: String? = "950",
        close: String? = "1050",
        vol: Long? = 12345L,
    ) = MultiPriceQuote(
        code = code,
        open = open?.let { BigDecimal(it) },
        high = high?.let { BigDecimal(it) },
        low = low?.let { BigDecimal(it) },
        close = close?.let { BigDecimal(it) },
        previousClose = BigDecimal("1040"),
        accumulatedVolume = vol,
        accumulatedTradeAmount = BigDecimal("13000000"),
        kospiKosdaqClsName = "KOSPI",
        marketTreatmentClsName = null,
        hourClsCode = null,
    )

    private val nxtProps =
        MarketDailySnapshotProperties(
            enabled = true,
            todayCloseAuthority = TodayCloseAuthority.NXT_INTEGRATED,
        )

    // 1. 영업일 게이트 — 비영업일 skip
    @Test
    fun `비영업일이면 universe·KIS·upsert 호출 0으로 skip`() {
        val date = LocalDate.of(2026, 6, 13) // 토요일
        every { businessDayCalendar.isBusinessDay(date) } returns false

        val result = scheduler(nxtProps).run(date)

        assertThat(result.skipped).isTrue()
        assertThat(result.skipReason).isEqualTo("non-business-day")
        verify(exactly = 0) { universeProvider.resolveCodes() }
        verify(exactly = 0) { multiPricePort.fetchMultiPrice(any(), any()) }
        verify(exactly = 0) { candleRepository.upsertAll(any()) }
        verify(exactly = 0) { candleRepository.saveAll(any()) }
    }

    // 2. universe 0건 — empty-universe skip
    @Test
    fun `universe 0건이면 KIS 호출 0으로 skip`() {
        val date = LocalDate.of(2026, 6, 9)
        every { businessDayCalendar.isBusinessDay(date) } returns true
        every { universeProvider.resolveCodes() } returns emptyList()

        val result = scheduler(nxtProps).run(date)

        assertThat(result.skipped).isTrue()
        assertThat(result.skipReason).isEqualTo("empty-universe")
        verify(exactly = 0) { multiPricePort.fetchMultiPrice(any(), any()) }
        verify(exactly = 0) { candleRepository.upsertAll(any()) }
    }

    // 3·4·5. today 봉 DO UPDATE + today-only 경계 + 과거봉 immutable(saveAll 0)
    @Test
    fun `today 1d 봉만 upsertAll로 적재 - ts는 today 단일 interval 1d - saveAll 0`() {
        val date = LocalDate.of(2026, 6, 9)
        every { businessDayCalendar.isBusinessDay(date) } returns true
        every { universeProvider.resolveCodes() } returns listOf("005930", "000660")
        every { multiPricePort.fetchMultiPrice(any(), any()) } returns
            listOf(quote("005930"), quote("000660"))
        // sanity는 입력 그대로 통과(실제 OHLC 정합 봉)
        val saneSlot = slot<List<Candle>>()
        every { backfillWorker.sanityCheck(capture(saneSlot), any()) } answers { saneSlot.captured }
        val upsertSlot = slot<List<Candle>>()
        every { candleRepository.upsertAll(capture(upsertSlot)) } returns 2

        scheduler(nxtProps).run(date)

        // upsertAll만 호출, saveAll(DO NOTHING 과거봉 경로) 0
        verify(exactly = 1) { candleRepository.upsertAll(any()) }
        verify(exactly = 0) { candleRepository.saveAll(any()) }

        // ★ today-only 경계 — 모든 봉 ts = today KST 00:00, interval=1d
        val expectedTs = date.atStartOfDay(zone).toEpochSecond()
        assertThat(upsertSlot.captured).hasSize(2)
        assertThat(upsertSlot.captured).allMatch { it.interval == CandleInterval.ONE_DAY }
        assertThat(upsertSlot.captured).allMatch { it.ts.epochSeconds == expectedTs }
        assertThat(upsertSlot.captured.map { it.code }).containsExactlyInAnyOrder("005930", "000660")
    }

    // 6. NXT 토글 → mrktDivCode "NX" + source ...-NXT
    @Test
    fun `NXT_INTEGRATED 토글이면 mrktDivCode NX와 NXT source`() {
        val date = LocalDate.of(2026, 6, 9)
        every { businessDayCalendar.isBusinessDay(date) } returns true
        every { universeProvider.resolveCodes() } returns listOf("005930")
        val divSlot = slot<String>()
        every { multiPricePort.fetchMultiPrice(any(), capture(divSlot)) } returns listOf(quote("005930"))
        every { backfillWorker.sanityCheck(any(), any()) } answers { firstArg() }
        val upsertSlot = slot<List<Candle>>()
        every { candleRepository.upsertAll(capture(upsertSlot)) } returns 1

        scheduler(nxtProps).run(date)

        assertThat(divSlot.captured).isEqualTo("NX")
        assertThat(upsertSlot.captured.first().source).isEqualTo("KIS-MARKET-DAILY-SNAPSHOT-NXT")
    }

    // 7. 정규장 토글 → mrktDivCode "J" + source ...-KRX
    @Test
    fun `KRX_REGULAR 폴백 토글이면 mrktDivCode J와 KRX source`() {
        val date = LocalDate.of(2026, 6, 9)
        val krxProps =
            MarketDailySnapshotProperties(
                enabled = true,
                todayCloseAuthority = TodayCloseAuthority.KRX_REGULAR_WITH_FINALIZER_FALLBACK,
            )
        every { businessDayCalendar.isBusinessDay(date) } returns true
        every { universeProvider.resolveCodes() } returns listOf("005930")
        val divSlot = slot<String>()
        every { multiPricePort.fetchMultiPrice(any(), capture(divSlot)) } returns listOf(quote("005930"))
        every { backfillWorker.sanityCheck(any(), any()) } answers { firstArg() }
        val upsertSlot = slot<List<Candle>>()
        every { candleRepository.upsertAll(capture(upsertSlot)) } returns 1

        scheduler(krxProps).run(date)

        assertThat(divSlot.captured).isEqualTo("J")
        assertThat(upsertSlot.captured.first().source).isEqualTo("KIS-MARKET-DAILY-SNAPSHOT-KRX")
    }

    // 8. ~84콜 산정 — expectedCalls = ceil(universe/30)
    @Test
    fun `expectedCalls는 universe를 30으로 나눈 올림 - 2517종목이면 84콜`() {
        val date = LocalDate.of(2026, 6, 9)
        val codes = (1..2517).map { it.toString().padStart(6, '0') }
        every { businessDayCalendar.isBusinessDay(date) } returns true
        every { universeProvider.resolveCodes() } returns codes
        every { multiPricePort.fetchMultiPrice(any(), any()) } returns emptyList()
        every { backfillWorker.sanityCheck(any(), any()) } answers { firstArg() }

        val result = scheduler(nxtProps).run(date)

        assertThat(result.expectedCalls).isEqualTo(84) // ceil(2517/30)=84
        assertThat(result.universeSize).isEqualTo(2517)
    }

    // 9. sanity drop — OHLC 위반 봉은 upsert 제외
    @Test
    fun `sanity가 drop한 봉은 upsert 대상에서 빠진다`() {
        val date = LocalDate.of(2026, 6, 9)
        every { businessDayCalendar.isBusinessDay(date) } returns true
        every { universeProvider.resolveCodes() } returns listOf("005930", "000660")
        every { multiPricePort.fetchMultiPrice(any(), any()) } returns
            listOf(quote("005930"), quote("000660", high = "900")) // 000660 OHLC 위반(high<close)
        // sanityCheck를 실제 동작처럼 — high<max(open,close) 봉 제거
        every { backfillWorker.sanityCheck(any(), any()) } answers {
            firstArg<List<Candle>>().filter { it.highRaw >= maxOf(it.openRaw, it.closeRaw) && it.lowRaw <= minOf(it.openRaw, it.closeRaw) }
        }
        val upsertSlot = slot<List<Candle>>()
        every { candleRepository.upsertAll(capture(upsertSlot)) } returns 1

        val result = scheduler(nxtProps).run(date)

        assertThat(upsertSlot.captured.map { it.code }).containsExactly("005930")
        assertThat(result.sanityDropped).isEqualTo(1)
    }

    // 10. OHLC 결손 종목 graceful skip (mock 0)
    @Test
    fun `OHLC 결손 종목은 봉 변환 0으로 graceful skip`() {
        val date = LocalDate.of(2026, 6, 9)
        every { businessDayCalendar.isBusinessDay(date) } returns true
        every { universeProvider.resolveCodes() } returns listOf("005930", "000660")
        every { multiPricePort.fetchMultiPrice(any(), any()) } returns
            listOf(quote("005930"), quote("000660", close = null)) // close 결손
        every { backfillWorker.sanityCheck(any(), any()) } answers { firstArg() }
        val upsertSlot = slot<List<Candle>>()
        every { candleRepository.upsertAll(capture(upsertSlot)) } returns 1

        scheduler(nxtProps).run(date)

        // close 결손 종목은 toTodayCandle null → 변환 제외
        assertThat(upsertSlot.captured.map { it.code }).containsExactly("005930")
    }

    // scheduled() — enabled=false면 아무것도 안 함
    @Test
    fun `enabled false면 scheduled가 영업일 게이트도 타지 않고 종료`() {
        val disabled = MarketDailySnapshotProperties(enabled = false)
        scheduler(disabled).scheduled()
        verify(exactly = 0) { businessDayCalendar.isBusinessDay(any()) }
        verify(exactly = 0) { multiPricePort.fetchMultiPrice(any(), any()) }
    }

    // toTodayCandle volume null → 0 보정
    @Test
    fun `volume null이면 0으로 보정된 봉 생성`() {
        val candle =
            scheduler(nxtProps).toTodayCandle(
                quote("005930", vol = null),
                kotlinx.datetime.Instant.fromEpochSeconds(1_700_000_000L),
                "KIS-MARKET-DAILY-SNAPSHOT-NXT",
            )
        assertThat(candle).isNotNull
        assertThat(candle!!.volume).isEqualTo(0L)
    }
}
