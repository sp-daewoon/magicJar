package kr.spdaewoon.magicjar.api.service.backtest

import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import kr.spdaewoon.magicjar.api.config.IntradayRealisticBacktestProperties
import kr.spdaewoon.magicjar.api.service.CandleRefreshService
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestCandlePreparePort
import kr.spdaewoon.magicjar.domain.market.candle.CandleIntegrityReport
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.LocalDate as JavaLocalDate

/**
 * [BacktestCandlePreparePort] **api in-process 구현** — ADR 0133 §3-A1 (#4 · 대안 C · consumer → api 통합).
 *
 * ## 배경 (ADR 0133 #4 — cross-process 단절 해소)
 * ADR 0132 R2a 는 본 포트 구현을 consumer 에 뒀으나, 호출자 [JvmBacktestEngine] 은 **api 프로세스**라 api 에서
 * 빈 null → `@Autowired(required=false)` → backfill 0(분봉 보유 종목만 백테스트 가능). 본 어댑터는 그 구현을
 * api 로 옮겨 **in-process 정상 주입**되게 한다(엔진 호출부·port 시그니처 불변).
 *
 * ## 신규 KIS 호출 0 (기존 능력 재사용 — DRY)
 * api [CandleRefreshService] 가 **이미** KIS 1m(FHKST03010230) fetch + upsert + 합산봉 재생성 +
 * `KisCallPriority.BACKFILL` + EGW backoff 를 보유한다. 본 어댑터는 그 add-only entrypoint
 * [CandleRefreshService.backfillIntradayRange] 를 종목별로 호출하는 **얇은 조립**일 뿐, 새 KIS 어댑터를 만들지
 * 않는다(consumer 원본은 `KisMinuteBackfillRangePort` + `AggregateBackfillRunner` 를 직접 조립했으나, api 는
 * 그 능력이 `CandleRefreshService` 단일 진입점으로 이미 응집돼 있어 더 간결).
 *
 * ## 단계 (포트 계약 4단계)
 *   1. lookback clamp — KIS 1년 bound ∧ ENV [IntradayRealisticBacktestProperties.maxLookbackDays].
 *   2. (backfillEnabled 시) 각 종목 [CandleRefreshService.backfillIntradayRange] — BACKFILL 1m fetch + 합산 재생성.
 *   3. [CandleIntegrityChecker.check] 로 종목별 기대/실제 봉수 대비 정합성 보고 — **mock 보간 0**(결측은 등급으로만).
 *   4. backfill 여부와 무관하게 universe 전 종목 보고 반환.
 *
 * ## fail-soft (라이브 보호 · 정직 노출)
 *   특정 종목 backfill 이 KIS rate-limit/EGW00201/credential 결함으로 실패해도 다른 종목 backfill 은 계속한다.
 *   실패 종목은 backfill 없이 DB 기존 봉만으로 정합성 검사 → 결측률 높으면 INSUFFICIENT_DATA 등급으로 노출
 *   (엔진이 skip 판정). mock 으로 메우지 않는다.
 *
 * ## 빈 단일성 LOCK (ADR 0133 §3-A2)
 *   api 프로세스에 본 포트 구현은 **정확히 1개**(consumer 의 BacktestCandlePrepareAdapter 는 본 PR 에서 삭제) —
 *   `NoUniqueBeanDefinitionException` 0.
 *
 * ## 진보 페르소나 결정 메모 (ADR 0087)
 *   - 🔄 consumer 의 3-부품 조립(KisMinuteBackfillRangePort + saveAll + AggregateBackfillRunner)을
 *     CandleRefreshService 단일 entrypoint 위임으로 축약 — 보일러플레이트 ~40줄 제거(DRY).
 *   - 💡 lookback clamp 를 ENV props 단일 출처([IntradayRealisticBacktestProperties])에서 — drift 0.
 */
@Component
class BacktestCandlePrepareApiAdapter(
    private val candleRefreshService: CandleRefreshService,
    private val candleIntegrityChecker: CandleIntegrityChecker,
    private val properties: IntradayRealisticBacktestProperties,
) : BacktestCandlePreparePort {
    private val log = LoggerFactory.getLogger(BacktestCandlePrepareApiAdapter::class.java)

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** KIS 분봉 보관 한계(최대 1년) 달력일 hard bound — KIS-first raw 원문 정합(거짓 기대봉수 차단). */
        const val KIS_HARD_BOUND_CALENDAR_DAYS: Int = 365
    }

    override fun prepareAndReport(
        universe: List<String>,
        interval: String,
        from: LocalDate,
        to: LocalDate,
        backfillEnabled: Boolean,
    ): Map<String, CandleIntegrityReport> {
        require(universe.isNotEmpty()) { "BacktestCandlePrepareApiAdapter: universe 비어 있음" }

        val targetInterval = CandleInterval.fromCode(interval)
        val javaFrom: JavaLocalDate = from.toJavaLocalDate()
        val javaTo: JavaLocalDate = to.toJavaLocalDate()
        require(!javaFrom.isAfter(javaTo)) { "BacktestCandlePrepareApiAdapter: from > to (from=$from to=$to)" }

        // lookback clamp — KIS 1년 bound ∧ ENV max-lookback-days(props 가 >=1 정규화, 여기서 KIS 365 상한 추가 clamp).
        val maxLookbackDays =
            properties.normalizedMaxLookbackDays().coerceAtMost(KIS_HARD_BOUND_CALENDAR_DAYS).toLong()
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
            "BacktestCandlePrepare(api) 시작: universe={} interval={} from={} to={} backfillEnabled={} clamped={}",
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
     * 종목 1m 분봉 BACKFILL fetch + 합산봉 재생성 — [CandleRefreshService.backfillIntradayRange] 위임.
     * fail-soft(예외 삼킴 — 정합성 등급이 결측 노출).
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
            val results = candleRefreshService.backfillIntradayRange(code, fromInstant, toInstant)
            val oneMin = results.firstOrNull { it.interval == CandleInterval.ONE_MIN.code }
            log.info(
                "BacktestCandlePrepare(api) backfill 완료: code={} from={} to={} 1mFetched={} 1mInserted={} aggregateRebuilt={}",
                code,
                from,
                to,
                oneMin?.fetchedCount ?: 0,
                oneMin?.insertedCount ?: 0,
                results.size - (if (oneMin != null) 1 else 0),
            )
        } catch (e: RuntimeException) {
            // fail-soft: 라이브 보호 + 정직 노출. backfill 실패해도 다른 종목 계속, 본 종목은 기존 봉으로 정합성 평가.
            log.warn(
                "BacktestCandlePrepare(api) backfill 실패(fail-soft — 정합성 등급으로 노출): code={} from={} to={} err={}",
                code,
                from,
                to,
                e.message,
            )
        }
    }
}
