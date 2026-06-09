package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * MarketDailyRollupService — ADR 0120 전종목 1wk/1mo/1y 1d 롤업 백필 본체.
 *
 * ## 배경 (년봉 회귀 — 2026-06-09 마이그레이션 부작용)
 *   MIGRATE wipe 가 일봉 패밀리 4종(1d·1wk·1mo·1y) 전부 삭제했으나 R-BF 캠페인은 1d 만 재적재
 *   → 실측 1wk·1mo·1y 가 15종목만 남음(전종목 비었음, 차트 년봉 안 나옴). 본 서비스가 전종목 1d 에서
 *   1wk/1mo/1y 를 합산 적재하여 복구한다. **KIS 콜 0** (이미 보유한 1d 10년 전종목 합산).
 *
 * ## 책임 (한 PR = 한 영역 · market-data)
 *   1. [MarketDailyUniverseProvider.resolveCodes] 보통주 ~2,517 코드 확보 (R1·R-BF 재사용).
 *   2. 종목별 [DailyFamilyRollupRunner.rollupDetailed] — 1d 전량 in-memory 롤업 → upsert(DO UPDATE).
 *   3. 멱등 — upsertAll(ON CONFLICT DO UPDATE) 이므로 재실행 시 행 안 늘고 값만 갱신.
 *   4. 관측 로그 + audit (처리 종목수·upsert 건수·실패·소요).
 *
 * ## R-BF / R2 / 본 서비스 분업 (ADR 0120 §2)
 *   - R-BF(MarketDailyBackfillCampaign): 1d 과거 10년 KIS range fetch (기준봉 적재).
 *   - 본 서비스(rollup): R-BF 가 채운 1d 에서 1wk/1mo/1y 파생 합산 (1회 복구 + admin 재실행).
 *   - R2(MarketDailyNightlySnapshotService): 매 영업일 today 1d snapshot 직후 1wk/1mo/1y 재롤업(증분 최신화).
 *
 * 자본 영향 0 — 데이터 적재만(주문 0). LLM import 0 (룰 5). 외부 fallback 0 (KIS-only · KIS 콜 0).
 */
@Component
class MarketDailyRollupService(
    private val universeProvider: MarketDailyUniverseProvider,
    private val rollupRunner: DailyFamilyRollupRunner,
    private val auditRepository: ConsumerAuditTrailJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(MarketDailyRollupService::class.java)

    companion object {
        /** audit_trail.entity_id VARCHAR(40) 한계 — 짧은 고정 식별자. runId 는 detail. */
        const val AUDIT_ENTITY_ID = "mkt-daily-rollup"
        const val AUDIT_ENTITY_TYPE = "market_daily_rollup"
    }

    enum class RollupStatus {
        EMPTY_UNIVERSE, // universe 0종목 (데이터 없음 path — mock 합성 0)
        COMPLETED, // 정상 완주
    }

    data class RollupResult(
        val runId: String,
        val status: RollupStatus,
        val universeSize: Int,
        val processedSymbols: Int,
        val skippedNoDaily: Int,
        val weeklyUpserted: Long,
        val monthlyUpserted: Long,
        val yearlyUpserted: Long,
        val failedSymbols: Int,
        val durationMs: Long,
    )

    /**
     * 전종목 1wk/1mo/1y 롤업 1회 실행 — 1d 에서 합산 적재(KIS 콜 0).
     *
     * 매 호출 멱등(upsert DO UPDATE) — 재호출 시 행 안 늘고 값만 갱신. universe 0 시 EMPTY_UNIVERSE.
     */
    fun runRollup(): RollupResult {
        val startMs = System.currentTimeMillis()
        val runId = "mkt-daily-rollup:$startMs"

        val codes = universeProvider.resolveCodes()
        if (codes.isEmpty()) {
            log.warn("MarketDailyRollup EMPTY_UNIVERSE — 보통주 0종목 (데이터 없음 — mock 합성 0)")
            audit("MARKET_DAILY_ROLLUP_EMPTY", mapOf("runId" to runId))
            return RollupResult(runId, RollupStatus.EMPTY_UNIVERSE, 0, 0, 0, 0, 0, 0, 0, System.currentTimeMillis() - startMs)
        }

        log.info("MarketDailyRollup 시동: runId={} universe={}종목 (1wk/1mo/1y ← 1d 합산, KIS 콜 0)", runId, codes.size)
        audit("MARKET_DAILY_ROLLUP_START", mapOf("runId" to runId, "universeSize" to codes.size))

        var processed = 0
        var skippedNoDaily = 0
        var weekly = 0L
        var monthly = 0L
        var yearly = 0L
        var failed = 0

        for (code in codes) {
            val detailed = rollupRunner.rollupDetailed(code)
            if (detailed.isEmpty()) {
                skippedNoDaily++
                continue
            }
            processed++
            var symbolFailed = false
            detailed.forEach { (interval, r) ->
                if (r.upserted < 0) {
                    symbolFailed = true
                    return@forEach
                }
                when (interval) {
                    CandleInterval.ONE_WEEK.code -> weekly += r.upserted
                    CandleInterval.ONE_MONTH.code -> monthly += r.upserted
                    CandleInterval.ONE_YEAR.code -> yearly += r.upserted
                }
            }
            if (symbolFailed) failed++
        }

        val durationMs = System.currentTimeMillis() - startMs
        val result =
            RollupResult(
                runId = runId,
                status = RollupStatus.COMPLETED,
                universeSize = codes.size,
                processedSymbols = processed,
                skippedNoDaily = skippedNoDaily,
                weeklyUpserted = weekly,
                monthlyUpserted = monthly,
                yearlyUpserted = yearly,
                failedSymbols = failed,
                durationMs = durationMs,
            )

        log.info(
            "MarketDailyRollup 종료: runId={} universe={} processed={} skippedNoDaily={} " +
                "weekly={} monthly={} yearly={} failed={} durationMs={}",
            runId,
            result.universeSize,
            result.processedSymbols,
            result.skippedNoDaily,
            result.weeklyUpserted,
            result.monthlyUpserted,
            result.yearlyUpserted,
            result.failedSymbols,
            result.durationMs,
        )
        audit(
            "MARKET_DAILY_ROLLUP_DONE",
            mapOf(
                "runId" to runId,
                "status" to result.status.name,
                "universeSize" to result.universeSize,
                "processed" to result.processedSymbols,
                "skippedNoDaily" to result.skippedNoDaily,
                "weeklyUpserted" to result.weeklyUpserted,
                "monthlyUpserted" to result.monthlyUpserted,
                "yearlyUpserted" to result.yearlyUpserted,
                "failed" to result.failedSymbols,
                "durationMs" to result.durationMs,
            ),
        )
        return result
    }

    private fun audit(
        action: String,
        detail: Map<String, Any?>,
    ) {
        val detailJson =
            try {
                objectMapper.writeValueAsString(detail)
            } catch (e: Exception) {
                log.warn("MarketDailyRollup audit JSON 직렬화 실패: action={} err={}", action, e.message)
                "{}"
            }
        try {
            auditRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "market-data",
                    action = action,
                    entityType = AUDIT_ENTITY_TYPE,
                    entityId = AUDIT_ENTITY_ID,
                    detailJson = detailJson,
                ),
            )
        } catch (e: Exception) {
            log.warn("MarketDailyRollup audit 저장 실패: action={} err={}", action, e.message)
        }
    }
}
