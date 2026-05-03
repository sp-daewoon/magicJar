package com.mj.batch.controller

import com.mj.batch.job.instrument.InstrumentMasterSyncJob
import com.mj.domain.common.Market
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 종목 마스터파일 수동 수집 트리거 (batch-app, port 8081).
 *
 * api-app의 InstrumentMasterSyncApiService가 HTTP POST /jobs/instrument/sync 로 호출한다.
 * dev·prd profile에서 활성 (InstrumentMasterSyncJob과 동일 조건).
 *
 * 흐름:
 * - POST /jobs/instrument/sync → runId 즉시 응답 + 비동기 실행
 * - GET  /jobs/instrument/sync/{runId} → 실행 상태 조회
 */
@RestController
@RequestMapping("/jobs/instrument")
@Profile("dev | prd")
@Tag(name = "InstrumentSyncTrigger", description = "종목 마스터파일 수동 수집 트리거 (batch-app 내부 endpoint)")
class InstrumentSyncTriggerController(
    private val syncJob: InstrumentMasterSyncJob,
) {
    private val log = LoggerFactory.getLogger(InstrumentSyncTriggerController::class.java)

    /** 실행 중인 runId → 상태 맵. 재시작 시 유실 — 단기 조회용. */
    private val runStatusMap = ConcurrentHashMap<String, SyncRunStatus>()

    @Operation(
        summary = "종목 마스터파일 수동 수집 트리거",
        description = """
            KIS OpenAPI로부터 종목 마스터파일을 즉시 수집한다.
            markets 미지정 시 KOSPI·KOSDAQ·KONEX 기본 대상.
            비동기 실행 — runId로 상태를 폴링한다 (GET /jobs/instrument/sync/{runId}).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "202", description = "수집 시작 (runId 반환)"),
        ],
    )
    @PostMapping("/sync")
    fun triggerSync(
        @RequestBody(required = false) req: SyncTriggerRequest?,
    ): ResponseEntity<SyncTriggerResponse> {
        val runId = UUID.randomUUID().toString()
        val markets =
            req?.markets?.mapNotNull { runCatching { Market.valueOf(it) }.getOrNull() }
                ?: listOf(Market.KOSPI, Market.KOSDAQ, Market.KONEX)

        runStatusMap[runId] = SyncRunStatus(runId = runId, status = "STARTED", scheduledAt = OffsetDateTime.now())
        log.info("[instrument-sync-trigger] runId={} markets={}", runId, markets)

        // 비동기 실행 — 간단한 Thread로 실행 (단일 작업, Batch Job 아님)
        Thread {
            try {
                for (market in markets) {
                    syncJob.syncMarket(market)
                }
                runStatusMap[runId] = runStatusMap[runId]!!.copy(status = "COMPLETED", completedAt = OffsetDateTime.now())
                log.info("[instrument-sync-trigger] runId={} COMPLETED", runId)
            } catch (ex: Exception) {
                runStatusMap[runId] =
                    runStatusMap[runId]!!.copy(
                        status = "FAILED",
                        errorMessage = ex.message,
                        completedAt = OffsetDateTime.now(),
                    )
                log.error("[instrument-sync-trigger] runId={} FAILED: {}", runId, ex.message, ex)
            }
        }.also {
            it.isDaemon = true
            it.name = "instrument-sync-$runId"
        }.start()

        return ResponseEntity.accepted().body(
            SyncTriggerResponse(runId = runId, status = "STARTED", scheduledAt = OffsetDateTime.now()),
        )
    }

    @Operation(
        summary = "종목 마스터파일 수집 상태 조회",
        description = "runId로 수집 진행 상태를 조회한다. 재시작 후에는 이전 실행 기록이 유실된다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "상태 조회 성공"),
            ApiResponse(responseCode = "404", description = "해당 runId 없음"),
        ],
    )
    @GetMapping("/sync/{runId}")
    fun syncStatus(
        @PathVariable runId: String,
    ): ResponseEntity<SyncRunStatus> {
        val status = runStatusMap[runId] ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(status)
    }

    // ─── DTOs ─────────────────────────────────────────────────────────────────

    @Schema(description = "종목 마스터파일 수집 트리거 요청")
    data class SyncTriggerRequest(
        @field:Schema(
            description = "수집 대상 시장 (기본: [KOSPI, KOSDAQ, KONEX])",
            example = "[\"KOSPI\", \"KOSDAQ\"]",
        )
        val markets: List<String>? = null,
    )

    @Schema(description = "종목 마스터파일 수집 트리거 응답")
    data class SyncTriggerResponse(
        @field:Schema(description = "실행 ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        val runId: String,
        @field:Schema(description = "시작 상태", example = "STARTED")
        val status: String,
        @field:Schema(description = "트리거 시각 (ISO-8601)", example = "2026-05-02T12:00:00+09:00")
        val scheduledAt: OffsetDateTime,
    )

    @Schema(description = "종목 마스터파일 수집 실행 상태")
    data class SyncRunStatus(
        @field:Schema(description = "실행 ID")
        val runId: String,
        @field:Schema(description = "상태 (STARTED / COMPLETED / FAILED)")
        val status: String,
        @field:Schema(description = "트리거 시각")
        val scheduledAt: OffsetDateTime,
        @field:Schema(description = "완료 시각 (COMPLETED·FAILED 시 설정)", nullable = true)
        val completedAt: OffsetDateTime? = null,
        @field:Schema(description = "오류 메시지 (FAILED 시 설정)", nullable = true)
        val errorMessage: String? = null,
    )
}
