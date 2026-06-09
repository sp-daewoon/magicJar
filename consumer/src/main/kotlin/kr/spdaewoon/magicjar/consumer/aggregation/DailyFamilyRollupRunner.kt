package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.ONE_DAY
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.ONE_MONTH
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.ONE_WEEK
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.ONE_YEAR
import kr.spdaewoon.magicjar.domain.market.candle.DailyRollupAggregator
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 1d candle 베이스를 1wk·1mo·1y 로 롤업 + DB UPSERT (ADR 0120 — 기준봉/파생봉 통일 모델).
 *
 * [AggregateBackfillRunner](1m → 3m~12h) 의 1d-family 대칭 컴포넌트. KIS 콜 0(이미 보유한 1d 합산).
 *
 * ## 사용 시점
 *   - [MarketDailyRollupService] 전종목 1회 백필 (admin 트리거) — 년봉 회귀 복구.
 *   - [MarketDailyNightlySnapshotService] R2 야간 today 1d snapshot 직후 — 1wk/1mo/1y 최신화.
 *
 * ## 닫힌 봉 + 진행봉 정합 (ADR 0120 §실시간 보존)
 *   본 러너는 DB 의 1d 전량(어제까지 닫힌 봉 + R2 가 적재한 today 1d)을 입력으로 롤업한다.
 *   따라서 "오늘 진행 주/월/년봉" 도 today 1d 까지 반영된 닫힌-스냅샷으로 upsert 된다.
 *   장중 tick 실시간 갱신은 `CandleQueryService.mergeQuoteLastCurrentCandle`(quote:last 병합)가
 *   별도로 최신 봉 위에 덮으므로, 본 롤업 결과를 라이브 merge 가 그대로 갱신한다(회귀 0).
 *
 * 원칙:
 *   - in-memory 롤업 (외부 호출 0). 10년 1d ~2,450 row → 롤업 가벼움(<10ms/종목 예상).
 *   - DB UPSERT (ON CONFLICT DO UPDATE) — 1d 교정 후 상위 봉 재생성 허용.
 *   - 1d 0건 종목은 즉시 반환 (guard). mock 합성 0 (DailyRollupAggregator 가 구간 내 1d 0건 시 bar 생략).
 *
 * @param candleRepository CandleRepository 도메인 포트 (consumer 구현 주입)
 */
@Component
class DailyFamilyRollupRunner(
    private val candleRepository: CandleRepository,
) {
    private val log = LoggerFactory.getLogger(DailyFamilyRollupRunner::class.java)

    companion object {
        /** ADR 0120 — 1d 에서 롤업 생성하는 상위 timeframe 3종 (KIS 직접 fetch 폐지). */
        val TARGET_INTERVALS = listOf(ONE_WEEK, ONE_MONTH, ONE_YEAR)
    }

    data class IntervalRollupResult(
        val upserted: Int,
        val latestBar: Candle?,
    )

    /**
     * 종목 1d candle 전량을 1wk·1mo·1y 로 롤업 + DB UPSERT.
     *
     * @param code 종목 코드 (KIS 6자리)
     * @return interval code → upsert 건수 (운영 관측)
     */
    fun rollup(code: String): Map<String, Int> = rollupDetailed(code).mapValues { it.value.upserted }

    fun rollupDetailed(code: String): Map<String, IntervalRollupResult> {
        val oneDayCandles =
            try {
                candleRepository.findByCodeAndInterval(code, ONE_DAY)
            } catch (e: Exception) {
                log.warn("DailyFamilyRollupRunner: 1d 조회 실패 — skip: code={} err={}", code, e.message)
                return emptyMap()
            }
        if (oneDayCandles.isEmpty()) {
            log.debug("DailyFamilyRollupRunner: 1d candle 없음 — 롤업 skip: code={}", code)
            return emptyMap()
        }

        val result = mutableMapOf<String, IntervalRollupResult>()
        for (target in TARGET_INTERVALS) {
            try {
                val rolled = DailyRollupAggregator.rollup(oneDayCandles, target)
                if (rolled.isNotEmpty()) {
                    val upserted = candleRepository.upsertAll(rolled)
                    result[target.code] = IntervalRollupResult(upserted = upserted, latestBar = rolled.last())
                    log.debug(
                        "DailyFamilyRollupRunner interval 완료: code={} interval={} rolled={} upserted={}",
                        code,
                        target.code,
                        rolled.size,
                        upserted,
                    )
                } else {
                    result[target.code] = IntervalRollupResult(upserted = 0, latestBar = null)
                }
            } catch (e: Exception) {
                log.warn(
                    "DailyFamilyRollupRunner interval 실패: code={} interval={} err={}",
                    code,
                    target.code,
                    e.message,
                )
                result[target.code] = IntervalRollupResult(upserted = -1, latestBar = null)
            }
        }

        val total =
            result.values
                .map { it.upserted }
                .filter { it >= 0 }
                .sum()
        log.info(
            "DailyFamilyRollupRunner 완료: code={} 1dCount={} totalUpserted={} breakdown={}",
            code,
            oneDayCandles.size,
            total,
            result.mapValues { it.value.upserted },
        )
        return result
    }
}
