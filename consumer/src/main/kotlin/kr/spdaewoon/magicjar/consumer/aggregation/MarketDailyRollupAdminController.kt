package kr.spdaewoon.magicjar.consumer.aggregation

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 전종목 1wk/1mo/1y 1d 롤업 백필 admin 트리거 (ADR 0120 — 년봉 회귀 복구).
 *
 * consumer 모듈 내부 REST (port 8082). [MarketDailyBackfillAdminController] 패턴 재사용.
 *
 * ## 트리거 (멱등 — upsert DO UPDATE)
 *   - POST /admin/market-daily-rollup/run — 전종목 1d → 1wk/1mo/1y 합산 1회 실행(KIS 콜 0).
 *     매 호출 멱등(행 안 늘고 값만 갱신). 년봉 회귀(1wk/1mo/1y 15종목뿐) 1회 복구용 +
 *     필요 시 재실행.
 *
 * ## 실행 예 (curl — 오케스트라 운영 단계 전용, 머지·재배포 후)
 * ```
 * # 전종목 1d 에서 주/월/년봉 합산 적재 (KIS 콜 0 — 이미 보유한 1d 합산)
 * curl -X POST 'http://localhost:8082/admin/market-daily-rollup/run'
 * # 응답: {"status":"COMPLETED","universeSize":2517,"processed":...,"weeklyUpserted":...,
 * #        "monthlyUpserted":...,"yearlyUpserted":...,"durationMs":...}
 * ```
 *
 * ## 전제
 *   R-BF(POST /admin/market-daily-backfill/run)로 1d 전종목 10년이 먼저 적재되어 있어야 한다
 *   (1d 0건 종목은 skippedNoDaily 로 집계). R2 야간 스케줄러는 매 영업일 today 1d snapshot 직후
 *   1wk/1mo/1y 를 자동 재롤업하므로(증분), 본 admin 은 대량 1회 복구 + 수동 재실행 경로다.
 *
 * 자본 영향 0 — 데이터 적재만. KIS 콜 0.
 */
@RestController
@RequestMapping("/admin/market-daily-rollup")
class MarketDailyRollupAdminController(
    private val rollupService: MarketDailyRollupService,
) {
    private val log = LoggerFactory.getLogger(MarketDailyRollupAdminController::class.java)

    @PostMapping("/run")
    fun run(): ResponseEntity<RollupResultResponse> {
        log.info("admin market-daily-rollup run trigger (전종목 1d → 1wk/1mo/1y 합산, KIS 콜 0)")
        val r = rollupService.runRollup()
        return ResponseEntity.ok(
            RollupResultResponse(
                runId = r.runId,
                status = r.status.name,
                universeSize = r.universeSize,
                processedSymbols = r.processedSymbols,
                skippedNoDaily = r.skippedNoDaily,
                weeklyUpserted = r.weeklyUpserted,
                monthlyUpserted = r.monthlyUpserted,
                yearlyUpserted = r.yearlyUpserted,
                failedSymbols = r.failedSymbols,
                durationMs = r.durationMs,
            ),
        )
    }

    data class RollupResultResponse(
        val runId: String,
        val status: String,
        val universeSize: Int,
        val processedSymbols: Int,
        val skippedNoDaily: Int,
        val weeklyUpserted: Long,
        val monthlyUpserted: Long,
        val yearlyUpserted: Long,
        val failedSymbols: Int,
        val durationMs: Long,
    )
}
