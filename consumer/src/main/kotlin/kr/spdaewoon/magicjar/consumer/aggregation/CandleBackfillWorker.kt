package kr.spdaewoon.magicjar.consumer.aggregation

import jakarta.annotation.PreDestroy
import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleGapDetectorPort
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * CandleBackfillWorker — T-CC Phase 2-A (ADR 0086 §2-2 + 햇징 룰 10건 본체).
 *
 * 책임:
 *   - CandleGapDetectorPort 로 갭 list 획득 (영업일 캘린더 기반 — 햇징 룰 #6·#7)
 *   - 갭만 정밀 backfill (KIS REST endpoint 재호출 — KisRestPort baseline 100% 재사용)
 *   - 햇징 룰 10건 적용 — sanity check·dry-run·rollback·idempotency·source-of-truth·retry·audit·KillSwitch·운영자 우회
 *
 * KIS endpoint raw 출처 (KIS-first 의무 — 헌법 §3):
 *   - 일봉: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/
 *     /uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice (tr_id FHKST03010100)
 *   - 1분봉: /uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice (tr_id FHKST03010200)
 *   - Phase 1 baseline 100% 재사용 (KisRestPort.fetchCandles) — 신규 endpoint 호출 0 LOCK.
 *
 * 진입점:
 *   - admin: CandleBackfillAdminController.trigger() (POST /admin/candle-curator/backfill)
 *   - 자동: LastCandleAtMonitor (1 line edit 권장 — 자율 결정)
 *
 * 영역 (ADR 0085 + ADR 0086):
 *   - chart-curator 단일 owner
 *   - market-data·strategy-engine·frontend 0 touch
 *
 * 룰 5 위반 0: LLM provider import 0건 (ADR 0023 정합).
 * 자본 영향 0 — 데이터 정합 보강만 + 주문 발행 0건.
 */
@Component
class CandleBackfillWorker(
    private val candleGapDetector: CandleGapDetectorPort,
    private val candleRepository: CandleRepository,
    private val kisRestPort: KisRestPort,
    private val instrumentJpaRepository: ConsumerInstrumentJpaRepository,
    private val auditRepository: ConsumerAuditTrailJpaRepository,
    private val slackSenderProvider: ObjectProvider<SlackWebhookSender>,
    private val killSwitchJpaRepositoryProvider: ObjectProvider<KillSwitchJpaRepository>,
    @Value("\${magicjar.chart-curator.backfill.dry-run:false}")
    private val dryRunPropertyDefault: Boolean,
    @Value("\${magicjar.chart-curator.backfill.window-days:30}")
    private val windowDaysDefault: Int,
) {
    private val log = LoggerFactory.getLogger(CandleBackfillWorker::class.java)

    companion object {
        /** 햇징 룰 #1 가격 sanity 상한 — KOSPI 단위 보호 (1e9 = 10억 KRW) */
        val PRICE_MAX: BigDecimal = BigDecimal("1000000000")
        val PRICE_MIN: BigDecimal = BigDecimal.ZERO

        /** 햇징 룰 #8 — 재시도 backoff (ms): 즉시·1s·5s 3회 */
        val RETRY_BACKOFF_MS: List<Long> = listOf(0L, 1_000L, 5_000L)

        /** 전체 실패 KillSwitch 임계치 — 갭 100% 실패 시 발동 (자본 보호 우선) */
        const val KILLSWITCH_FAILURE_RATIO = 1.0

        /** KillSwitch system user id (Phase 2-A 1차 MVP — user별 KillSwitch는 ADR 0084 P0 baseline 정합) */
        const val SYSTEM_USER_ID = "system"

        /** thread pool — backfill submit 비동기 */
        private const val POOL_SIZE = 2

        /** 호출자가 expected/dryRun 인자 명시 — 룰 #5 source-of-truth */
        private const val SOURCE_KIS_BACKFILL = "KIS-BACKFILL"
    }

    private val threadCounter = AtomicInteger(0)
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(
            POOL_SIZE,
            ThreadFactory { r ->
                Thread(r, "candle-backfill-${threadCounter.incrementAndGet()}").also {
                    it.isDaemon = true
                }
            },
        )

    data class BackfillResult(
        val code: String,
        val interval: CandleInterval,
        val backfillBatchId: String,
        val dryRun: Boolean,
        val gapCount: Int,
        val gapsSample: List<Instant>,
        val insertedCount: Int,
        val failedGapsCount: Int,
        val durationMs: Long,
        val status: BackfillStatus,
    )

    enum class BackfillStatus {
        SUCCESS, // 모든 갭 성공
        SKIPPED, // 거래정지·정리매매·갭 0건
        PARTIAL_FAIL, // 일부 갭 실패 (warn + Slack notice)
        FAILED_KILLSWITCH, // 전체 갭 실패 (KillSwitch on)
    }

    /**
     * 비동기 backfill 제출 — LastCandleAtMonitor 등 자동 진입점 용도.
     */
    fun submit(
        code: String,
        interval: CandleInterval = CandleInterval.ONE_DAY,
        reason: String = "auto",
    ) {
        executor.submit {
            try {
                runBackfill(code, interval, dryRun = dryRunPropertyDefault, windowDays = windowDaysDefault, reason = reason)
            } catch (t: Throwable) {
                log.warn(
                    "CandleBackfillWorker 비동기 실행 예외: code={} interval={} err={}",
                    code,
                    interval.code,
                    t.message,
                )
            }
        }
    }

    /**
     * 동기 backfill 실행 — admin endpoint 용도 (호출자 응답 대기).
     *
     * 흐름 (햇징 룰 10건 본문):
     *   1. backfill_batch_id 발행 + audit CANDLE_BACKFILL_START 발행 (룰 #3·#9)
     *   2. 거래정지·정리매매 skip (룰 #6) → audit CANDLE_BACKFILL_SKIPPED
     *   3. CandleGapDetector.detectGaps() (룰 #6·#7 가드 내장)
     *   4. dryRun=true → audit + 응답만 반환 (룰 #2)
     *   5. 각 갭별 KIS fetch + sanity check (룰 #1) + 3회 retry (룰 #8) + UPSERT (룰 #4)
     *   6. 결과 집계 → audit SUCCESS/PARTIAL_FAIL/FAIL (룰 #9) + KillSwitch (룰 #8)
     */
    fun runBackfill(
        code: String,
        interval: CandleInterval,
        dryRun: Boolean = dryRunPropertyDefault,
        windowDays: Int = windowDaysDefault,
        reason: String = "manual",
    ): BackfillResult {
        val startMs = System.currentTimeMillis()
        val backfillBatchId =
            "candle-backfill:$code:${interval.code}:" +
                "$startMs-${UUID.randomUUID()}"

        log.info(
            "CandleBackfillWorker start: code={} interval={} dryRun={} reason={} batchId={}",
            code,
            interval.code,
            dryRun,
            reason,
            backfillBatchId,
        )

        // 룰 #6 — 거래정지·정리매매 skip (룰 false positive 가드)
        val instrument = instrumentJpaRepository.findById(code).orElse(null)
        if (instrument != null && (instrument.tradingHalt || instrument.liquidation)) {
            persistAudit(
                action = "CANDLE_BACKFILL_SKIPPED",
                entityId = code,
                detail =
                    """{"code":"$code","interval":"${interval.code}","reason":"TRADING_HALT",""" +
                        """"tradingHalt":${instrument.tradingHalt},"liquidation":${instrument.liquidation}}""",
            )
            return BackfillResult(
                code = code,
                interval = interval,
                backfillBatchId = backfillBatchId,
                dryRun = dryRun,
                gapCount = 0,
                gapsSample = emptyList(),
                insertedCount = 0,
                failedGapsCount = 0,
                durationMs = System.currentTimeMillis() - startMs,
                status = BackfillStatus.SKIPPED,
            )
        }

        // CandleGapDetector — 룰 #6·#7 false positive·negative 가드 내장
        val gaps =
            try {
                candleGapDetector.detectGaps(code, interval, windowDays)
            } catch (e: Exception) {
                log.warn(
                    "CandleBackfillWorker detectGaps 예외: code={} interval={} err={}",
                    code,
                    interval.code,
                    e.message,
                )
                emptyList()
            }

        // 룰 #3 — audit CANDLE_BACKFILL_START (gapCount·dryRun 포함)
        persistAudit(
            action = "CANDLE_BACKFILL_START",
            entityId = code,
            detail =
                """{"code":"$code","interval":"${interval.code}","backfillBatchId":"$backfillBatchId",""" +
                    """"gapCount":${gaps.size},"dryRun":$dryRun,"reason":"$reason"}""",
        )

        if (gaps.isEmpty()) {
            persistAudit(
                action = "CANDLE_BACKFILL_SKIPPED",
                entityId = code,
                detail = """{"code":"$code","reason":"NO_GAPS","backfillBatchId":"$backfillBatchId"}""",
            )
            return BackfillResult(
                code = code,
                interval = interval,
                backfillBatchId = backfillBatchId,
                dryRun = dryRun,
                gapCount = 0,
                gapsSample = emptyList(),
                insertedCount = 0,
                failedGapsCount = 0,
                durationMs = System.currentTimeMillis() - startMs,
                status = BackfillStatus.SKIPPED,
            )
        }

        val gapsSample = gaps.take(10) // 응답 한도 보호

        // 룰 #2 — dry-run 모드 (DB 적재 0)
        if (dryRun) {
            log.info(
                "CandleBackfillWorker DRY-RUN: code={} interval={} gapCount={} batchId={}",
                code,
                interval.code,
                gaps.size,
                backfillBatchId,
            )
            persistAudit(
                action = "CANDLE_BACKFILL_SUCCESS",
                entityId = code,
                detail =
                    """{"code":"$code","backfillBatchId":"$backfillBatchId","insertedCount":0,""" +
                        """"dryRun":true,"gapCount":${gaps.size},"durationMs":${System.currentTimeMillis() - startMs}}""",
            )
            return BackfillResult(
                code = code,
                interval = interval,
                backfillBatchId = backfillBatchId,
                dryRun = true,
                gapCount = gaps.size,
                gapsSample = gapsSample,
                insertedCount = 0,
                failedGapsCount = 0,
                durationMs = System.currentTimeMillis() - startMs,
                status = BackfillStatus.SUCCESS,
            )
        }

        // 실 backfill — 갭별 KIS fetch + sanity + retry + UPSERT
        var insertedTotal = 0
        var failedGapsCount = 0
        val failedGapsSample = mutableListOf<Instant>()

        // KIS fetch는 갭별이 아닌 전체 캔들 일괄 fetch + filter 방식이 효율
        // (Phase 1 cold-start 패턴 정합 — fetchCandles 전체 응답 후 gaps 매칭)
        // 사유: KIS endpoint는 페이지네이션 100건 한도 → 갭별 1:1 호출은 비효율
        val fetchCount =
            when (interval) {
                CandleInterval.ONE_DAY -> 200 // Phase 1 ColdStart baseline
                CandleInterval.ONE_MIN -> 1 // KisRestCandleAdapter 내부 30일 페이지네이션 (count 무시)
                else -> 100
            }

        val fetchedCandles: List<Candle> =
            try {
                fetchWithRetry(code, interval, fetchCount, backfillBatchId)
            } catch (e: Exception) {
                log.warn(
                    "CandleBackfillWorker KIS fetch 3회 실패: code={} interval={} err={}",
                    code,
                    interval.code,
                    e.message,
                )
                emptyList()
            }

        if (fetchedCandles.isEmpty()) {
            // 전체 실패 → KillSwitch + audit FAIL (룰 #8)
            failedGapsCount = gaps.size
            failedGapsSample.addAll(gapsSample)
            handleFailure(
                code = code,
                interval = interval,
                backfillBatchId = backfillBatchId,
                gapsTotal = gaps.size,
                failedGapsCount = failedGapsCount,
                failedGapsSample = failedGapsSample,
                durationMs = System.currentTimeMillis() - startMs,
                fatal = true,
            )
            return BackfillResult(
                code = code,
                interval = interval,
                backfillBatchId = backfillBatchId,
                dryRun = false,
                gapCount = gaps.size,
                gapsSample = gapsSample,
                insertedCount = 0,
                failedGapsCount = failedGapsCount,
                durationMs = System.currentTimeMillis() - startMs,
                status = BackfillStatus.FAILED_KILLSWITCH,
            )
        }

        // 룰 #1 — 이중 검증 sanity check
        val sane = sanityCheck(fetchedCandles, backfillBatchId)
        val gapSet: Set<Instant> = HashSet(gaps.map { it })
        val matched =
            sane.filter { candle ->
                // candle.ts (kotlinx) → java.time.Instant
                val ts = Instant.ofEpochSecond(candle.ts.epochSeconds)
                ts in gapSet
            }

        if (matched.isEmpty()) {
            log.warn(
                "CandleBackfillWorker — KIS fetched but no gaps matched: code={} interval={} fetched={} gaps={}",
                code,
                interval.code,
                sane.size,
                gaps.size,
            )
        }

        // 룰 #4 — UPSERT (ON CONFLICT DO NOTHING — Idempotency LOCK)
        try {
            insertedTotal =
                candleRepository.saveAll(
                    matched.map { candle ->
                        // 룰 #5 — source-of-truth: KIS raw 그대로 + backfill_batch_id 추가 위해 source 필드 LOCK
                        // backfill_batch_id 컬럼 적재는 Phase 2-B (Flyway V39 add-only 상태) — 1차 MVP는 source 그대로
                        candle.copy(source = SOURCE_KIS_BACKFILL)
                    },
                )
        } catch (e: Exception) {
            log.warn(
                "CandleBackfillWorker saveAll 실패: code={} interval={} err={}",
                code,
                interval.code,
                e.message,
            )
            failedGapsCount = gaps.size
        }

        // 매칭되지 않은 갭은 실패로 집계
        val matchedTsSet: Set<Instant> = HashSet(matched.map { Instant.ofEpochSecond(it.ts.epochSeconds) })
        val unmatchedGaps = gaps.filterNot { it in matchedTsSet }
        failedGapsCount += unmatchedGaps.size
        unmatchedGaps.take(10 - failedGapsSample.size).forEach { failedGapsSample.add(it) }

        val durationMs = System.currentTimeMillis() - startMs
        val failureRatio = if (gaps.isNotEmpty()) failedGapsCount.toDouble() / gaps.size.toDouble() else 0.0
        val status =
            when {
                failureRatio >= KILLSWITCH_FAILURE_RATIO -> BackfillStatus.FAILED_KILLSWITCH
                failedGapsCount > 0 -> BackfillStatus.PARTIAL_FAIL
                else -> BackfillStatus.SUCCESS
            }

        when (status) {
            BackfillStatus.SUCCESS ->
                persistAudit(
                    action = "CANDLE_BACKFILL_SUCCESS",
                    entityId = code,
                    detail =
                        """{"code":"$code","backfillBatchId":"$backfillBatchId","insertedCount":$insertedTotal,""" +
                            """"durationMs":$durationMs}""",
                )
            BackfillStatus.PARTIAL_FAIL ->
                handleFailure(
                    code = code,
                    interval = interval,
                    backfillBatchId = backfillBatchId,
                    gapsTotal = gaps.size,
                    failedGapsCount = failedGapsCount,
                    failedGapsSample = failedGapsSample,
                    durationMs = durationMs,
                    fatal = false,
                )
            BackfillStatus.FAILED_KILLSWITCH ->
                handleFailure(
                    code = code,
                    interval = interval,
                    backfillBatchId = backfillBatchId,
                    gapsTotal = gaps.size,
                    failedGapsCount = failedGapsCount,
                    failedGapsSample = failedGapsSample,
                    durationMs = durationMs,
                    fatal = true,
                )
            BackfillStatus.SKIPPED -> { /* unreachable in this branch */ }
        }

        log.info(
            "CandleBackfillWorker done: code={} interval={} status={} gaps={} inserted={} failed={} durationMs={} batchId={}",
            code,
            interval.code,
            status,
            gaps.size,
            insertedTotal,
            failedGapsCount,
            durationMs,
            backfillBatchId,
        )

        return BackfillResult(
            code = code,
            interval = interval,
            backfillBatchId = backfillBatchId,
            dryRun = false,
            gapCount = gaps.size,
            gapsSample = gapsSample,
            insertedCount = insertedTotal,
            failedGapsCount = failedGapsCount,
            durationMs = durationMs,
            status = status,
        )
    }

    /**
     * 햇징 룰 #1 — 이중 검증 (Double Verification):
     *   - 가격 range: 0 < open·high·low·close ≤ 1e9
     *   - 거래량 비음수: volume >= 0
     *   - time monotonic: 응답 list 내 ts 오름차순
     *   - OHLC 정합성: high >= max(open, close) ∧ low <= min(open, close) ∧ low <= high
     *
     * 위반 시 적재 0 + warn log (audit는 호출자 집계 단계에서 발행).
     */
    fun sanityCheck(
        candles: List<Candle>,
        backfillBatchId: String,
    ): List<Candle> {
        if (candles.isEmpty()) return emptyList()
        val valid = mutableListOf<Candle>()
        var prevTsSec: Long = Long.MIN_VALUE
        var monotonicViolation = false
        for ((idx, c) in candles.withIndex()) {
            // 가격 range
            if (c.openRaw <= PRICE_MIN ||
                c.openRaw > PRICE_MAX ||
                c.highRaw <= PRICE_MIN ||
                c.highRaw > PRICE_MAX ||
                c.lowRaw <= PRICE_MIN ||
                c.lowRaw > PRICE_MAX ||
                c.closeRaw <= PRICE_MIN ||
                c.closeRaw > PRICE_MAX
            ) {
                log.warn(
                    "CandleBackfillWorker sanity 실패 가격 range: code={} ts={} O={} H={} L={} C={} batchId={}",
                    c.code,
                    c.ts,
                    c.openRaw,
                    c.highRaw,
                    c.lowRaw,
                    c.closeRaw,
                    backfillBatchId,
                )
                continue
            }
            // 거래량 비음수
            if (c.volume < 0) {
                log.warn(
                    "CandleBackfillWorker sanity 실패 volume 음수: code={} ts={} volume={} batchId={}",
                    c.code,
                    c.ts,
                    c.volume,
                    backfillBatchId,
                )
                continue
            }
            // OHLC 정합성
            val maxOC = if (c.openRaw > c.closeRaw) c.openRaw else c.closeRaw
            val minOC = if (c.openRaw < c.closeRaw) c.openRaw else c.closeRaw
            if (c.highRaw < maxOC || c.lowRaw > minOC || c.lowRaw > c.highRaw) {
                log.warn(
                    "CandleBackfillWorker sanity 실패 OHLC: code={} ts={} O={} H={} L={} C={} batchId={}",
                    c.code,
                    c.ts,
                    c.openRaw,
                    c.highRaw,
                    c.lowRaw,
                    c.closeRaw,
                    backfillBatchId,
                )
                continue
            }
            // time monotonic — index 1 이상에서 prevTs와 비교 (등호 허용 X — 엄격)
            val tsSec = c.ts.epochSeconds
            if (idx > 0 && tsSec <= prevTsSec) {
                monotonicViolation = true
                // 위반 시 해당 candle skip (이전 candle은 이미 valid 추가됨 — 1차 MVP 보존)
                log.warn(
                    "CandleBackfillWorker sanity 실패 monotonic: code={} idx={} ts={} prev={} batchId={}",
                    c.code,
                    idx,
                    c.ts,
                    prevTsSec,
                    backfillBatchId,
                )
                continue
            }
            prevTsSec = tsSec
            valid.add(c)
        }
        if (monotonicViolation) {
            log.warn(
                "CandleBackfillWorker — monotonic violation 감지 (KIS 응답 정렬 결함 의심): total={} valid={} batchId={}",
                candles.size,
                valid.size,
                backfillBatchId,
            )
        }
        return valid
    }

    /**
     * 햇징 룰 #8 — 3회 backoff (즉시·1s·5s) KIS fetch retry.
     * 3회 모두 실패 시 IllegalStateException throw — 호출자가 KillSwitch 발동 판단.
     */
    private fun fetchWithRetry(
        code: String,
        interval: CandleInterval,
        count: Int,
        backfillBatchId: String,
    ): List<Candle> {
        var lastErr: Throwable? = null
        for ((attempt, backoffMs) in RETRY_BACKOFF_MS.withIndex()) {
            if (backoffMs > 0) {
                try {
                    Thread.sleep(backoffMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("CandleBackfillWorker interrupted", e)
                }
            }
            try {
                val candles = kisRestPort.fetchCandles(code, interval, count)
                if (candles.isNotEmpty()) {
                    log.debug(
                        "CandleBackfillWorker fetch 성공: code={} interval={} attempt={} count={} batchId={}",
                        code,
                        interval.code,
                        attempt + 1,
                        candles.size,
                        backfillBatchId,
                    )
                    return candles
                }
                log.warn(
                    "CandleBackfillWorker fetch 빈 응답 attempt={}: code={} interval={} batchId={}",
                    attempt + 1,
                    code,
                    interval.code,
                    backfillBatchId,
                )
            } catch (e: Exception) {
                lastErr = e
                log.warn(
                    "CandleBackfillWorker fetch 실패 attempt={}: code={} interval={} err={} batchId={}",
                    attempt + 1,
                    code,
                    interval.code,
                    e.message,
                    backfillBatchId,
                )
            }
        }
        throw IllegalStateException(
            "CandleBackfillWorker 3회 retry 실패: code=$code interval=${interval.code} batchId=$backfillBatchId",
            lastErr,
        )
    }

    /**
     * 햇징 룰 #8·#9 — 실패 audit + Slack notice + KillSwitch (전체 실패 시).
     */
    private fun handleFailure(
        code: String,
        interval: CandleInterval,
        backfillBatchId: String,
        gapsTotal: Int,
        failedGapsCount: Int,
        failedGapsSample: List<Instant>,
        durationMs: Long,
        fatal: Boolean,
    ) {
        val sampleStr = failedGapsSample.take(5).joinToString(",") { it.toString() }
        val detail =
            """{"code":"$code","interval":"${interval.code}","backfillBatchId":"$backfillBatchId",""" +
                """"gapsTotal":$gapsTotal,"failedGapsCount":$failedGapsCount,"failedGapsSample":[$sampleStr],""" +
                """"durationMs":$durationMs,"fatal":$fatal}"""
        persistAudit(action = "CANDLE_BACKFILL_FAIL", entityId = code, detail = detail)

        // Slack notice
        val sender = slackSenderProvider.getIfAvailable()
        if (sender != null) {
            val msg =
                "[magicJar] CandleBackfill 실패 — code=$code interval=${interval.code} " +
                    "failed=$failedGapsCount/$gapsTotal fatal=$fatal batchId=$backfillBatchId"
            try {
                sender.sendDirect(msg)
            } catch (e: Exception) {
                log.warn("CandleBackfillWorker Slack 발화 실패: err={}", e.message)
            }
        }

        // KillSwitch — 전체 실패(fatal=true)만 발동 (자본 보호 우선)
        if (fatal) {
            val ksRepo = killSwitchJpaRepositoryProvider.getIfAvailable()
            if (ksRepo != null) {
                try {
                    ksRepo.enableKillSwitch(
                        userId = SYSTEM_USER_ID,
                        reason = "CANDLE_BACKFILL_FAIL:$code:${interval.code}",
                        updatedBy = "chart-curator",
                    )
                    log.warn(
                        "CandleBackfillWorker — KillSwitch ON: userId={} reason={} batchId={}",
                        SYSTEM_USER_ID,
                        "CANDLE_BACKFILL_FAIL:$code:${interval.code}",
                        backfillBatchId,
                    )
                } catch (e: Exception) {
                    log.warn(
                        "CandleBackfillWorker KillSwitch enable 실패: err={} batchId={}",
                        e.message,
                        backfillBatchId,
                    )
                }
            }
        }
    }

    /**
     * 햇징 룰 #9 — audit_trail row 1건 발행.
     * action: CANDLE_BACKFILL_START / SUCCESS / FAIL / SKIPPED / ROLLBACK 5 path
     */
    private fun persistAudit(
        action: String,
        entityId: String,
        detail: String,
    ) {
        try {
            auditRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "chart-curator",
                    action = action,
                    entityType = "candle",
                    entityId = entityId,
                    detailJson = detail,
                ),
            )
        } catch (e: Exception) {
            log.warn("CandleBackfillWorker audit 저장 실패: action={} err={}", action, e.message)
        }
    }

    /**
     * 햇징 룰 #10 — 운영자 수동 rollback (audit only).
     * 실제 DELETE는 admin SQL 또는 향후 admin endpoint 확장 (1차 MVP는 audit row만).
     */
    fun recordRollback(
        backfillBatchId: String,
        deletedCount: Int,
        reason: String,
    ) {
        persistAudit(
            action = "CANDLE_BACKFILL_ROLLBACK",
            entityId = backfillBatchId,
            detail =
                """{"backfillBatchId":"$backfillBatchId","deletedCount":$deletedCount,"reason":"$reason"}""",
        )
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }
}
