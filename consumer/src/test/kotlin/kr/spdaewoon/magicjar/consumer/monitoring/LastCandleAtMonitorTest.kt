package kr.spdaewoon.magicjar.consumer.monitoring

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner
import kr.spdaewoon.magicjar.consumer.ingestion.ColdStartExecutor
import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional

/**
 * LastCandleAtMonitor 단위 테스트 (T-CC Phase 1 — chart-curator 영역).
 *
 * 6 케이스 분기 매트릭스:
 *  (1) watchlist 비어 있음 → graceful return
 *  (2) 한국석유(004090) 일봉 5개월 stale → cold-start submit + Slack alert + audit row 발행 (dogfooding evidence)
 *  (3) 1분봉 1 거래일 stale → cold-start submit (분봉 SLA 분기)
 *  (4) 정상 종목 (일봉·1분봉 모두 임계치 이내) → cold-start 호출 0
 *  (5) 거래정지·정리매매 종목 → skip (KIS_COLD_START_SKIPPED 정합)
 *  (6) 일봉 row 0건 (lastTs null) → stale 표기 + cold-start submit
 *
 * AI 호출 0 — 룰 5 정합 (W3 트랙).
 */
class LastCandleAtMonitorTest {
    private val watchlistJpaRepository = mockk<ConsumerWatchlistJpaRepository>()
    private val candleRepository = mockk<CandleRepository>()
    private val coldStartExecutor = mockk<ColdStartExecutor>(relaxed = true)
    private val aggregateBackfillRunner = mockk<AggregateBackfillRunner>(relaxed = true)
    private val instrumentJpaRepository = mockk<ConsumerInstrumentJpaRepository>()
    private val auditRepository = mockk<ConsumerAuditTrailJpaRepository>(relaxed = true)
    private val slackSender = mockk<SlackWebhookSender>(relaxed = true)
    private val slackSenderProvider = mockk<ObjectProvider<SlackWebhookSender>>()

    private val kst = ZoneId.of("Asia/Seoul")

    private fun newMonitor(): LastCandleAtMonitor {
        every { slackSenderProvider.getIfAvailable() } returns slackSender
        return LastCandleAtMonitor(
            watchlistJpaRepository = watchlistJpaRepository,
            candleRepository = candleRepository,
            coldStartExecutor = coldStartExecutor,
            aggregateBackfillRunner = aggregateBackfillRunner,
            instrumentJpaRepository = instrumentJpaRepository,
            auditRepository = auditRepository,
            slackSenderProvider = slackSenderProvider,
        )
    }

    private fun watchlist(vararg codes: String): List<ConsumerWatchlistJpaEntity> =
        codes.mapIndexed { idx, c ->
            ConsumerWatchlistJpaEntity(id = (idx + 1).toLong(), userId = "u1", code = c)
        }

    private fun normalInstrument(code: String): ConsumerInstrumentJpaEntity =
        ConsumerInstrumentJpaEntity(
            code = code,
            name = "Test-$code",
            market = "KOSPI",
            type = "STOCK",
            active = true,
            tradingHalt = false,
            liquidation = false,
        )

    private fun haltedInstrument(code: String): ConsumerInstrumentJpaEntity =
        ConsumerInstrumentJpaEntity(
            code = code,
            name = "Halted-$code",
            market = "KOSPI",
            type = "STOCK",
            active = true,
            tradingHalt = true,
            liquidation = false,
        )

    @Test
    fun `watchlist 비어 있음 graceful return`() {
        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns emptyList()

        val result = newMonitor().runMonitor("test")

        assertThat(result).isEmpty()
        verify(exactly = 0) { coldStartExecutor.submit(any()) }
        verify(exactly = 0) { slackSender.sendDirect(any()) }
    }

