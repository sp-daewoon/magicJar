package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisMinuteBackfillRangePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 분봉 수집 풀 — 단일 종목 1분봉 range 수집 + 적재 + 합산봉 롤업 (2026-07-10 · market-data · minute-pool).
 *
 * 과거 백필([kr.spdaewoon.magicjar.consumer.aggregation.MinutePoolBackfillCampaign])과 장중 전방 수집
 * ([MinutePoolForwardPoller]) 두 경로가 공유하는 유일한 KIS-calling 진입점. 흐름:
 *
 *   1. [KisMinuteBackfillRangePort.fetchOneMinuteCandlesForBackfill] (FHKST03010230 · **BACKFILL(BG) 버킷**)로
 *      [from]..[to] 1분봉 fetch — USER_PATH(주문·현재가·차트)·INGESTION(approval·watchlist) 침범 0.
 *   2. [CandleRepository.saveAll] (ON CONFLICT DO NOTHING · 멱등) — 과거봉 immutable, 재실행 중복 0.
 *   3. INSERT > 0 이면 [AggregateBackfillRunner.backfill] (기존 롤업 경로 재사용)로 5m·15m·30m·1h 등 재생성
 *      (upsert DO UPDATE).
 *
 * KIS upstream/rate-limit(EGW00201)/credential 실패는 [KisMinuteBackfillRangePort] 계약대로 예외 전파(fail-closed).
 * 호출자(campaign·poller)가 per-code try-catch 로 흡수하고 failed 카운트 후 다음 종목 진행(silent skip + 카운터).
 *
 * candle.source = [SOURCE](14자 ≤ VARCHAR(20)) — 기존 source 컨벤션과 충돌 없는 별도 값(관측·감사 구분용).
 */
@Component
class MinutePoolCollectionService(
    private val kisMinuteBackfillRangePort: KisMinuteBackfillRangePort,
    private val candleRepository: CandleRepository,
    private val aggregateBackfillRunner: AggregateBackfillRunner,
) {
    private val log = LoggerFactory.getLogger(MinutePoolCollectionService::class.java)

    companion object {
        /** candle.source VARCHAR(20) 한계 정합 — "KIS-POOL-1M-BF" = 14자. */
        const val SOURCE = "KIS-POOL-1M-BF"
        const val SOURCE_MAX_LEN = 20
    }

    init {
        require(SOURCE.length <= SOURCE_MAX_LEN) {
            "SOURCE '$SOURCE' length=${SOURCE.length} exceeds candle.source VARCHAR($SOURCE_MAX_LEN)"
        }
    }

    data class CollectOutcome(
        val fetched: Int,
        val inserted: Int,
        val rollupUpserted: Int,
    )

    /**
     * [code] 의 [from]..[to] 1분봉을 수집·적재·롤업한다.
     *
     * @throws IllegalStateException KIS rate-limit/credential/HTTP 실패 시 (호출자 fail-closed 흡수 의무).
     */
    fun collect(
        code: String,
        from: Instant,
        to: Instant,
    ): CollectOutcome {
        if (from.isAfter(to)) {
            return CollectOutcome(fetched = 0, inserted = 0, rollupUpserted = 0)
        }
        val fetched: List<Candle> = kisMinuteBackfillRangePort.fetchOneMinuteCandlesForBackfill(code, from, to)
        if (fetched.isEmpty()) {
            return CollectOutcome(fetched = 0, inserted = 0, rollupUpserted = 0)
        }
        val inserted = candleRepository.saveAll(fetched.map { it.copy(source = SOURCE) })
        var rollupUpserted = 0
        if (inserted > 0) {
            val aggregate = aggregateBackfillRunner.backfill(code)
            rollupUpserted = aggregate.values.sum()
            log.debug(
                "minute-pool collect: code={} range={}..{} fetched={} inserted={} rollup={}",
                code,
                from,
                to,
                fetched.size,
                inserted,
                aggregate,
            )
        }
        return CollectOutcome(fetched = fetched.size, inserted = inserted, rollupUpserted = rollupUpserted)
    }
}
