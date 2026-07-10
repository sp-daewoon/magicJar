package kr.spdaewoon.magicjar.consumer.aggregation

import jakarta.annotation.PreDestroy
import kr.spdaewoon.magicjar.consumer.config.MinutePoolBackfillProperties
import kr.spdaewoon.magicjar.consumer.ingestion.MinutePoolCollectionService
import kr.spdaewoon.magicjar.consumer.ingestion.MinutePoolProvider
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 분봉 수집 풀 — 후보 풀 × 최근 N영업일 1분봉 과거 백필 캠페인 (2026-07-10 · market-data · minute-pool).
 *
 * ## 책임 (한 PR = 한 영역 · market-data)
 *   1. [MinutePoolProvider.resolvePool] 후보 풀 산출(거래대금 상위·발굴 후보·전략·seed union).
 *   2. 종목별 이미 보유 1분봉 count 로 skip 판정(멱등 재개 — [MinutePoolBackfillProperties.coverageRatio]).
 *   3. 미완료 종목당 [MinutePoolCollectionService.collect](BACKFILL 버킷) 1회 — 최근 N영업일 09:00~now range.
 *   4. saveAll(DO NOTHING) + 기존 롤업 재사용(5m/15m 등). per-code try-catch(실패는 카운터 후 다음 종목).
 *
 * ## 비동기 실행 (curl 타임아웃 회피 — [MarketDailyBackfillAdminController.repairDate] 선례)
 *   [runAsync]는 즉시 runId(accepted) 반환 후 단일 daemon 스레드에서 완주한다. 진행/결과는 [statusView].
 *   동시 실행 방지 — running 중 재호출은 accepted=false + 현재 runId 반환.
 *
 * ## rate-limit 예산 (ADR 0116 BG 버킷 capacity 3/초)
 *   종목당 1 method 콜이 내부적으로 N일 × ~4 page(정규장 390분/120봉) 페이지네이션.
 *   30종목 × 30일 × ~4 page ≈ 3,600 KIS 콜 → BG 3/초 pacing ≈ 20분 (장 마감 후 실행 목표 — 자본 path 침범 0).
 *
 * 자본 영향 0 — 데이터 적재만(주문 0). LLM import 0 (룰 5). 외부 fallback 0 (KIS-only). mock 보간 0.
 */