    @Test
    fun `한국석유 일봉 5개월 stale 검출 시 cold-start submit + Slack alert + audit 발행`() {
        val code = "004090" // 한국석유 — dogfooding evidence

        // 2025-12-24 KST 00:00:00 → 약 5개월 stale (사용자 dogfooding 인용)
        val staleDailyTs =
            LocalDate
                .of(2025, 12, 24)
                .atStartOfDay(kst)
                .toInstant()
        // 1분봉은 정상 (어제 09:00 이후) — 일봉만 stale
        val normalMinuteTs = Instant.now().minusSeconds(60 * 60 * 2)

        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns watchlist(code)
        every { instrumentJpaRepository.findById(code) } returns Optional.of(normalInstrument(code))
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_DAY) } returns staleDailyTs
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns normalMinuteTs

        val auditSlot = slot<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(auditSlot)) } answers { firstArg() }

        val slackSlot = slot<String>()
        every { slackSender.sendDirect(capture(slackSlot)) } returns true

        val stale = newMonitor().runMonitor("dogfooding")

        // 일봉 stale 1건만 검출 (1분봉 정상)
        assertThat(stale).hasSize(1)
        assertThat(stale[0].code).isEqualTo(code)
        assertThat(stale[0].interval).isEqualTo(CandleInterval.ONE_DAY)
        assertThat(stale[0].lastTs).isEqualTo(staleDailyTs)
        // 5개월 ≈ 100+ 거래일 (정확 값 변동 OK — 임계치 5 초과만 검증)
        assertThat(stale[0].staleBusinessDays).isGreaterThan(LastCandleAtMonitor.STALE_THRESHOLD_DAILY_BUSINESS_DAYS)

        // cold-start submit 1회 (일봉 stale → cold-start trigger)
        verify(exactly = 1) { coldStartExecutor.submit(code) }
        // Slack alert 1회 — 한국석유 dogfooding evidence 인용 가능 메시지
        verify(exactly = 1) { slackSender.sendDirect(any()) }
        assertThat(slackSlot.captured).contains(code)
        assertThat(slackSlot.captured).contains("stale")
        // audit row 1건 — 운영 audit baseline
        assertThat(auditSlot.captured.action).isEqualTo("CANDLE_LAST_AT_MONITOR")
        assertThat(auditSlot.captured.entityType).isEqualTo("candle")
        assertThat(auditSlot.captured.detailJson).contains("\"trigger\":\"dogfooding\"")
        assertThat(auditSlot.captured.detailJson).contains("\"stale\":1")
        assertThat(auditSlot.captured.detailJson).contains("\"triggered\":1")
    }

    @Test
    fun `1분봉 1 거래일 stale 검출 시 cold-start submit`() {
        val code = "005930"

        // 일봉은 정상 (최신)
        val normalDailyTs = Instant.now().minusSeconds(60 * 60 * 2)
        // 1분봉은 5일 전 stale (1 거래일 임계치 초과)
        val staleMinuteTs =
            LocalDate
                .now(kst)
                .minusDays(5)
                .atStartOfDay(kst)
                .toInstant()

        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns watchlist(code)
        every { instrumentJpaRepository.findById(code) } returns Optional.of(normalInstrument(code))
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_DAY) } returns normalDailyTs
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns staleMinuteTs

        val stale = newMonitor().runMonitor("test")

        assertThat(stale).hasSize(1)
        assertThat(stale[0].interval).isEqualTo(CandleInterval.ONE_MIN)
        verify(exactly = 1) { coldStartExecutor.submit(code) }
    }

    @Test
    fun `정상 종목 일봉·1분봉 모두 임계치 이내 cold-start 호출 0`() {
        val code = "000660"

        val normalDailyTs = Instant.now().minusSeconds(60 * 60 * 2)
        val normalMinuteTs = Instant.now().minusSeconds(60 * 30)

        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns watchlist(code)
        every { instrumentJpaRepository.findById(code) } returns Optional.of(normalInstrument(code))
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_DAY) } returns normalDailyTs
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns normalMinuteTs

        val stale = newMonitor().runMonitor("test")

        assertThat(stale).isEmpty()
        verify(exactly = 0) { coldStartExecutor.submit(any()) }
        // 정상 시 Slack alert 발화 0 (spam 회피)
        verify(exactly = 0) { slackSender.sendDirect(any()) }
    }

    @Test
    fun `거래정지·정리매매 종목 자동 skip cold-start 호출 0`() {
        val code = "999999" // 거래정지 가정

        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns watchlist(code)
        every { instrumentJpaRepository.findById(code) } returns Optional.of(haltedInstrument(code))
        // candleRepository 호출 안 됨 — skip path

        val stale = newMonitor().runMonitor("test")

        assertThat(stale).isEmpty()
        verify(exactly = 0) { coldStartExecutor.submit(any()) }
        verify(exactly = 0) { candleRepository.findMaxTsByCodeAndInterval(any(), any()) }
    }

    @Test
    fun `일봉 row 0건 lastTs null 시 stale 표기 + cold-start submit`() {
        val code = "035420"

        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns watchlist(code)
        every { instrumentJpaRepository.findById(code) } returns Optional.of(normalInstrument(code))
        // 일봉·1분봉 모두 row 0건
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_DAY) } returns null
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns null

        val stale = newMonitor().runMonitor("test")

        // 일봉·1분봉 각 1건씩 stale로 표기
        assertThat(stale).hasSize(2)
        assertThat(stale.map { it.staleBusinessDays }.toSet()).containsExactly(Long.MAX_VALUE)
        assertThat(stale.map { it.lastTs }.toSet()).containsExactly(null)
        // 동일 종목 중복 trigger 방지 — submit은 1회만
        verify(exactly = 1) { coldStartExecutor.submit(code) }
    }

    @Test
    fun `Slack sender 미등록 시 graceful skip - alert 발화 0`() {
        val code = "004090"
        val staleDailyTs = LocalDate.of(2025, 12, 24).atStartOfDay(kst).toInstant()
        val normalMinuteTs = Instant.now().minusSeconds(60 * 60 * 2)

        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns watchlist(code)
        every { instrumentJpaRepository.findById(code) } returns Optional.of(normalInstrument(code))
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_DAY) } returns staleDailyTs
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns normalMinuteTs

        // SlackWebhookSender 미등록 (예: notification 비활성 환경)
        every { slackSenderProvider.getIfAvailable() } returns null

        val monitor =
            LastCandleAtMonitor(
                watchlistJpaRepository = watchlistJpaRepository,
                candleRepository = candleRepository,
                coldStartExecutor = coldStartExecutor,
                aggregateBackfillRunner = aggregateBackfillRunner,
                instrumentJpaRepository = instrumentJpaRepository,
                auditRepository = auditRepository,
                slackSenderProvider = slackSenderProvider,
            )

        val stale = monitor.runMonitor("test")

        // stale 검출 + cold-start 발화는 정상
        assertThat(stale).hasSize(1)
        verify(exactly = 1) { coldStartExecutor.submit(code) }
        // Slack 발화는 graceful skip
        verify(exactly = 0) { slackSender.sendDirect(any()) }
    }
}
