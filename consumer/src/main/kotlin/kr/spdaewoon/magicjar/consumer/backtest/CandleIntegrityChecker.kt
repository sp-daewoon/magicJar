package kr.spdaewoon.magicjar.consumer.backtest

import kr.spdaewoon.magicjar.domain.market.candle.CandleConfidence
import kr.spdaewoon.magicjar.domain.market.candle.CandleIntegrityReport
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 캔들 정합성 검사기 — ADR 0132 기둥3 §D3 (mock 절대 금지 LOCK · R2a).
 *
 * backfill 후 거래일별 **기대 봉 수 대비 실제 봉 수**(결측률)로 백테스트 결과 신뢰도를 등급화한다.
 * 결측 봉을 직전봉 복제·선형보간 등으로 **절대 메우지 않고**([feedback_no_mock_data]) 등급([CandleConfidence])
 * 으로만 정직하게 노출한다(거짓 수익률보다 "신뢰도 낮음/데이터 부족"이 정직).
 *
 * ## 기대 봉 수 산정 (candle-data-policy-v2 §3-B 근사)
 *   - 1m 베이스는 **KRX 정규장 390 bar/일**(09:00~15:30) 기준. ADR 0118 amend 로 REST 는 J(본시장) 단일
 *     채널이므로 NXT 720 bar 가 아닌 본시장 390 bar 가 정합(NXT 분 제외).
 *   - interval 별 기대 봉/일 = ceil(390 / interval 분).
 *   - 기대 봉 = (interval 별 기대 봉/일) × 거래일 수(주말 제외 근사 — 공휴일은 post-MVP 정밀화).
 *
 * ## mock 금지 단언
 *   본 checker 는 candle 을 **생성·수정하지 않는다**. DB 실제 봉 수만 count 하여 결측률을 산출한다.
 *   결측을 메우는 어떤 경로도 없음(읽기 전용 · CandleRepository.findAllTsByCodeAndIntervalBetween count 만).
 */
@Component
class CandleIntegrityChecker(
    private val candleRepository: CandleRepository,
) {
    private val log = LoggerFactory.getLogger(CandleIntegrityChecker::class.java)

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * KRX 정규장 분 수 — 09:00~15:30 = 6시간 30분 = 390분 (390 bar/일 1m 기준).
         * candle-data-policy-v2 §3-B + ADR 0118 amend(J 단일·본시장 390 bar).
         */
        const val KRX_REGULAR_SESSION_MINUTES: Int = 390

        /**
         * interval code → interval 분. 분봉 패밀리만 정합성 검사 대상(1d 이상은 본 checker scope 밖).
         */
        private val INTERVAL_MINUTES: Map<String, Int> =
            mapOf(
                CandleInterval.ONE_MIN.code to 1,
                CandleInterval.TWO_MIN.code to 2,
                CandleInterval.THREE_MIN.code to 3,
                CandleInterval.FIVE_MIN.code to 5,
                CandleInterval.FIFTEEN_MIN.code to 15,
                CandleInterval.THIRTY_MIN.code to 30,
                CandleInterval.NINETY_MIN.code to 90,
            )

        /**
         * interval·거래일 수 → 기대 봉 수.
         *
         * 기대 봉/일 = ceil(390 / interval 분). 예: 1m=390 · 3m=130 · 5m=78 · 15m=26 · 30m=13 · 90m=5(ceil).
         */
        fun expectedBarsFor(
            intervalCode: String,
            tradingDays: Int,
        ): Int {
            if (tradingDays <= 0) return 0
            val minutes =
                INTERVAL_MINUTES[intervalCode]
                    ?: throw IllegalArgumentException("CandleIntegrityChecker 미지원 interval(분봉 아님): $intervalCode")
            val barsPerDay = (KRX_REGULAR_SESSION_MINUTES + minutes - 1) / minutes
            return barsPerDay * tradingDays
        }

        /**
         * [from]~[to](inclusive) 사이 거래일 수(주말 제외 근사). 공휴일 미반영 — KIS 응답 0건이 자연 방어.
         * post-MVP 에서 KRX 영업일 캘린더로 정밀화(ADR 0132 §D3-1).
         */
        fun tradingDaysBetween(
            from: LocalDate,
            to: LocalDate,
        ): Int {
            if (from.isAfter(to)) return 0
            var count = 0
            var cur = from
            while (!cur.isAfter(to)) {
                val dow = cur.dayOfWeek
                if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) count++
                cur = cur.plusDays(1)
            }
            return count
        }
    }

    /**
     * 종목 × interval × [from,to] 정합성 보고 산출 — DB 실제 봉 수만 읽어 결측률·등급 산정(mock 0).
     *
     * @param code 종목코드.
     * @param interval 분봉 interval.
     * @param from 보고 시작일(inclusive · clamp 후 경계).
     * @param to 보고 종료일(inclusive).
     * @param lookbackClamped 요청 from 이 KIS 1년 bound·ENV 로 clamp 됐는지(거짓 결과 차단 경고).
     * @param lowThreshold LOW 경계(ENV 주입 가능 · default 5%).
     * @param insufficientThreshold INSUFFICIENT 경계(ENV 주입 가능 · default 20%).
     */
    fun check(
        code: String,
        interval: CandleInterval,
        from: LocalDate,
        to: LocalDate,
        lookbackClamped: Boolean = false,
        lowThreshold: BigDecimal = CandleIntegrityReport.LOW_CONFIDENCE_THRESHOLD,
        insufficientThreshold: BigDecimal = CandleIntegrityReport.INSUFFICIENT_THRESHOLD,
    ): CandleIntegrityReport {
        val tradingDays = tradingDaysBetween(from, to)
        val expectedBars = expectedBarsFor(interval.code, tradingDays)

        // DB 실제 봉 count — 읽기 전용. 결측을 메우는 경로 없음(mock 금지 LOCK).
        val rangeFrom = from.atStartOfDay(KST).toInstant()
        val rangeTo = toExclusiveDayEnd(to)
        val actualBars =
            candleRepository
                .findAllTsByCodeAndIntervalBetween(code, interval, rangeFrom, rangeTo)
                .size

        val missingBars = (expectedBars - actualBars).coerceAtLeast(0)
        val missingRatio =
            if (expectedBars <= 0) {
                BigDecimal.ZERO
            } else {
                BigDecimal(missingBars)
                    .divide(BigDecimal(expectedBars), 6, RoundingMode.HALF_UP)
            }
        val confidence =
            CandleIntegrityReport.classify(
                missingRatio = missingRatio,
                lowThreshold = lowThreshold,
                insufficientThreshold = insufficientThreshold,
            )

        log.info(
            "CandleIntegrity: code={} interval={} tradingDays={} expected={} actual={} missing={} ratio={} " +
                "confidence={} clamped={}",
            code,
            interval.code,
            tradingDays,
            expectedBars,
            actualBars,
            missingBars,
            missingRatio,
            confidence,
            lookbackClamped,
        )

        return CandleIntegrityReport(
            code = code,
            interval = interval.code,
            expectedBars = expectedBars,
            actualBars = actualBars,
            missingBars = missingBars,
            missingRatio = missingRatio,
            confidence = confidence,
            tradingDaysCovered = tradingDays,
            lookbackClamped = lookbackClamped,
        )
    }

    private fun toExclusiveDayEnd(to: LocalDate): Instant =
        to
            .plusDays(1)
            .atStartOfDay(KST)
            .minusNanos(1)
            .toInstant()
}
