package kr.spdaewoon.magicjar.consumer.aggregation

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleGapDetectorPort
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import kotlinx.datetime.Instant as KtInstant

/**
 * CandleBackfillWorker 단위 테스트 — 햇징 룰 10건 검증 (ADR 0086 §2-1).
 *
 * 룰 ↔ 테스트 매핑:
 *   #1 (Double Verification) → sanity_OHLC, sanity_price_range, sanity_volume_negative, sanity_time_monotonic
 *   #2 (Dry-run)              → dry_run_no_db_write
 *   #3 (Rollback)             → rollback_audit_recorded, backfill_batch_id_format
 *   #4 (Idempotency)          → idempotency_uses_saveAll (UPSERT 위임)
 *   #5 (Source-of-truth)      → source_of_truth_kis_raw_only (source 필드 LOCK)
 *   #6 (False positive 가드)  → skip_trading_halt
 *   #7 (False negative 가드)  → no_gaps_skipped_audit
 *   #8 (Retry + KillSwitch)   → killswitch_on_total_fail, retry_3times
 *   #9 (Audit trail)          → audit_5_paths (START·SUCCESS·FAIL·SKIPPED·ROLLBACK)
 *   #10 (운영자 우회)         → CandleBackfillAdminControllerTest 별도
 */
class CandleBackfillWorkerTest {
    private val candleGapDetector = mockk<CandleGapDetectorPort>()
    private val candleRepository = mockk<CandleRepository>()
    private val kisRestPort = mockk<KisRestPort>()
    private val instrumentJpaRepository = mockk<ConsumerInstrumentJpaRepository>()
    private val auditRepository = mockk<ConsumerAuditTrailJpaRepository>()
    private val slackSender = mockk<SlackWebhookSender>()
    private val killSwitchRepo = mockk<KillSwitchJpaRepository>()

    private val slackProvider =
        mockk<ObjectProvider<SlackWebhookSender>>().also {
            every { it.getIfAvailable() } returns slackSender
        }

    private val ksProvider =
        mockk<ObjectProvider<KillSwitchJpaRepository>>().also {
            every { it.getIfAvailable() } returns killSwitchRepo
        }

    private fun newWorker(dryRun: Boolean = false): CandleBackfillWorker =
        CandleBackfillWorker(
            candleGapDetector = candleGapDetector,
            candleRepository = candleRepository,
            kisRestPort = kisRestPort,
            instrumentJpaRepository = instrumentJpaRepository,
            auditRepository = auditRepository,
            slackSenderProvider = slackProvider,
            killSwitchJpaRepositoryProvider = ksProvider,
            dryRunPropertyDefault = dryRun,
            windowDaysDefault = 30,
        )

    private fun candle(
        code: String = "004090",
        tsEpochSec: Long,
        o: String = "100",
        h: String = "110",
        l: String = "95",
        c: String = "105",
        volume: Long = 1000L,
    ): Candle =
        Candle(
            code = code,
            interval = CandleInterval.ONE_DAY,
            ts = KtInstant.fromEpochSeconds(tsEpochSec),
            openRaw = BigDecimal(o),
            highRaw = BigDecimal(h),
            lowRaw = BigDecimal(l),
            closeRaw = BigDecimal(c),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = volume,
            source = "KIS",
        )

    private fun setupNoInstrument(code: String = "004090") {
        every { instrumentJpaRepository.findById(code) } returns Optional.empty()
    }

    private fun setupAuditCapture() {
        every { auditRepository.save(any<ConsumerAuditTrailJpaEntity>()) } answers
            { firstArg<ConsumerAuditTrailJpaEntity>() }
    }

    // ---------- 룰 #1 sanity check ----------

    @Test
    fun `룰 #1 OHLC sanity — high lt close 위반 candle 제외`() {
        val worker = newWorker()
        val valid = candle(tsEpochSec = 1_700_000_000L)
        // high=90 < close=105 위반
        val invalid =
            candle(
                tsEpochSec = 1_700_086_400L,
                h = "90", // high < close
                c = "105",
            )
        val result = worker.sanityCheck(listOf(valid, invalid), "test-batch")
        assertThat(result).hasSize(1)
        assertThat(result[0].ts).isEqualTo(valid.ts)
    }

    @Test
    fun `룰 #1 price range — 음수 가격 제외 + 1e9 초과 제외`() {
        val worker = newWorker()
        val negative = candle(tsEpochSec = 1_700_000_000L, o = "-1")
        val overflow = candle(tsEpochSec = 1_700_086_400L, c = "2000000000")
        val valid = candle(tsEpochSec = 1_700_172_800L)
        val result = worker.sanityCheck(listOf(negative, overflow, valid), "test-batch")
        assertThat(result).hasSize(1)
        assertThat(result[0].ts).isEqualTo(valid.ts)
    }

