package kr.spdaewoon.magicjar.api.service.trend

import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.trend.IndexTrend
import kr.spdaewoon.magicjar.domain.trend.MarketIndexSnapshotReader
import kr.spdaewoon.magicjar.domain.trend.TrendPreferenceDefaults
import kr.spdaewoon.magicjar.market.calculator.TrendCalculator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 지수 추세 query 단일 진입점 (ADR 0100 §2-5 `MarketIndexController` reuse).
 *
 * - `snapshot()` — 4 지수 (KOSPI · KOSDAQ · NASDAQ · S&P) 카드 query (user pref 적용 trend 라벨)
 * - `trend(code, overrides)` — 단일 지수 상세 (periods override 우선 · 없으면 user pref)
 *
 * candles는 `CandleRepository.findByCodeAndInterval(code, ONE_DAY)` 일봉 list ASC 사용.
 * 데이터 0건 시 `dataInsufficient=true` flag echo (mock 합성 0 — feedback_no_mock_data 정합).
 */
@Service
class TrendQueryService(
    private val candleRepository: CandleRepository,
    private val trendCalculator: TrendCalculator,
    private val trendPreferenceService: TrendPreferenceService,
    private val marketIndexSnapshotReader: MarketIndexSnapshotReader,
) {
    private val log = LoggerFactory.getLogger(TrendQueryService::class.java)

    /**
     * 4 지수 카드 query.
     *
     * @param userId 사용자 id (TrendPreference 적용)
     * @param indexCodes default [INDEX_CODES_DEFAULT] (KOSPI · KOSDAQ · NASDAQ · S&P)
     */
    @Transactional(readOnly = true)
    fun snapshot(
        userId: String,
        indexCodes: List<String> = INDEX_CODES_DEFAULT,
    ): List<IndexTrend> {
        val pref = trendPreferenceService.getOrDefault(userId)
        val isUserCustom = !pref.isDefault()
        return indexCodes.map { code ->
            computeTrend(
                code = code,
                periods = pref.maPeriods,
                crossPair = pref.crossPair,
                isUserCustom = isUserCustom,
            )
        }
    }

    /**
     * 단일 지수 추세 상세.
     *
     * @param userId 사용자 id
     * @param code KIS index code
     * @param periodsOverride 일회성 override (URL share / 테스트) · null 시 user pref
     */
    @Transactional(readOnly = true)
    fun trend(
        userId: String,
        code: String,
        periodsOverride: List<Int>? = null,
    ): IndexTrend {
        val pref = trendPreferenceService.getOrDefault(userId)
        val periods = periodsOverride ?: pref.maPeriods
        // override 사용 시에도 사용자 본인 pref 와 다르면 isUserCustom 로 표기 (frontend "임시 적용" 표시 가능)
        val isUserCustom =
            periodsOverride != null &&
                periodsOverride != TrendPreferenceDefaults.MA_PERIODS ||
                periodsOverride == null &&
                !pref.isDefault()
        val crossPair = if (periodsOverride != null) null else pref.crossPair
        return computeTrend(code = code, periods = periods, crossPair = crossPair, isUserCustom = isUserCustom)
    }

    private fun computeTrend(
        code: String,
        periods: List<Int>,
        crossPair: Pair<Int, Int>?,
        isUserCustom: Boolean,
    ): IndexTrend {
        val candles = candleRepository.findByCodeAndInterval(code, CandleInterval.ONE_DAY)
        log.debug("trend candles loaded: code={} count={}", code, candles.size)
        return trendCalculator.computeIndexTrend(
            code = code,
            candles = candles,
            periods = periods,
            crossPair = crossPair,
            isUserCustom = isUserCustom,
        )
    }

    companion object {
        /**
         * 4 지수 default codes (ADR 0100 §2-5).
         * - 0001 = KOSPI · 1001 = KOSDAQ · .IXIC = NASDAQ · .SPX = S&P 500
         */
        val INDEX_CODES_DEFAULT: List<String> = listOf("0001", "1001", ".IXIC", ".SPX")
    }
}
