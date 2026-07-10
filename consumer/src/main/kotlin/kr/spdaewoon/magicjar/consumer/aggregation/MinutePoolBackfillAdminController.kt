package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.config.MinutePoolBackfillProperties
import kr.spdaewoon.magicjar.consumer.ingestion.MinutePoolProvider
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 분봉 수집 풀 과거 백필 admin 트리거 (2026-07-10 · market-data · minute-pool).
 *
 * consumer 모듈 내부 REST (port 8082). 기존 [MarketDailyBackfillAdminController] 패턴 재사용.
 *
 * ## endpoint
 *   - POST /admin/minute-pool-backfill/run      — 후보 풀 × 최근 N영업일 1분봉 백필 비동기 시동(멱등 재개).
 *       즉시 runId(accepted) 반환 → 완주는 GET /status 로 관측. running 중 재호출은 ALREADY_RUNNING.
 *   - GET  /admin/minute-pool-backfill/status   — 진행/최근 결과(processed·inserted·rollup·failed·durationMs).
 *   - GET  /admin/minute-pool-backfill/pool     — 후보 풀 breakdown 관측(KIS 콜 0 · 적재 0 · 순수 조회).
 *
 * ## ★ 실행 예 (curl — 장 마감 후 실행 목표 · 머지·재배포 후)
 * ```
 * # 후보 풀 확인(적재 0)
 * curl 'http://localhost:8082/admin/minute-pool-backfill/pool'
 * # 백필 시동(비동기)
 * curl -X POST 'http://localhost:8082/admin/minute-pool-backfill/run'
 * # 진행/결과 관측 (완주까지 반복)
 * curl 'http://localhost:8082/admin/minute-pool-backfill/status'
 * ```
 *
 * ## ENV gate
 *   `magicjar.minute-pool-backfill.enabled` (MAGICJAR_MINUTE_POOL_BACKFILL_ENABLED)
 *     - true(default) → 백필 시동. 매 run 이 종목별 skip 판정으로 미완료만 처리(멱등).
 *     - false → run 호출해도 DISABLED 즉시 종료(가동 0 · 적재분 보존).
 *
 * ## rate-limit
 *   모든 KIS 콜 = BACKFILL(BG) 버킷 고정 — USER_PATH·INGESTION 침범 0. 자본 영향 0(데이터 적재만).
 */
@RestController
@RequestMapping("/admin/minute-pool-backfill")
class MinutePoolBackfillAdminController(
    private val campaign: MinutePoolBackfillCampaign,
    private val poolProvider: MinutePoolProvider,
    private val properties: MinutePoolBackfillProperties,
) {
    private val log = LoggerFactory.getLogger(MinutePoolBackfillAdminController::class.java)

    @PostMapping("/run")
    fun run(): ResponseEntity<RunResponse> {
        log.info("admin minute-pool-backfill run trigger: enabled={}", properties.enabled)
        val r = campaign.runAsync()
        return ResponseEntity.ok(toRunResponse(r))
    }

    @GetMapping("/status")
    fun status(): ResponseEntity<StatusResponse> {
        val v = campaign.statusView()
        return ResponseEntity.ok(
            StatusResponse(
                running = v.running,
                enabled = v.enabled,
                currentRunId = v.currentRunId,
                lastResult = v.lastResult?.let { toRunResponse(it) },
            ),
        )
    }

    @GetMapping("/pool")
    fun pool(): ResponseEntity<PoolResponse> {
        val b = poolProvider.resolveBreakdown()
        return ResponseEntity.ok(
            PoolResponse(
                enabled = properties.enabled,
                seedCount = b.seed,
                strategyCount = b.strategy,
                turnoverCount = b.turnover,
                scorecardCount = b.scorecard,
                unionBeforeClamp = b.unionBeforeClamp,
                poolSizeCap = properties.normalizedPoolSize(),
                poolCodes = b.finalPool,
                lookbackTradingDays = properties.normalizedLookbackTradingDays(),
            ),
        )
    }

    private fun toRunResponse(r: MinutePoolBackfillCampaign.RunResult): RunResponse =
        RunResponse(
            runId = r.runId,
            status = r.status.name,
            enabled = r.enabled,
            poolSize = r.poolSize,
            lookbackTradingDays = r.lookbackTradingDays,
            windowFrom = r.windowFrom?.toString(),
            windowTo = r.windowTo?.toString(),
            processedSymbols = r.processedSymbols,
            skippedAlreadyCovered = r.skippedAlreadyCovered,
            failedSymbols = r.failedSymbols,
            fetchedRows = r.fetchedRows,
            insertedRows = r.insertedRows,
            rollupUpserted = r.rollupUpserted,
            durationMs = r.durationMs,
        )

    data class RunResponse(
        val runId: String,
        val status: String,
        val enabled: Boolean,
        val poolSize: Int,
        val lookbackTradingDays: Int,
        val windowFrom: String?,
        val windowTo: String?,
        val processedSymbols: Int,
        val skippedAlreadyCovered: Int,
        val failedSymbols: Int,
        val fetchedRows: Long,
        val insertedRows: Long,
        val rollupUpserted: Long,
        val durationMs: Long,
    )

    data class StatusResponse(
        val running: Boolean,
        val enabled: Boolean,
        val currentRunId: String?,
        val lastResult: RunResponse?,
    )

    data class PoolResponse(
        val enabled: Boolean,
        val seedCount: Int,
        val strategyCount: Int,
        val turnoverCount: Int,
        val scorecardCount: Int,
        val unionBeforeClamp: Int,
        val poolSizeCap: Int,
        val poolCodes: List<String>,
        val lookbackTradingDays: Int,
    )
}
