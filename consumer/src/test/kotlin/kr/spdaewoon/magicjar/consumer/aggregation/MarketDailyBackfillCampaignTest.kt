package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.config.MarketDailyBackfillProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerMarketDailyUniverseJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisDailyCandleRangePort
import kr.spdaewoon.magicjar.domain.market.port.KisDailyRangeUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.datetime.Instant as KtInstant

/**
 * MarketDailyBackfillCampaign — ADR 0118 R-BF 멱등 캠페인 단위테스트.
 *
 * 검증 매트릭스 (PR 본문 evidence):
 *   - ENV off → DISABLED 즉시 종료(가동 0, KIS 콜 0).
 *   - progress skip(멱등) — 이미 완료(row≥cap) 종목 fetch 0.
 *   - listed_at clamp — fetch from = max(today-10y, listed_at).
 *   - 콜 cap 이연 — cap 도달 시 CAP_REACHED, 미처리 종목은 다음 실행 이연.
 *   - throttle/circuit OPEN → 캠페인 run 이월(다음 실행).
 *   - DO NOTHING idempotency — saveAll(과거봉 immutable) 경로 사용.
 *   - range fetch 실패(fail-closed) → 종목 완료 마킹 0 + remaining 유지.
 *
 * 모든 KIS 콜은 [KisDailyCandleRangePort](어댑터 BACKFILL 버킷 고정 — 어댑터 단위테스트에서 별도 검증)으로만 발생.
 */
class MarketDailyBackfillCampaignTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.now(zone)
    private val toDate = today.minusDays(1)

    private fun candle(
        code: String,
        date: LocalDate,
    ): Candle =
        Candle(
            code = code,
            interval = CandleInterval.ONE_DAY,
            ts = KtInstant.fromEpochSeconds(date.atStartOfDay(zone).toEpochSecond()),
            openRaw = BigDecimal("1000"),
            highRaw = BigDecimal("1100"),
            lowRaw = BigDecimal("950"),
            closeRaw = BigDecimal("1050"),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 10_000L,
            source = "KIS",
        )

    private fun props(
        enabled: Boolean = true,
        nightlyCallCap: Int = 6000,
        completionMinRows: Int = 200,
        historyYears: Int = 10,
        recencyGraceDays: Int = 7,
    ) = MarketDailyBackfillProperties(
        enabled = enabled,
        historyYears = historyYears,
        nightlyCallCap = nightlyCallCap,
        completionMinRows = completionMinRows,
        recencyGraceDays = recencyGraceDays,
    )

    private fun newCampaign(
        properties: MarketDailyBackfillProperties,
        provider: MarketDailyUniverseProvider,
        rangePort: KisDailyCandleRangePort,
        candleRepo: CandleRepository,
        universeRepo: ConsumerMarketDailyUniverseJpaRepository = mockk(relaxed = true),
        throttle: BackfillThrottleGate = passThroughThrottle(),
    ): MarketDailyBackfillCampaign {
        val worker = mockk<CandleBackfillWorker>()
        // sanity check 재사용 — 본 테스트는 통과 candle을 그대로 반환(정합성 검증은 CandleBackfillWorkerTest 책임).
        every { worker.sanityCheck(any(), any()) } answers { firstArg() }
        val audit = mockk<ConsumerAuditTrailJpaRepository>(relaxed = true)
        return MarketDailyBackfillCampaign(
            universeProvider = provider,
            universeRepository = universeRepo,
            kisDailyRangePort = rangePort,
            candleRepository = candleRepo,
            candleBackfillWorker = worker,
            backfillThrottleGate = throttle,
            auditRepository = audit,
            objectMapper = ObjectMapper(),
            properties = properties,
        )
    }

    /** throttle gate — block을 그대로 실행(통과)하는 stub. */
    private fun passThroughThrottle(): BackfillThrottleGate {
        val gate = mockk<BackfillThrottleGate>(relaxed = true)
        every { gate.runThrottled<Any?>(any(), any()) } answers { secondArg<() -> Any?>().invoke() }
        return gate
    }

    private fun provider(codes: List<String>): MarketDailyUniverseProvider {
        val p = mockk<MarketDailyUniverseProvider>()
        every { p.resolveCodes() } returns codes
        every { p.count() } returns codes.size.toLong()
        return p
    }

    @Test
    fun `ENV off - DISABLED 즉시 종료 (KIS 콜 0)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>()
        val campaign = newCampaign(props(enabled = false), provider(listOf("005930")), rangePort, candleRepo)

        val r = campaign.runCampaign()

        assertThat(r.status).isEqualTo(MarketDailyBackfillCampaign.CampaignStatus.DISABLED)
        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `EMPTY_UNIVERSE - 보통주 0종목 (mock 합성 0)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val campaign = newCampaign(props(), provider(emptyList()), rangePort, mockk(relaxed = true))

        val r = campaign.runCampaign()

        assertThat(r.status).isEqualTo(MarketDailyBackfillCampaign.CampaignStatus.EMPTY_UNIVERSE)
        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `progress skip (멱등) - 이미 완료(row 충분) 종목 fetch 0`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>()
        // 완료 판정 row≥completionMinRows(200) — 2500 row면 완료.
        every { candleRepo.countByCodeAndInterval("005930", CandleInterval.ONE_DAY) } returns 2500L
        every { candleRepo.findMaxTsByCodeAndInterval("005930", CandleInterval.ONE_DAY) } returns
            toDate.atStartOfDay(zone).toInstant()

        val campaign = newCampaign(props(), provider(listOf("005930")), rangePort, candleRepo)
        val r = campaign.runCampaign()

        assertThat(r.alreadyComplete).isEqualTo(1)
        assertThat(r.status).isEqualTo(MarketDailyBackfillCampaign.CampaignStatus.COMPLETED)
        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `listed_at clamp - fetch from = max(today-10y, listed_at)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>()
        // 미완료(row 0).
        every { candleRepo.countByCodeAndInterval("999999", CandleInterval.ONE_DAY) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        // 상장 3년 전 — historyFloor(10년 전)보다 늦으므로 clamp 경계 = listed_at.
        val listedAt = today.minusYears(3)
        every { universeRepo.findListedAtByCode("999999") } returns listedAt

        val fromSlot = slot<LocalDate>()
        every {
            rangePort.fetchDailyCandlesInRange("999999", capture(fromSlot), any())
        } returns listOf(candle("999999", listedAt.plusDays(10)))
        every { candleRepo.saveAll(any()) } returns 1

        val campaign = newCampaign(props(), provider(listOf("999999")), rangePort, candleRepo, universeRepo)
        campaign.runCampaign()

        // clamp 경계 = listed_at (historyFloor=today-10y 보다 늦음).
        assertThat(fromSlot.captured).isEqualTo(listedAt)
    }

    @Test
    fun `listed_at null - historyFloor(today-10y) 사용`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>()
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode("005930") } returns null

        val fromSlot = slot<LocalDate>()
        every { rangePort.fetchDailyCandlesInRange("005930", capture(fromSlot), any()) } returns emptyList()

        val campaign = newCampaign(props(historyYears = 10), provider(listOf("005930")), rangePort, candleRepo, universeRepo)
        campaign.runCampaign()

        assertThat(fromSlot.captured).isEqualTo(today.minusYears(10))
    }

    @Test
    fun `콜 cap 이연 - cap 도달 시 CAP_REACHED + 잔여 종목 다음 실행 이연`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        // 모든 종목 미완료.
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(10)
        // 종목당 대량 적재 → 1종목 처리에 estimateCalls(~26콜). cap=10 이면 1종목 후 cap 초과.
        every { rangePort.fetchDailyCandlesInRange(any(), any(), any()) } returns
            listOf(candle("A", today.minusYears(5)))
        every { candleRepo.saveAll(any()) } returns 1

        val campaign =
            newCampaign(props(nightlyCallCap = 10), provider(listOf("000001", "000002", "000003")), rangePort, candleRepo, universeRepo)
        val r = campaign.runCampaign()

        // cap=10 < estimateCalls 누적 → 1종목 처리 후 cap 도달로 중단.
        assertThat(r.status).isEqualTo(MarketDailyBackfillCampaign.CampaignStatus.CAP_REACHED)
        assertThat(r.processedSymbols).isEqualTo(1)
        assertThat(r.remainingSymbols).isGreaterThan(0)
        verify(exactly = 1) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `DO NOTHING idempotency - saveAll(과거봉 immutable) 경로 사용`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(10)
        every { rangePort.fetchDailyCandlesInRange(any(), any(), any()) } returns
            listOf(candle("005930", today.minusYears(2)))
        val saveSlot = slot<List<Candle>>()
        every { candleRepo.saveAll(capture(saveSlot)) } returns 1

        val campaign = newCampaign(props(nightlyCallCap = 9999), provider(listOf("005930")), rangePort, candleRepo, universeRepo)
        campaign.runCampaign()

        // upsertAll(ON CONFLICT DO UPDATE)이 아니라 saveAll(DO NOTHING) 사용 — 과거봉 immutable.
        verify(exactly = 1) { candleRepo.saveAll(any()) }
        verify(exactly = 0) { candleRepo.upsertAll(any()) }
        // source 마킹 검증 — 상수 BACKFILL_SOURCE.
        assertThat(saveSlot.captured.first().source).isEqualTo(MarketDailyBackfillCampaign.BACKFILL_SOURCE)
    }

    @Test
    fun `회귀 가드 - BACKFILL_SOURCE 길이 candle source VARCHAR(20) 이내`() {
        // candle.source = VARCHAR(20) (V1__baseline.sql:115). 마킹이 20자를 넘으면
        // PostgreSQL 22001(value too long)로 saveAll 전건 실패 → 0행 적재.
        // 직전 결함 "KIS-MARKET-DAILY-BACKFILL"(25자) 재발 방지.
        assertThat(MarketDailyBackfillCampaign.BACKFILL_SOURCE.length)
            .isLessThanOrEqualTo(MarketDailyBackfillCampaign.SOURCE_MAX_LEN)
        assertThat(MarketDailyBackfillCampaign.SOURCE_MAX_LEN).isEqualTo(20)
    }

    @Test
    fun `회귀 가드 - saveAll 에 적재되는 candle source 가 varchar(20) 위반 0`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(10)
        every { rangePort.fetchDailyCandlesInRange(any(), any(), any()) } returns
            listOf(candle("005930", today.minusYears(2)), candle("005930", today.minusYears(1)))
        val saveSlot = slot<List<Candle>>()
        every { candleRepo.saveAll(capture(saveSlot)) } returns 2

        val campaign = newCampaign(props(nightlyCallCap = 9999), provider(listOf("005930")), rangePort, candleRepo, universeRepo)
        campaign.runCampaign()

        // 적재되는 모든 candle의 source 가 varchar(20) 한계를 넘지 않음 (전건 길이 위반 0).
        assertThat(saveSlot.captured).isNotEmpty
        assertThat(saveSlot.captured).allSatisfy { c ->
            assertThat(c.source!!.length).isLessThanOrEqualTo(MarketDailyBackfillCampaign.SOURCE_MAX_LEN)
        }
    }

    @Test
    fun `range fetch 실패(fail-closed) - 종목 완료 마킹 0 + failed 집계`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(10)
        every { rangePort.fetchDailyCandlesInRange(any(), any(), any()) } throws
            KisDailyRangeUnavailableException("KIS rate limit")

        val campaign = newCampaign(props(nightlyCallCap = 9999), provider(listOf("005930")), rangePort, candleRepo, universeRepo)
        val r = campaign.runCampaign()

        assertThat(r.failedSymbols).isEqualTo(1)
        assertThat(r.insertedRows).isEqualTo(0)
        // saveAll 미호출(fetch 실패) — 종목은 미완료로 남아 다음 실행 재시도.
        verify(exactly = 0) { candleRepo.saveAll(any()) }
        assertThat(r.remainingSymbols).isEqualTo(1)
    }

    @Test
    fun `throttle OPEN - 캠페인 run 이월 (다음 실행 이연)`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        every { universeRepo.findListedAtByCode(any()) } returns today.minusYears(10)
        // throttle gate가 null 반환(circuit OPEN/permit timeout) → 캠페인 break.
        val throttle = mockk<BackfillThrottleGate>(relaxed = true)
        every { throttle.runThrottled<Any?>(any(), any()) } returns null

        val campaign =
            newCampaign(props(), provider(listOf("000001", "000002")), rangePort, candleRepo, universeRepo, throttle = throttle)
        val r = campaign.runCampaign()

        assertThat(r.skippedThrottle).isEqualTo(1)
        assertThat(r.status).isEqualTo(MarketDailyBackfillCampaign.CampaignStatus.CAP_REACHED)
        // 첫 종목에서 throttle → break. 두 번째 종목 fetch 0.
        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
    }

    @Test
    fun `초신규 상장(상장일 가장 늦음) - 거래일 0 종목 완료 간주 fetch 0`() {
        val rangePort = mockk<KisDailyCandleRangePort>()
        val candleRepo = mockk<CandleRepository>(relaxed = true)
        val universeRepo = mockk<ConsumerMarketDailyUniverseJpaRepository>(relaxed = true)
        every { candleRepo.countByCodeAndInterval(any(), any()) } returns 0L
        every { candleRepo.findMaxTsByCodeAndInterval(any(), any()) } returns null
        // 상장일이 toDate(어제) 이후 — 적재 대상 거래일 0.
        every { universeRepo.findListedAtByCode("777777") } returns today.plusDays(5)

        val campaign = newCampaign(props(), provider(listOf("777777")), rangePort, candleRepo, universeRepo)
        val r = campaign.runCampaign()

        verify(exactly = 0) { rangePort.fetchDailyCandlesInRange(any(), any(), any()) }
        assertThat(r.processedSymbols).isEqualTo(1)
    }
}