@Component
class MinutePoolBackfillCampaign(
    private val poolProvider: MinutePoolProvider,
    private val collectionService: MinutePoolCollectionService,
    private val candleRepository: CandleRepository,
    private val properties: MinutePoolBackfillProperties,
) {
    private val log = LoggerFactory.getLogger(MinutePoolBackfillCampaign::class.java)

    private companion object {
        val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        val SESSION_OPEN: LocalTime = LocalTime.of(9, 0)
    }

    enum class RunStatus {
        ACCEPTED,
        DISABLED,
        ALREADY_RUNNING,
        COMPLETED,
        FAILED,
    }

    data class RunResult(
        val runId: String,
        val status: RunStatus,
        val enabled: Boolean,
        val poolSize: Int,
        val lookbackTradingDays: Int,
        val windowFrom: Instant?,
        val windowTo: Instant?,
        val processedSymbols: Int,
        val skippedAlreadyCovered: Int,
        val failedSymbols: Int,
        val fetchedRows: Long,
        val insertedRows: Long,
        val rollupUpserted: Long,
        val durationMs: Long,
    )

    data class StatusView(
        val running: Boolean,
        val enabled: Boolean,
        val currentRunId: String?,
        val lastResult: RunResult?,
    )

    private val running = AtomicBoolean(false)
    private val currentRunId = AtomicReference<String?>(null)
    private val lastResult = AtomicReference<RunResult?>(null)
    private val executor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "minute-pool-backfill").also { it.isDaemon = true }
        }

    /**
     * 백필 캠페인 비동기 시동. running 중이면 ALREADY_RUNNING(accepted=false) 즉시 반환.
     * enabled=false 면 DISABLED 즉시 반환(가동 0·적재분 보존).
     */
    fun runAsync(): RunResult {
        if (!properties.enabled) {
            log.info("minute-pool 백필 trigger — DISABLED(enabled=false), 가동 0")
            return disabledResult()
        }
        if (!running.compareAndSet(false, true)) {
            val runId = currentRunId.get()
            log.info("minute-pool 백필 trigger — 이미 실행 중(runId={}) skip", runId)
            return RunResult(
                runId = runId ?: "unknown",
                status = RunStatus.ALREADY_RUNNING,
                enabled = true,
                poolSize = 0,
                lookbackTradingDays = properties.normalizedLookbackTradingDays(),
                windowFrom = null,
                windowTo = null,
                processedSymbols = 0,
                skippedAlreadyCovered = 0,
                failedSymbols = 0,
                fetchedRows = 0,
                insertedRows = 0,
                rollupUpserted = 0,
                durationMs = 0,
            )
        }
        val runId = "min-pool-bf:${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        currentRunId.set(runId)
        executor.submit {
            try {
                val r = execute(runId)
                lastResult.set(r)
            } catch (e: Exception) {
                log.error("minute-pool 백필 실행 중 예외: runId={} err={}", runId, e.message, e)
                lastResult.set(failedResult(runId))
            } finally {
                running.set(false)
                currentRunId.set(null)
            }
        }
        return RunResult(
            runId = runId,
            status = RunStatus.ACCEPTED,
            enabled = true,
            poolSize = 0,
            lookbackTradingDays = properties.normalizedLookbackTradingDays(),
            windowFrom = null,
            windowTo = null,
            processedSymbols = 0,
            skippedAlreadyCovered = 0,
            failedSymbols = 0,
            fetchedRows = 0,
            insertedRows = 0,
            rollupUpserted = 0,
            durationMs = 0,
        )
    }

    fun statusView(): StatusView =
        StatusView(
            running = running.get(),
            enabled = properties.enabled,
            currentRunId = currentRunId.get(),
            lastResult = lastResult.get(),
        )

    private fun execute(runId: String): RunResult {
        val startMs = System.currentTimeMillis()
        val pool = poolProvider.resolvePool().take(properties.maxCodesPerRun.coerceAtLeast(1))
        val to = ZonedDateTime.now(KST_ZONE).truncatedTo(ChronoUnit.MINUTES).toInstant()
        val from = lookbackStartDate().atTime(SESSION_OPEN).atZone(KST_ZONE).toInstant()
        val skipThreshold = (properties.expectedOneMinBars() * properties.normalizedCoverageRatio()).toLong()

        log.info(
            "minute-pool 백필 시작: runId={} pool={} window={}..{} lookbackDays={} skipThreshold={}봉",
            runId,
            pool.size,
            from,
            to,
            properties.normalizedLookbackTradingDays(),
            skipThreshold,
        )

        var processed = 0
        var skipped = 0
        var failed = 0
        var fetchedRows = 0L
        var insertedRows = 0L
        var rollupUpserted = 0L

        for ((index, code) in pool.withIndex()) {
            val existing =
                runCatching { candleRepository.countByCodeAndInterval(code, CandleInterval.ONE_MIN) }
                    .getOrDefault(0L)
            if (existing >= skipThreshold && skipThreshold > 0) {
                skipped++
                log.info(
                    "minute-pool 백필 skip(이미 보유): runId={} [{}/{}] code={} existing1m={} ≥ threshold={}",
                    runId,
                    index + 1,
                    pool.size,
                    code,
                    existing,
                    skipThreshold,
                )
                continue
            }
            try {
                val outcome = collectionService.collect(code, from, to)
                processed++
                fetchedRows += outcome.fetched
                insertedRows += outcome.inserted
                rollupUpserted += outcome.rollupUpserted
                log.info(
                    "minute-pool 백필 진행 [{}/{}]: runId={} code={} fetched={} inserted={} rollup={} (누적 inserted={})",
                    index + 1,
                    pool.size,
                    runId,
                    code,
                    outcome.fetched,
                    outcome.inserted,
                    outcome.rollupUpserted,
                    insertedRows,
                )
            } catch (e: Exception) {
                failed++
                log.warn(
                    "minute-pool 백필 실패 [{}/{}]: runId={} code={} err={} (다음 종목 진행)",
                    index + 1,
                    pool.size,
                    runId,
                    code,
                    e.message,
                )
            }
            if (properties.perCodeDelayMs > 0 && index < pool.lastIndex) {
                try {
                    Thread.sleep(properties.perCodeDelayMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        val durationMs = System.currentTimeMillis() - startMs
        val result =
            RunResult(
                runId = runId,
                status = RunStatus.COMPLETED,
                enabled = true,
                poolSize = pool.size,
                lookbackTradingDays = properties.normalizedLookbackTradingDays(),
                windowFrom = from,
                windowTo = to,
                processedSymbols = processed,
                skippedAlreadyCovered = skipped,
                failedSymbols = failed,
                fetchedRows = fetchedRows,
                insertedRows = insertedRows,
                rollupUpserted = rollupUpserted,
                durationMs = durationMs,
            )
        log.info(
            "minute-pool 백필 완료: runId={} pool={} processed={} skipped={} failed={} inserted={} rollup={} {}ms",
            runId,
            pool.size,
            processed,
            skipped,
            failed,
            insertedRows,
            rollupUpserted,
            durationMs,
        )
        return result
    }

    /** 최근 N영업일 시작일 — 주말 제외 카운트백(공휴일은 KIS 응답 0건으로 방어). */
    private fun lookbackStartDate(): LocalDate {
        val days = properties.normalizedLookbackTradingDays()
        var d = LocalDate.now(KST_ZONE)
        var counted = 0
        // 오늘 포함 count — 오늘이 영업일이면 1일차.
        while (counted < days) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) {
                counted++
                if (counted >= days) break
            }
            d = d.minusDays(1)
        }
        return d
    }

    private fun disabledResult(): RunResult =
        RunResult(
            runId = "disabled",
            status = RunStatus.DISABLED,
            enabled = false,
            poolSize = 0,
            lookbackTradingDays = properties.normalizedLookbackTradingDays(),
            windowFrom = null,
            windowTo = null,
            processedSymbols = 0,
            skippedAlreadyCovered = 0,
            failedSymbols = 0,
            fetchedRows = 0,
            insertedRows = 0,
            rollupUpserted = 0,
            durationMs = 0,
        )

    private fun failedResult(runId: String): RunResult =
        RunResult(
            runId = runId,
            status = RunStatus.FAILED,
            enabled = properties.enabled,
            poolSize = 0,
            lookbackTradingDays = properties.normalizedLookbackTradingDays(),
            windowFrom = null,
            windowTo = null,
            processedSymbols = 0,
            skippedAlreadyCovered = 0,
            failedSymbols = 0,
            fetchedRows = 0,
            insertedRows = 0,
            rollupUpserted = 0,
            durationMs = 0,
        )

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
