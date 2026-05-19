package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * CandleBackfillWorker 운영자 수동 trigger admin REST endpoint (ADR 0086 §2-1 룰 #10).
 *
 * consumer 모듈 내부 REST (port 8082). 운영 편의 + dogfooding cycle 즉시 발화 + 사전 검증 path.
 *
 * endpoint:
 *   POST /admin/candle-curator/backfill?code={code}&interval={1d|1m}&dry-run={true|false}&window-days={n}
 *
 * 응답 schema (BackfillResponse):
 *   - code: 종목 코드
 *   - interval: 1d / 1m
 *   - dryRun: dry-run 모드 여부
 *   - gapCount: 검출된 갭 수
 *   - gapsSample: 갭 ts list (top 10)
 *   - backfillBatchId: 추적 ID (룰 #3 rollback baseline)
 *   - insertedCount: 실 적재된 row 수 (dryRun=true 시 0)
 *   - failedGapsCount: KIS fetch 실패한 갭 수
 *   - durationMs: 소요 ms
 *   - status: SUCCESS / SKIPPED / PARTIAL_FAIL / FAILED_KILLSWITCH
 *
 * 자본 영향 0 — 데이터 정합 보강만 수행.
 * dry-run path는 KIS fetch만 + DB 변경 0건 (룰 #2 LOCK).
 */
@RestController
@RequestMapping("/admin/candle-curator")
class CandleBackfillAdminController(
    private val candleBackfillWorker: CandleBackfillWorker,
) {
    private val log = LoggerFactory.getLogger(CandleBackfillAdminController::class.java)

    @PostMapping("/backfill")
    fun trigger(
        @RequestParam("code") code: String,
        @RequestParam("interval") interval: String,
        @RequestParam(value = "dry-run", required = false, defaultValue = "false") dryRun: Boolean,
        @RequestParam(value = "window-days", required = false, defaultValue = "30") windowDays: Int,
    ): ResponseEntity<BackfillResponse> {
        log.info(
            "admin candle backfill trigger: code={} interval={} dryRun={} windowDays={}",
            code,
            interval,
            dryRun,
            windowDays,
        )

        val candleInterval =
            try {
                CandleInterval.fromCode(interval)
            } catch (e: IllegalArgumentException) {
                return ResponseEntity.badRequest().body(
                    BackfillResponse(
                        code = code,
                        interval = interval,
                        dryRun = dryRun,
                        gapCount = 0,
                        gapsSample = emptyList(),
                        backfillBatchId = "",
                        insertedCount = 0,
                        failedGapsCount = 0,
                        durationMs = 0L,
                        status = "BAD_REQUEST",
                        error = "Unknown interval: $interval (expected: 1d / 1m)",
                    ),
                )
            }

        // Phase 2-A LOCK — 1d / 1m 만 지원 (5m·15m·30m·90m·1h는 AggregateBackfillRunner 위임)
        if (candleInterval != CandleInterval.ONE_DAY && candleInterval != CandleInterval.ONE_MIN) {
            return ResponseEntity.badRequest().body(
                BackfillResponse(
                    code = code,
                    interval = interval,
                    dryRun = dryRun,
                    gapCount = 0,
                    gapsSample = emptyList(),
                    backfillBatchId = "",
                    insertedCount = 0,
                    failedGapsCount = 0,
                    durationMs = 0L,
                    status = "BAD_REQUEST",
                    error = "Phase 2-A는 1d·1m만 지원 (interval=$interval). 5m·15m·30m·90m·1h는 AggregateBackfillRunner 위임.",
                ),
            )
        }

        if (code.isBlank() || code.length > 12) {
            return ResponseEntity.badRequest().body(
                BackfillResponse(
                    code = code,
                    interval = interval,
                    dryRun = dryRun,
                    gapCount = 0,
                    gapsSample = emptyList(),
                    backfillBatchId = "",
                    insertedCount = 0,
                    failedGapsCount = 0,
                    durationMs = 0L,
                    status = "BAD_REQUEST",
                    error = "Invalid code: $code",
                ),
            )
        }

        if (windowDays !in 1..365) {
            return ResponseEntity.badRequest().body(
                BackfillResponse(
                    code = code,
                    interval = interval,
                    dryRun = dryRun,
                    gapCount = 0,
                    gapsSample = emptyList(),
                    backfillBatchId = "",
                    insertedCount = 0,
                    failedGapsCount = 0,
                    durationMs = 0L,
                    status = "BAD_REQUEST",
                    error = "windowDays out of range: $windowDays (1..365)",
                ),
            )
        }

        val result =
            candleBackfillWorker.runBackfill(
                code = code,
                interval = candleInterval,
                dryRun = dryRun,
                windowDays = windowDays,
                reason = "admin",
            )

        return ResponseEntity.ok(
            BackfillResponse(
                code = result.code,
                interval = result.interval.code,
                dryRun = result.dryRun,
                gapCount = result.gapCount,
                gapsSample = result.gapsSample.map { it.toString() },
                backfillBatchId = result.backfillBatchId,
                insertedCount = result.insertedCount,
                failedGapsCount = result.failedGapsCount,
                durationMs = result.durationMs,
                status = result.status.name,
                error = null,
            ),
        )
    }

    data class BackfillResponse(
        val code: String,
        val interval: String,
        val dryRun: Boolean,
        val gapCount: Int,
        val gapsSample: List<String>,
        val backfillBatchId: String,
        val insertedCount: Int,
        val failedGapsCount: Int,
        val durationMs: Long,
        val status: String,
        val error: String?,
    )
}
