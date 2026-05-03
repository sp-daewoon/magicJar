package com.mj.api.application.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.port.CandleRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId

/**
 * 캔들 조회 서비스 — 분봉 합성 정책 v2 (ADR 0048).
 *
 * 분기 규칙:
 *   D1/DAY alias  → DB에서 D1 직접 조회
 *   M1/1m alias   → DB에서 M1 직접 조회
 *   W/MO/Y        → D1 조회 후 [CandleAggregator.aggregateDailyToWeekly]
 *   그 외 N분봉   → M1 조회 후 [CandleAggregator.aggregate(targetMinutes)]
 */
@Service
class CandleQueryService(
    private val candleRepository: CandleRepository,
    private val candleAggregator: CandleAggregator,
    private val candleCacheService: CandleCacheService,
) {
    private val kst = ZoneId.of("Asia/Seoul")
    private val log = org.slf4j.LoggerFactory.getLogger(CandleQueryService::class.java)

    /**
     * 캔들 조회 — interval 문자열 기반 (v2 주 진입점).
     *
     * @param code        종목코드
     * @param intervalRaw interval 문자열 (D1/M1/M5/M30/H1/60m/90m/W/MO/Y 등)
     * @param from        조회 시작 시각 (null이면 봉별 기본 lookback)
     * @param to          조회 종료 시각 (null이면 현재)
     */
    fun queryByString(
        code: String,
        intervalRaw: String,
        from: Instant?,
        to: Instant?,
    ): List<Candle> {
        val upper = intervalRaw.trim().uppercase()
        val toResolved = to ?: Instant.now()

        // D1 / DAY alias
        if (isD1(upper)) {
            val fromResolved = from ?: defaultFrom(Interval.D1, toResolved)
            return candleRepository.find(code, Interval.D1, fromResolved, toResolved)
        }

        // M1 alias
        if (isM1(upper)) {
            val fromResolved = from ?: defaultFrom(Interval.M1, toResolved)
            val candles = candleRepository.find(code, Interval.M1, fromResolved, toResolved)
            // cold-start (사용자 정책): 신규 종목 첫 진입 시 분봉 0건이면 yfinance backfill 자동 trigger.
            if (candles.isEmpty()) {
                log.info("[query-svc] M1 0건 — cold-start backfill trigger 시도 code={}", code)
                candleCacheService.triggerBackgroundFetchAsync(code)
            }
            return candles
        }

        // 일봉 기반 합성 (W/MO/Y)
        if (CandleAggregator.isDailyPeriod(upper)) {
            val fromResolved = from ?: defaultFrom(Interval.D1, toResolved)
            val d1Candles = candleRepository.find(code, Interval.D1, fromResolved, toResolved)
            return candleAggregator.aggregateDailyToWeekly(d1Candles, upper)
        }

        // 분봉 합성 (M5/M15/M30/H1/임의 N분봉)
        val targetMinutes =
            CandleAggregator.parseToMinutesOrNull(intervalRaw)
                ?: throw IllegalArgumentException("Unsupported interval: $intervalRaw")

        require(targetMinutes > 0) { "interval must be positive minutes, got $intervalRaw" }
        require(targetMinutes <= 1440) { "interval must be <= 1440 minutes (1 day), got $intervalRaw" }

        val fromResolved = from ?: defaultFromMinutes(targetMinutes, toResolved)
        val m1Candles = candleRepository.find(code, Interval.M1, fromResolved, toResolved)
        // cold-start (N분봉 합성 path도 동일) — M1 0건이면 backfill trigger.
        if (m1Candles.isEmpty()) {
            log.info("[query-svc] M1 0건(N={}분봉 합성) — cold-start backfill trigger code={}", targetMinutes, code)
            candleCacheService.triggerBackgroundFetchAsync(code)
        }
        return candleAggregator.aggregate(m1Candles, targetMinutes)
    }

    /**
     * 하위 호환: Interval enum을 직접 받는 경우 (기존 테스트·MCP tool 에서 사용).
     * D1·M1만 지원. 그 외 enum 값은 queryByString으로 전환 필요.
     */
    fun query(
        code: String,
        interval: Interval,
        from: Instant?,
        to: Instant?,
    ): List<Candle> {
        val toResolved = to ?: Instant.now()
        val fromResolved = from ?: defaultFrom(interval, toResolved)
        return candleRepository.find(code, interval, fromResolved, toResolved)
    }

    /**
     * infinite scroll: before 이전 캔들 반환 — interval 문자열 기반 (v2).
     *
     * 합성 interval 처리: M1을 limit * targetMinutes 만큼 조회 후 합성.
     */
    fun queryBeforeByString(
        code: String,
        intervalRaw: String,
        before: Instant,
        limit: Int,
    ): List<Candle> {
        val upper = intervalRaw.trim().uppercase()

        if (isD1(upper)) return candleRepository.findBefore(code, Interval.D1, before, limit)
        if (isM1(upper)) return candleRepository.findBefore(code, Interval.M1, before, limit)

        if (CandleAggregator.isDailyPeriod(upper)) {
            val d1Candles = candleRepository.findBefore(code, Interval.D1, before, limit)
            return candleAggregator.aggregateDailyToWeekly(d1Candles, upper)
        }

        val targetMinutes =
            CandleAggregator.parseToMinutesOrNull(intervalRaw)
                ?: throw IllegalArgumentException("Unsupported interval: $intervalRaw")

        // M1 기준 더 넓게 조회 (합성 후 limit개 확보)
        val m1Limit = (limit.toLong() * targetMinutes).coerceAtMost(10_000L).toInt()
        val m1Candles = candleRepository.findBefore(code, Interval.M1, before, m1Limit)
        return candleAggregator.aggregate(m1Candles, targetMinutes)
    }

    /**
     * 하위 호환: Interval enum 기반 infinite scroll (기존 테스트).
     */
    fun queryBefore(
        code: String,
        interval: Interval,
        before: Instant,
        limit: Int,
    ): List<Candle> = candleRepository.findBefore(code, interval, before, limit)

    // ── private helpers ─────────────────────────────────────────────────────

    private fun isD1(upper: String): Boolean = upper in setOf("D1", "DAY", "D", "1D")

    private fun isM1(upper: String): Boolean = upper in setOf("M1", "1M", "1MIN", "MIN1")

    private fun defaultFrom(
        interval: Interval,
        to: Instant,
    ): Instant {
        val daysBack =
            when (interval) {
                Interval.D1 -> 180L
                Interval.M1 -> 7L
            }
        return to
            .atZone(kst)
            .toLocalDate()
            .minusDays(daysBack)
            .atStartOfDay(kst)
            .toInstant()
    }

    private fun defaultFromMinutes(
        targetMinutes: Int,
        to: Instant,
    ): Instant {
        // N분봉 lookback: M1 영속 한도(yfinance 30일)와 정합 — 30일 KST 자정 align.
        // 키움 MTS 등 일반 차트가 합성 결과 봉 100~500개 정도 보여주므로 30일이면 충분.
        return to
            .atZone(kst)
            .toLocalDate()
            .minusDays(30)
            .atStartOfDay(kst)
            .toInstant()
    }

    companion object {
        /**
         * interval 문자열 → Interval enum 파싱.
         *
         * v2 정책: D1·M1 alias만 허용.
         * M5/M15/M30/H1 등 합성 interval은 queryByString이 처리 — 이 메서드는 IllegalArgumentException 발생.
         * 기존 코드 호환을 위해 companion object로 유지.
         */
        fun parseInterval(raw: String): Interval =
            when (raw.trim().uppercase()) {
                "DAY", "D", "D1", "1D" -> Interval.D1
                "M1", "1M", "1MIN", "MIN1" -> Interval.M1
                else ->
                    throw IllegalArgumentException(
                        "Unsupported storage interval: '$raw'. " +
                            "Synthetic intervals (M5/M15/M30/H1/N분봉) are handled by CandleQueryService.queryByString.",
                    )
            }
    }
}
