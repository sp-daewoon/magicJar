package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleGapDetectorPort
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * CandleGapDetectorPort 구현 — consumer 모듈 (ADR 0086 §2-2 + T-CC Phase 2-A).
 *
 * 책임:
 *   - 영업일 캘린더 기반 expected sequence 생성 (BusinessDayCalendar.isBusinessDay() 의무 호출)
 *   - DB actual sequence 조회 (CandleRepository.findAllTsByCodeAndIntervalBetween)
 *   - HashSet diff O(1) — gaps 산출 (ASC)
 *
 * 햇징 룰 적용 (ADR 0086 §2-1):
 *   - 룰 #6 false positive 가드:
 *       * 거래정지·정리매매 종목 → empty list 반환 (ColdStartExecutor KIS_COLD_START_SKIPPED 정합)
 *       * 휴장일 제외 (BusinessDayCalendar.isBusinessDay() 호출)
 *       * 시간외(NXT) — Phase 2-A 1차 MVP 1m 갭은 정규장 09:00~15:30 KST만 cover (NXT cover는 Phase 후속)
 *   - 룰 #7 false negative 가드: actual 0건이면 expected 전체 = 갭 list (last_ts null Phase 1 패턴 정합)
 *
 * 윈도우 (ADR 0086 §2-3 LOCK):
 *   - 최근 30 거래일 (default windowDays=30)
 *   - KIS 호출 cost 보호 + in-memory diff O(1) (30 거래일 × 1m = 21,600 ts/종목 in-memory)
 *
 * 자본 영향 0 — 조회만 수행 + 데이터 변경 0건.
 *
 * 룰 5 위반 0: LLM provider import 0건 (ADR 0023 정합).
 */
