package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.config.MarketDailyBackfillProperties
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * MARKET_DAILY 10년 일봉 backfill 캠페인 admin 트리거 (ADR 0118 R-BF).
 *
 * consumer 모듈 내부 REST (port 8082). 기존 [CandleBackfillAdminController] 패턴 재사용.
 *
 * ## 멱등 트리거 (시동 1회 → 이후 호출마다 progress 이어받기)
 *   - POST /admin/market-daily-backfill/run — 캠페인 1회 실행(멱등 재개).
 *     매 호출마다 이미 적재 완료 종목 skip + 밤당 콜 cap까지만 처리 + 미완료분 다음 호출 이연.
 *   - GET  /admin/market-daily-backfill/progress — universe·완료/잔여 종목 수 관측(콜 0 — count만).
 *
 * ## ENV gate (시동/중단)
 *   `magicjar.market-daily-backfill.enabled` (MAGICJAR_MARKET_DAILY_BACKFILL_ENABLED)
 *     - false(default) → run 호출해도 DISABLED 즉시 종료(가동 0, 적재분 보존 — 중단 = off).
 *     - true → 캠페인 가동. 매 run 호출이 progress 이어받아 미완료만 처리.
 *
 * ## rate-limit (ADR 0118 §5-1 LOCK)
 *   캠페인 모든 KIS 콜은 BACKFILL(BG) 버킷 고정 — USER_PATH·INGESTION 침범 0.
 *   8~12h 점유해도 자본·사용자 path 영향 0.
 *
 * 자본 영향 0 — 데이터 적재만.
 *
 * ## ⚠ full 실행 주의
 *   본 endpoint 1회 호출은 cap(default 6,000콜)까지만 처리한다. 전종목 10년(~75,000콜) full 완주는
 *   여러 밤/주말에 걸친 반복 호출(또는 R2 야간 스케줄러 배선)로 이뤄지며, **실 가동은 사용자 환경 + 명시 go 시점**이다.
 */
@RestController
@RequestMapping("/admin/market-daily-backfill")
class MarketDailyBackfillAdminController(
    private val campaign: MarketDailyBackfillCampaign,
    private val properties: MarketDailyBackfillProperties,
) {
    private val log = LoggerFactory.getLogger(MarketDailyBackfillAdminController::class.java)

    @PostMapping("/run")
    fun run(): ResponseEntity<CampaignResultResponse> {
        log.info("admin market-daily-backfill run trigger: enabled={}", properties.enabled)
        val r = campaign.runCampaign()
        return ResponseEntity.ok(
            CampaignResultResponse(
                runId = r.runId,
                status = r.status.name,
                universeSize = r.universeSize,
                alreadyComplete = r.alreadyComplete,
                processedSymbols = r.processedSymbols,
                newlyCompleteSymbols = r.newlyCompleteSymbols,
                insertedRows = r.insertedRows,
                callsConsumed = r.callsConsumed,
                callCap = r.callCap,
                failedSymbols = r.failedSymbols,
                skippedThrottle = r.skippedThrottle,
                remainingSymbols = r.remainingSymbols,
                durationMs = r.durationMs,
                enabled = properties.enabled,
            ),
        )
    }

    @GetMapping("/progress")
    fun progress(): ResponseEntity<ProgressResponse> {
        val total = campaign.universeCount()
        return ResponseEntity.ok(
            ProgressResponse(
                enabled = properties.enabled,
                universeSize = total,
                historyYears = properties.normalizedHistoryYears(),
                nightlyCallCap = properties.normalizedNightlyCallCap(),
                note = "잔여/완료 정확 산출은 POST /run 의 응답(remainingSymbols) 참조 — progress 는 universe 규모 관측만(콜 0).",
            ),
        )
    }

    data class CampaignResultResponse(
        val runId: String,
        val status: String,
        val universeSize: Int,
        val alreadyComplete: Int,
        val processedSymbols: Int,
        val newlyCompleteSymbols: Int,
        val insertedRows: Int,
        val callsConsumed: Int,
        val callCap: Int,
        val failedSymbols: Int,
        val skippedThrottle: Int,
        val remainingSymbols: Int,
        val durationMs: Long,
        val enabled: Boolean,
    )

    data class ProgressResponse(
        val enabled: Boolean,
        val universeSize: Long,
        val historyYears: Int,
        val nightlyCallCap: Int,
        val note: String,
    )
}
