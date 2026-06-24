package kr.spdaewoon.magicjar.consumer.backtest

import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner
import kr.spdaewoon.magicjar.consumer.config.BacktestIntradayProperties
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestCandlePreparePort
import kr.spdaewoon.magicjar.domain.market.candle.CandleIntegrityReport
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisMinuteBackfillRangePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.LocalDate as JavaLocalDate

/**
 * [BacktestCandlePreparePort] consumer(market-data 어댑터) 구현 — ADR 0132 기둥2·3 (R2a).
 *
 * INTRADAY_REALISTIC 백테스트 엔진(api)이 호출하는 **동기 선수집** 어댑터:
 *   1. universe × interval × [from,to] 의 lookback 을 KIS 1년 bound·ENV `max-lookback-days` 로 clamp.
 *   2. (backfillEnabled 시) 각 종목 1m 분봉을 [KisMinuteBackfillRangePort.fetchOneMinuteCandlesForBackfill]
 *      (**BACKFILL 버킷** — 라이브 매매·warmup 쿼터 침범 0)로 fetch → save → [AggregateBackfillRunner] 로 3m/5m
 *      등 집계봉 재생성([SymbolDetailMinuteGapFillService] 패턴 재사용).
 *   3. backfill 후 [CandleIntegrityChecker] 로 기대/실제 봉수 대비 정합성 보고 — **mock 보간 0**(결측은 등급으로만).
 *
 * ## clamp (lookback bound · 거짓 결과 차단)
 *   요청 from 이 `to - max-lookback-days(KIS 1년 ∧ ENV)` 보다 과거면 그 경계로 clamp 하고
 *   [CandleIntegrityReport.lookbackClamped]=true 로 표기(KIS 가 어차피 반환하지 않는 과거 구간을 기대봉수에
 *   넣어 거짓 결측률을 만들지 않도록).
 *
 * ## fail-soft (라이브 보호 · 정직 노출)
 *   특정 종목 backfill 이 KIS rate-limit/EGW00201/credential 결함으로 실패해도 다른 종목 backfill 은 계속한다.
 *   실패 종목은 backfill 없이 DB 기존 봉만으로 정합성 검사 → 결측률 높으면 INSUFFICIENT_DATA 등급으로 노출
 *   (엔진이 skip 판정). mock 으로 메우지 않는다.
 */
@Component
class BacktestCandlePrepareAdapter(
    private val candleRepository: CandleRepository,
    private val kisMinuteBackfillRangePort: KisMinuteBackfillRangePort,
    private val aggregateBackfillRunner: AggregateBackfillRunner,
    private val candleIntegrityChecker: CandleIntegrityChecker,
    private val backtestIntradayProperties: BacktestIntradayProperties,
) : BacktestCandlePreparePort {
    private val log = LoggerFactory.getLogger(BacktestCandlePrepareAdapter::class.java)

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private const val SOURCE_KIS_BACKTEST_BACKFILL = "KIS-BACKTEST-BACKFILL"
    }

    override fun prepareAndReport(
        universe: List<String>,
        interval: String,
        from: LocalDate,
        to: LocalDate,
        backfillEnabled: Boolean,
    ): Map<String, CandleIntegrityReport> {
        require(universe.isNotEmpty()) { "BacktestCandlePrepareAdapter: universe 비어 있음" }

        val targetInterval = CandleInterval.fromCode(interval)
        val javaFrom: JavaLocalDate = from.toJavaLocalDate()
        val javaTo: JavaLocalDate = to.toJavaLocalDate()
        require(!javaFrom.isAfter(javaTo)) { "BacktestCandlePrepareAdapter: from > to (from=$from to=$to)" }

        // lookback clamp — KIS 1년 bound ∧ ENV max-lookback-days.
        val maxLookbackDays = backtestIntradayProperties.normalizedMaxLookbackDays().toLong()
        val boundFrom = javaTo.minusDays(maxLookbackDays - 1)
        val clamped = javaFrom.isBefore(boundFrom)
        val effectiveFrom = if (clamped) boundFrom else javaFrom
        if (clamped) {
            log.warn(
                "[LOOKBACK_CLAMPED] backtest 분봉 lookback clamp: requestedFrom={} → {} (max-lookback-days={} · KIS 1년 bound)",
                javaFrom,
                effectiveFrom,
                maxLookbackDays,
            )
        }

        log.info(
            "BacktestCandlePrepare 시작: universe={} interval={} from={} to={} backfillEnabled={} clamped={}",
            universe.size,
            interval,
            effectiveFrom,
            javaTo,
            backfillEnabled,
            clamped,
        )

        val reports = LinkedHashMap<String, CandleIntegrityReport>(universe.size)
        for (code in universe) {
            if (backfillEnabled) {
                backfillOneSymbol(code, effectiveFrom, javaTo)
            }
            reports[code] =
                candleIntegrityChecker.check(
                    code = code,
                    interval = targetInterval,
                    from = effectiveFrom,
                    to = javaTo,
                    lookbackClamped = clamped,
                )
        }
        return reports
    }

    /**
     * 종목 1m 분봉 BACKFILL fetch → save → 집계봉 재생성. fail-soft(예외 삼킴 — 정합성 등급이 결측 노출).
     */
    private fun backfillOneSymbol(
        code: String,
        from: JavaLocalDate,
        to: JavaLocalDate,
    ) {
        val fromInstant = from.atStartOfDay(KST).toInstant()
        val toInstant =
            to
                .plusDays(1)
                .atStartOfDay(KST)
                .minusNanos(1)
                .toInstant()
        try {
            val fetched =
                kisMinuteBackfillRangePort.fetchOneMinuteCandlesForBackfill(code, fromInstant, toInstant)
            if (fetched.isEmpty()) {
                log.info("BacktestCandlePrepare backfill 0건(uncovered): code={} from={} to={}", code, from, to)
                return
            }
            val inserted =
                candleRepository.saveAll(
                    fetched.map { it.copy(source = SOURCE_KIS_BACKTEST_BACKFILL) },
                )
            if (inserted > 0) {
                val aggregate = aggregateBackfillRunner.backfill(code)
                log.info(
                    "BacktestCandlePrepare backfill 완료 + 합산봉 재생성: code={} fetched={} inserted={} aggregate={}",
                    code,
                    fetched.size,
                    inserted,
                    aggregate,
                )
            } else {
                log.info(
                    "BacktestCandlePrepare backfill 완료(신규 0 — 기존 충분): code={} fetched={}",
                    code,
                    fetched.size,
                )
            }
        } catch (e: Exception) {
            // fail-soft: 라이브 보호 + 정직 노출. backfill 실패해도 다른 종목 계속, 본 종목은 기존 봉으로 정합성 평가.
            log.warn(
                "BacktestCandlePrepare backfill 실패(fail-soft — 정합성 등급으로 노출): code={} from={} to={} err={}",
                code,
                from,
                to,
                e.message,
            )
        }
    }
}
