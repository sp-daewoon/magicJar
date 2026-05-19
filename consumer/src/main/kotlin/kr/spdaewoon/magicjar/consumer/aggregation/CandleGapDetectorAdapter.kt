package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleGapDetectorPort
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * CandleGapDetectorPort 구현 — consumer 모듈 (ADR 0086 §2-2 + T-CC Phase 2-A/B).
 *
 * 책임:
 *   - 영업일 캘린더 기반 expected sequence 생성 (BusinessDayCalendar.isBusinessDay() 의무 호출)
 *   - DB actual sequence 조회 (CandleRepository.findAllTsByCodeAndIntervalBetween)
 *   - HashSet diff O(1) — gaps 산출 (ASC)
 *
 * 햇징 룰 적용 (ADR 0086 §2-1):
 *   - 룰 #6 false positive 가드 (passive):
 *       * 거래정지·정리매매 종목 → empty list 반환 (ColdStartExecutor KIS_COLD_START_SKIPPED 정합)
 *       * 휴장일 제외 (BusinessDayCalendar.isBusinessDay() 호출)
 *       * 시간외(NXT) — Phase 2-A 1차 MVP 1m 갭은 정규장 09:00~15:30 KST만 cover (NXT cover는 Phase 후속)
 *   - 룰 #7 false negative 가드: actual 0건이면 expected 전체 = 갭 list (last_ts null Phase 1 패턴 정합)
 *   - **룰 §12 Active gap detection 4축 교차 검증 (Phase 2-B amend, ADR 0086 §2-7)**:
 *       1축: `market_calendar.is_business_day=TRUE` (BusinessDayCalendar 권위 — 위 #6과 동일 호출)
 *       2축: `instrument.trading_halt=FALSE` (거래정지 history 부재 — entity snapshot 1차 MVP)
 *       3축: `instrument.liquidation=FALSE` (정리매매 부재)
 *       4축: `instrument.listed_at <= ts` (상장일 이후 ts)
 *     → 4축 모두 TRUE인데 candle row 부재 = 명확한 결함 → audit `CANDLE_GAP_DETECTED` 발행.
 *     사용자(최고 관리자) 명시 정합 룰. 임의 임계치("5+ 영업일 연속") 영구 폐기.
 *     **현행 한계 (Phase 2-B 1차 MVP)**: 거래정지·정리매매 history snapshot 부재 → 현재 entity flag만으로 판단.
 *     정확한 history 검증은 Phase 3 (instrument_status_history 별도 테이블) 의제.
 *
 * 윈도우 (ADR 0086 §2-3 LOCK):
 *   - 최근 30 거래일 (default windowDays=30)
 *   - KIS 호출 cost 보호 + in-memory diff O(1) (30 거래일 × 1m = 21,600 ts/종목 in-memory)
 *
 * 자본 영향 0 — 조회만 수행 + 데이터 변경 0건 (audit_trail row만 추가).
 *
 * 룰 5 위반 0: LLM provider import 0건 (ADR 0023 정합).
 */
@Component
class CandleGapDetectorAdapter(
    private val candleRepository: CandleRepository,
    private val businessDayCalendar: BusinessDayCalendar,
    private val instrumentJpaRepository: ConsumerInstrumentJpaRepository,
    private val auditRepositoryProvider: ObjectProvider<ConsumerAuditTrailJpaRepository>,
    private val objectMapperProvider: ObjectProvider<ObjectMapper>,
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
        // 햇징 룰 #6 false positive 가드 — 거래정지·정리매매 skip (passive — entity 단위)
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

        // expected sequence 생성 (영업일 30거래일 — BusinessDayCalendar 의무 호출 — §12 1축)
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

        // §12 4축 교차 검증 — 각 ts별로 axes 2·3·4 통과 여부 필터 (axis 1은 위 expectedDates에서 이미 적용)
        // axis 2: instrument.trading_halt = FALSE (entity 단위 — 위 early-return으로 이미 통과)
        // axis 3: instrument.liquidation = FALSE (위 early-return으로 이미 통과)
        // axis 4: instrument.listed_at <= ts (ts가 상장일 이전이면 false positive — 데이터 부재가 정상)
        val verifiedExpected = expected.filter { ts -> passesActiveGapAxes(instrument, ts) }
        if (verifiedExpected.isEmpty()) {
            log.info(
                "CandleGapDetector — 4축 통과 ts 0건 (상장일 이전 등): code={} interval={} rawExpected={}",
                code,
                interval.code,
                expected.size,
            )
            return emptyList()
        }

        // actual sequence 조회 — 범위 from·to (inclusive · 4축 통과 ts 기준)
        val from = verifiedExpected.first()
        val to = verifiedExpected.last()
        val actual = candleRepository.findAllTsByCodeAndIntervalBetween(code, interval, from, to)
        val actualSet: Set<Instant> = HashSet(actual)

        // diff — gaps (4축 통과 expected 중 actual에 없는 ts) → §12 active 결함 정의 적용
        val gaps = verifiedExpected.filterNot { it in actualSet }

        log.info(
            "CandleGapDetector: code={} interval={} windowDays={} expected={} verified={} actual={} gaps={}",
            code,
            interval.code,
            windowDays,
            expected.size,
            verifiedExpected.size,
            actual.size,
            gaps.size,
        )

        // §12 audit_trail CANDLE_GAP_DETECTED 발행 (gaps > 0 시) — 4축 evidence inline
        if (gaps.isNotEmpty()) {
            recordGapDetectedAudit(
                code = code,
                interval = interval,
                windowDays = windowDays,
                instrument = instrument,
                rawExpected = expected.size,
                verifiedExpected = verifiedExpected.size,
                actualCount = actual.size,
                gaps = gaps,
            )
        }

        return gaps
    }

    /**
     * §12 4축 교차 검증 중 axis 2·3·4 통과 여부 (axis 1은 expectedDates 생성 단계에서 이미 적용).
     *
     * axis 2 (trading_halt=FALSE) + axis 3 (liquidation=FALSE):
     *   detectGaps() 진입 early-return으로 이미 통과 보장 (이 함수 호출 시점에는 무조건 통과 상태).
     *   다만 instrument 자체가 null이면 trading_halt·liquidation flag 추론 불가 → "정보 없음" → 통과로 간주
     *   (false positive 회피 — 미지정 종목은 갭 검증 진행).
     *
     * axis 4 (listed_at <= ts):
     *   instrument.listedAt null 시 "상장일 미상" → 통과로 간주 (false positive 회피).
     *   listedAt 존재 시 ts가 상장일 이전이면 skip — 정상 부재(상장 전 데이터 없음).
     *
     * ts → KST LocalDate 변환 후 비교 (listed_at은 LocalDate).
     */
    private fun passesActiveGapAxes(
        instrument: ConsumerInstrumentJpaEntity?,
        ts: Instant,
    ): Boolean {
        // axis 4 — 상장일 이후 ts 검증
        val listedAt = instrument?.listedAt ?: return true // 상장일 미상 → 통과 (false positive 회피)
        val tsKstDate = ts.atZone(KST).toLocalDate()
        return !tsKstDate.isBefore(listedAt)
    }

    /**
     * §12 audit_trail `CANDLE_GAP_DETECTED` 발행 — 4축 evidence inline.
     *
     * detail JSON 구조:
     *   {
     *     "code": "004090",
     *     "interval": "1d",
     *     "windowDays": 30,
     *     "rawExpected": 30,
     *     "verifiedExpected": 28,    // 4축 통과 ts 수
     *     "actualCount": 23,
     *     "gapCount": 5,
     *     "gapsSample": ["2026-05-15T00:00:00Z", ...],  // 상위 10개
     *     "axes": {                  // 4축 권위 source evidence
     *       "businessDayCalendar": true,         // axis 1 — 항상 true (filter 결과)
     *       "tradingHalt": false,                // axis 2
     *       "liquidation": false,                // axis 3
     *       "listedAt": "2010-12-09"            // axis 4 (null 시 "unknown")
     *     }
     *   }
     */
    private fun recordGapDetectedAudit(
        code: String,
        interval: CandleInterval,
        windowDays: Int,
        instrument: ConsumerInstrumentJpaEntity?,
        rawExpected: Int,
        verifiedExpected: Int,
        actualCount: Int,
        gaps: List<Instant>,
    ) {
        val auditRepo = auditRepositoryProvider.getIfAvailable() ?: return
        val objectMapper = objectMapperProvider.getIfAvailable() ?: return
        val detail =
            mapOf(
                "code" to code,
                "interval" to interval.code,
                "windowDays" to windowDays,
                "rawExpected" to rawExpected,
                "verifiedExpected" to verifiedExpected,
                "actualCount" to actualCount,
                "gapCount" to gaps.size,
                "gapsSample" to gaps.take(10).map { it.toString() },
                "axes" to
                    mapOf(
                        "businessDayCalendar" to true,
                        "tradingHalt" to (instrument?.tradingHalt ?: false),
                        "liquidation" to (instrument?.liquidation ?: false),
                        "listedAt" to (instrument?.listedAt?.toString() ?: "unknown"),
                    ),
            )
        val detailJson =
            try {
                objectMapper.writeValueAsString(detail)
            } catch (e: Exception) {
                log.warn("CandleGapDetector audit JSON 직렬화 실패: code={} err={}", code, e.message)
                "{}"
            }
        try {
            auditRepo.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "chart-curator",
                    action = "CANDLE_GAP_DETECTED",
                    entityType = "candle",
                    entityId = code,
                    detailJson = detailJson,
                ),
            )
        } catch (e: Exception) {
            log.warn("CandleGapDetector audit 저장 실패: code={} err={}", code, e.message)
        }
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