@Component
class CandleGapDetectorAdapter(
    private val candleRepository: CandleRepository,
    private val businessDayCalendar: BusinessDayCalendar,
    private val instrumentJpaRepository: ConsumerInstrumentJpaRepository,
) : CandleGapDetectorPort {
    private val log = LoggerFactory.getLogger(CandleGapDetectorAdapter::class.java)

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** KRX 정규장 시작 시각 (KST) — 1m expected sequence 시작점 */
        private const val MARKET_OPEN_HOUR = 9
        private const val MARKET_OPEN_MIN = 0

        /** KRX 정규장 마감 시각 (KST) — 15:30 → 1m expected sequence 종료점 (15:29 마지막) */
        private const val MARKET_CLOSE_HOUR = 15
        private const val MARKET_CLOSE_MIN = 30

        /** 1d expected ts 정규화 — 00:00 KST (Candle.ts 정합 baseline) */
        private const val DAILY_TS_HOUR = 0
        private const val DAILY_TS_MIN = 0
    }

    override fun detectGaps(
        code: String,
        interval: CandleInterval,
        windowDays: Int,
    ): List<Instant> {
        require(windowDays in 1..365) { "windowDays out of range: $windowDays (1..365)" }
        // 햇징 룰 #6 false positive 가드 — 거래정지·정리매매 skip
        val instrument = instrumentJpaRepository.findById(code).orElse(null)
        if (instrument != null && (instrument.tradingHalt || instrument.liquidation)) {
            log.info(
                "CandleGapDetector skip — 거래정지·정리매매: code={} halt={} liquidation={}",
                code,
                instrument.tradingHalt,
                instrument.liquidation,
            )
            return emptyList()
        }

        // expected sequence 생성 (영업일 30거래일 — BusinessDayCalendar 의무 호출)
        val nowKst = LocalDate.now(KST)
        val expectedDates = computeExpectedBusinessDays(nowKst, windowDays)
        if (expectedDates.isEmpty()) {
            log.warn("CandleGapDetector — expected business days 0: code={} interval={}", code, interval.code)
            return emptyList()
        }

        val expected: List<Instant> =
            when (interval) {
                CandleInterval.ONE_DAY -> expectedDailyTs(expectedDates)
                CandleInterval.ONE_MIN -> expectedMinuteTs(expectedDates)
                else -> {
                    log.warn(
                        "CandleGapDetector — interval={} 미지원 (Phase 2-A는 1d·1m만 LOCK)",
                        interval.code,
                    )
                    return emptyList()
                }
            }

        if (expected.isEmpty()) return emptyList()

        // actual sequence 조회 — 범위 from·to (inclusive)
        val from = expected.first()
        val to = expected.last()
        val actual = candleRepository.findAllTsByCodeAndIntervalBetween(code, interval, from, to)
        val actualSet: Set<Instant> = HashSet(actual)

        // diff — gaps (expected에 있는데 actual에 없는 ts)
        val gaps = expected.filterNot { it in actualSet }

        log.info(
            "CandleGapDetector: code={} interval={} windowDays={} expected={} actual={} gaps={}",
            code,
            interval.code,
            windowDays,
            expected.size,
            actual.size,
            gaps.size,
        )
        return gaps
    }

    /**
     * 영업일 expected dates 생성 — 최근 windowDays 거래일 (BusinessDayCalendar 의무 호출).
     *
     * 알고리즘:
     *   - cursor = nowKst (오늘 포함 X — 장 진행 중 false positive 회피)
     *   - cursor 이전 거래일 windowDays개 누적 (calendar.isBusinessDay() 호출)
     *   - 결과 ASC 정렬
     */
    private fun computeExpectedBusinessDays(
        nowKst: LocalDate,
        windowDays: Int,
    ): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var cursor = nowKst.minusDays(1) // 오늘 제외 (장 진행 중)
        val maxLookback = windowDays * 3L // 휴일·주말 비율 가드 (3배 안전)
        var iterCount = 0L
        while (dates.size < windowDays && iterCount < maxLookback) {
            if (businessDayCalendar.isBusinessDay(cursor)) {
                dates.add(cursor)
            }
            cursor = cursor.minusDays(1)
            iterCount++
        }
        return dates.sorted() // ASC
    }

    /**
     * 일봉 expected ts — 각 거래일 00:00 KST → UTC Instant.
     * Candle.ts 적재 baseline 정합 (KIS daily candle ts는 KST 00:00 기준).
     */
    private fun expectedDailyTs(dates: List<LocalDate>): List<Instant> =
        dates.map { d ->
            ZonedDateTime.of(d, java.time.LocalTime.of(DAILY_TS_HOUR, DAILY_TS_MIN), KST).toInstant()
        }

    /**
     * 1분봉 expected ts — 각 거래일 09:00~15:29 KST (390 bar/일, 6.5h × 60분).
     *
     * NXT cover (08:00~20:00) Phase 후속 — 1차 MVP는 정규장만 LOCK
     * (false positive 가드 — NXT 미시드 종목 무의미 호출 회피).
     *
     * 윈도우 30 거래일 × 390 = 11,700 ts/종목 (in-memory HashSet diff O(1)).
     */
    private fun expectedMinuteTs(dates: List<LocalDate>): List<Instant> {
        val result = ArrayList<Instant>(dates.size * 390)
        for (d in dates) {
            val open = ZonedDateTime.of(d, java.time.LocalTime.of(MARKET_OPEN_HOUR, MARKET_OPEN_MIN), KST)
            // 09:00 ~ 15:29 → 391 bar (정규장)
            // 15:30 정각은 마감 시각 → 마감 직전까지만 expected (마감 bar는 KIS 적재 정책에 따라 미생성 가능)
            val totalBars = (MARKET_CLOSE_HOUR - MARKET_OPEN_HOUR) * 60 + (MARKET_CLOSE_MIN - MARKET_OPEN_MIN)
            for (i in 0 until totalBars) {
                result.add(open.plusMinutes(i.toLong()).toInstant())
            }
        }
        return result
    }
}