    @Test
    fun `룰 #1 volume — 음수 volume 제외`() {
        val worker = newWorker()
        val invalid = candle(tsEpochSec = 1_700_000_000L, volume = -1L)
        val valid = candle(tsEpochSec = 1_700_086_400L)
        val result = worker.sanityCheck(listOf(invalid, valid), "test-batch")
        assertThat(result).hasSize(1)
        assertThat(result[0].volume).isEqualTo(1000L)
    }

    @Test
    fun `룰 #1 time monotonic — ts 역순 candle 제외`() {
        val worker = newWorker()
        val first = candle(tsEpochSec = 1_700_086_400L)
        val outOfOrder = candle(tsEpochSec = 1_700_000_000L) // 이전 ts
        val result = worker.sanityCheck(listOf(first, outOfOrder), "test-batch")
        assertThat(result).hasSize(1)
        assertThat(result[0].ts.epochSeconds).isEqualTo(1_700_086_400L)
    }

    // ---------- 룰 #2 Dry-run ----------

    @Test
    fun `룰 #2 dry-run — KIS fetch 0 + DB 적재 0 + audit SUCCESS dryRun true`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns
            listOf(Instant.ofEpochSecond(1_700_000_000L))

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = true)

        assertThat(result.dryRun).isTrue
        assertThat(result.insertedCount).isEqualTo(0)
        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.SUCCESS)
        // KIS fetch 호출 0
        verify(exactly = 0) { kisRestPort.fetchCandles(any(), any(), any()) }
        // DB saveAll 호출 0
        verify(exactly = 0) { candleRepository.saveAll(any()) }
        // audit START + SUCCESS 2건
        verify(atLeast = 2) { auditRepository.save(any<ConsumerAuditTrailJpaEntity>()) }
    }

    // ---------- 룰 #3 Rollback ----------

    @Test
    fun `룰 #3 backfill_batch_id — 형식 LOCK candle-backfill code interval epochMs UUID`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns emptyList()

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        assertThat(result.backfillBatchId).matches("candle-backfill:004090:1d:\\d+-[0-9a-f-]+")
    }

    @Test
    fun `룰 #3 recordRollback — audit CANDLE_BACKFILL_ROLLBACK 발행`() {
        val worker = newWorker()
        setupAuditCapture()
        val captured = slot<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { captured.captured }

        worker.recordRollback(
            backfillBatchId = "candle-backfill:004090:1d:1700000000000-uuid",
            deletedCount = 42,
            reason = "manual",
        )

        assertThat(captured.captured.action).isEqualTo("CANDLE_BACKFILL_ROLLBACK")
        assertThat(captured.captured.detailJson).contains("deletedCount\":42")
        assertThat(captured.captured.detailJson).contains("reason\":\"manual\"")
    }

    // ---------- 룰 #4 Idempotency ----------

    @Test
    fun `룰 #4 idempotency — saveAll 단일 호출로 UPSERT 위임 (ON CONFLICT DO NOTHING)`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        val gapTs = Instant.ofEpochSecond(1_700_000_000L)
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns listOf(gapTs)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns
            listOf(candle(tsEpochSec = 1_700_000_000L))
        val saveSlot = slot<List<Candle>>()
        every { candleRepository.saveAll(capture(saveSlot)) } returns 1

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        // saveAll 정확히 1회 호출 (idempotency UPSERT 위임)
        verify(exactly = 1) { candleRepository.saveAll(any()) }
        assertThat(result.insertedCount).isEqualTo(1)
        assertThat(saveSlot.captured).hasSize(1)
    }

    // ---------- 룰 #5 Source-of-truth ----------

    @Test
    fun `룰 #5 source-of-truth — source 필드 KIS-BACKFILL LOCK (raw 권위)`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        val gapTs = Instant.ofEpochSecond(1_700_000_000L)
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns listOf(gapTs)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns
            listOf(candle(tsEpochSec = 1_700_000_000L)) // source="KIS"
        val saveSlot = slot<List<Candle>>()
        every { candleRepository.saveAll(capture(saveSlot)) } returns 1

        worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        // saveAll 호출된 candle의 source는 KIS-BACKFILL LOCK
        assertThat(saveSlot.captured[0].source).isEqualTo("KIS-BACKFILL")
        // 합성 데이터 적재 0건 — fetched candle만 saveAll
        assertThat(saveSlot.captured).hasSize(1)
    }

    // ---------- 룰 #6 False positive 가드 ----------

    @Test
    fun `룰 #6 false positive — 거래정지 종목 skip + audit CANDLE_BACKFILL_SKIPPED`() {
        val worker = newWorker()
        val haltInstrument =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns true
                every { it.liquidation } returns false
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(haltInstrument)
        setupAuditCapture()
        val captured = slot<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { captured.captured }

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY)

        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.SKIPPED)
        assertThat(captured.captured.action).isEqualTo("CANDLE_BACKFILL_SKIPPED")
        assertThat(captured.captured.detailJson).contains("TRADING_HALT")
        // KIS fetch 호출 0 (가드 통과 전)
        verify(exactly = 0) { kisRestPort.fetchCandles(any(), any(), any()) }
        // GapDetector 호출 0
        verify(exactly = 0) { candleGapDetector.detectGaps(any(), any(), any()) }
    }

    // ---------- 룰 #7 False negative 가드 ----------

    @Test
    fun `룰 #7 false negative — 갭 0건이면 SKIPPED + audit NO_GAPS`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns emptyList()
        val captured = mutableListOf<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { firstArg<ConsumerAuditTrailJpaEntity>() }

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.SKIPPED)
        assertThat(result.gapCount).isEqualTo(0)
        // audit START + SKIPPED 2건
        assertThat(captured.map { it.action }).contains("CANDLE_BACKFILL_START", "CANDLE_BACKFILL_SKIPPED")
        // SKIPPED detail에 NO_GAPS 명시
        val skipped = captured.first { it.action == "CANDLE_BACKFILL_SKIPPED" }
        assertThat(skipped.detailJson).contains("NO_GAPS")
    }

    // ---------- 룰 #8 Retry + KillSwitch ----------

    @Test
    fun `룰 #8 retry — KIS 3회 모두 실패 시 KillSwitch ON + audit FAIL`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns
            listOf(Instant.ofEpochSecond(1_700_000_000L))
        // KIS 3회 모두 빈 응답 (또는 throw)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns emptyList()
        every { slackSender.sendDirect(any()) } returns true
        every {
            killSwitchRepo.enableKillSwitch(
                userId = CandleBackfillWorker.SYSTEM_USER_ID,
                reason = any(),
                updatedBy = "chart-curator",
            )
        } just Runs

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.FAILED_KILLSWITCH)
        // 3회 retry 호출 (즉시·1s·5s)
        verify(exactly = 3) { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) }
        // KillSwitch ON
        verify(exactly = 1) {
            killSwitchRepo.enableKillSwitch(
                userId = CandleBackfillWorker.SYSTEM_USER_ID,
                reason = match { it!!.startsWith("CANDLE_BACKFILL_FAIL") },
                updatedBy = "chart-curator",
            )
        }
        // Slack notice
        verify { slackSender.sendDirect(match { it.contains("CandleBackfill 실패") }) }
    }

    @Test
    fun `룰 #8 retry — 부분 실패는 PARTIAL_FAIL + KillSwitch 미발동`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        // 2개 갭 중 1개만 KIS 응답 match
        val gap1 = Instant.ofEpochSecond(1_700_000_000L)
        val gap2 = Instant.ofEpochSecond(1_700_086_400L)
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns listOf(gap1, gap2)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns
            listOf(candle(tsEpochSec = 1_700_000_000L)) // gap1만 매칭
        every { candleRepository.saveAll(any()) } returns 1
        every { slackSender.sendDirect(any()) } returns true

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.PARTIAL_FAIL)
        assertThat(result.insertedCount).isEqualTo(1)
        assertThat(result.failedGapsCount).isEqualTo(1)
        // KillSwitch 미발동 (부분 실패 — 자본 영향 0 LOCK)
        verify(exactly = 0) {
            killSwitchRepo.enableKillSwitch(any(), any(), any())
        }
    }

    // ---------- 룰 #9 Audit trail (5 path) ----------

    @Test
    fun `룰 #9 audit — START + SUCCESS 2 path 발행`() {
        val worker = newWorker()
        setupNoInstrument()
        val captured = mutableListOf<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { firstArg<ConsumerAuditTrailJpaEntity>() }
        val gap = Instant.ofEpochSecond(1_700_000_000L)
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns listOf(gap)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns
            listOf(candle(tsEpochSec = 1_700_000_000L))
        every { candleRepository.saveAll(any()) } returns 1

        worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        val actions = captured.map { it.action }
        assertThat(actions).contains("CANDLE_BACKFILL_START", "CANDLE_BACKFILL_SUCCESS")
        // START detail에 backfillBatchId·gapCount·dryRun 명시
        val startAudit = captured.first { it.action == "CANDLE_BACKFILL_START" }
        assertThat(startAudit.detailJson).contains("backfillBatchId", "gapCount\":1", "dryRun\":false")
        // SUCCESS detail에 insertedCount·durationMs 명시
        val successAudit = captured.first { it.action == "CANDLE_BACKFILL_SUCCESS" }
        assertThat(successAudit.detailJson).contains("insertedCount\":1", "durationMs")
    }

    @Test
    fun `룰 #9 audit — actor=chart-curator entityType=candle entityId=code LOCK`() {
        val worker = newWorker()
        setupNoInstrument()
        val captured = slot<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { captured.captured }
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns emptyList()

        worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        assertThat(captured.captured.actor).isEqualTo("chart-curator")
        assertThat(captured.captured.entityType).isEqualTo("candle")
        assertThat(captured.captured.entityId).isEqualTo("004090")
    }
}
