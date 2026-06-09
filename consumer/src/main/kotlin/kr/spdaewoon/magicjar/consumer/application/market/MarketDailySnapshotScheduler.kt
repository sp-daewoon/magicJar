package kr.spdaewoon.magicjar.consumer.application.market

import kr.spdaewoon.magicjar.consumer.aggregation.CandleBackfillWorker
import kr.spdaewoon.magicjar.consumer.config.MarketDailySnapshotProperties
import kr.spdaewoon.magicjar.consumer.config.TodayCloseAuthority
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisMultiPricePort
import kr.spdaewoon.magicjar.domain.market.port.MultiPriceQuote
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.datetime.Instant as KtInstant

/**
 * MARKET_DAILY 야간 멀티시세 snapshot 스케줄러 (ADR 0118 R2 — §2-C·§2-D).
 *
 * ## 무엇을 하는가
 * 매 영업일 23:00 KST 1회:
 *   1. 영업일 게이트([BusinessDayCalendar.isBusinessDay]) — 비영업일 skip.
 *   2. [MarketDailyUniverseProvider.resolveCodes] — 보통주 ~2,517(ADR 0118 결재 ① · 실측). WS 구독 0.
 *   3. 30종목 chunk → [KisMultiPricePort.fetchMultiPrice](FHKST11300006) — 총 ~84콜(불변, ceil(2517/30)).
 *   4. 응답 6필드 → **today(=오늘 거래일) 1d Candle 1개**로 변환 + sanity check.
 *   5. [CandleRepository.upsertAll] (ON CONFLICT DO UPDATE) — today 1d 봉을 공식 마감값으로 교정.
 *
 * ## ★ today 봉 공식 보정 경계 (§2-D — #833 deferred 흡수, 오적용 시 과거봉 오염 critical)
 * snapshot은 멀티시세 응답 = **그날 1개 시세**만 반환하므로, 변환 결과 Candle.ts는 **항상 today 1d**다.
 *   - today row → upsertAll(DO UPDATE) = 공식 마감값으로 덮어 장중 WS 집계 부정확분(구독 종목)
 *     + 비구독 전종목 today 봉을 교정.
 *   - 과거봉(today 이전)은 구조적으로 **건드릴 수 없다** — 응답에 과거 ts가 없으므로.
 *   - 즉 immutable 보존은 "today-only ts 생성"으로 보장된다(별도 ts 필터 불필요하나, ts=todayStart
 *     단일 고정으로 명시적 today-only LOCK). R-BF 과거봉(saveAll DO NOTHING, toDate=today-1)과 경계 0 겹침.
 *
 * ## authority chain (#833 연계 — 충돌 0)
 *   장중 WS seed(#833 seedTodayCandle Redis Hash) → WS 틱 갱신 → 20:10 DailyCloseFinalizer DB INSERT(DO NOTHING)
 *     → **23:00 본 snapshot DB UPDATE(공식 마감값 최종 확정)**.
 *   finalizer는 INSERT(없으면 채움)만, snapshot은 UPDATE(있으면 공식값으로 덮음)이므로 같은 today row를
 *   두 단계가 경합 없이 순차 강화한다. candle-data-policy 권위: 공식 마감값(snapshot) > WS tick 집계.
 *
 * ## NXT/정규장 분기 (§2-D 결재 ③ — 양쪽 빌드 + config 토글)
 *   [MarketDailySnapshotProperties.todayCloseAuthority]로 멀티시세 시장구분("NX" vs "J")이 갈린다.
 *   default = NXT_INTEGRATED(결재 ③ 값). 단 멀티시세 NXT 반환 여부는 R1 probe(KisMultiPriceNxtProbeIT)
 *   PENDING이므로, 정규장만 반환으로 실측되면 KRX_REGULAR_WITH_FINALIZER_FALLBACK로 토글 — follow-up.
 *   추측 단정 금지(헌법 §3) — 토글로 안전 분기.
 *
 * ## rate-limit · 운영 영향
 *   모든 KIS 콜 = BACKFILL(BG) 버킷 (어댑터 내부 LOCK — ADR 0118 §2-C·§5-1). USER_PATH·INGESTION 침범 0.
 *   ~84콜 ÷ 3/s ≈ 28초 + jitter → ~1~2분. 사용자·자본 path 영향 0.
 *   ★ enabled default = false (canary 전 신중 — ADR 0118 §6 롤백 toggle). off=야간 갱신 0.
 *
 * mock 합성 0 (응답 결손 → 해당 종목 skip = 데이터 없음 path). LLM 0(룰 5).
 */
@Component
class MarketDailySnapshotScheduler(
    private val marketDailyUniverseProvider: MarketDailyUniverseProvider,
    private val kisMultiPricePort: KisMultiPricePort,
    private val candleRepository: CandleRepository,
    private val candleBackfillWorker: CandleBackfillWorker,
    private val businessDayCalendar: BusinessDayCalendar,
    private val properties: MarketDailySnapshotProperties,
) {
    private val log = LoggerFactory.getLogger(MarketDailySnapshotScheduler::class.java)

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
        private const val INTERVAL_1D = "1d"

        /** snapshot source 마킹 — NXT 통합. DailyCloseFinalizer(KIS_DAILY_CLOSE)·R-BF와 구분. */
        private const val SOURCE_SNAPSHOT_NXT = "KIS-MARKET-DAILY-SNAPSHOT-NXT"

        /** snapshot source 마킹 — 정규장 한정(폴백 토글). */
        private const val SOURCE_SNAPSHOT_KRX = "KIS-MARKET-DAILY-SNAPSHOT-KRX"
    }

    /**
     * 매 영업일 23:00 KST 발사 (cron = magicjar.market-daily-snapshot.cron, default "0 0 23 * * *").
     *
     * cron의 zone은 @Scheduled 정적 속성이라 properties로 주입 불가 → cron 문자열만 SpEL로 외부화하고
     * zone은 "Asia/Seoul" 고정(MarketIndexScheduler·DailyCloseFinalizer 동일 패턴).
     * 비영업일은 [run] 내부 BusinessDayCalendar 게이트로 skip(2차 — cron은 매일 발사, 게이트가 영업일 판정).
     */
    @Scheduled(cron = "\${magicjar.market-daily-snapshot.cron:0 0 23 * * *}", zone = "Asia/Seoul")
    fun scheduled() {
        if (!properties.enabled) {
            log.debug("MarketDailySnapshot skip: enabled=false (canary 전 toggle off)")
            return
        }
        run(LocalDate.now(KST))
    }

    /**
     * snapshot 본체 — 영업일 게이트 → universe → chunk fetch → today 1d upsert.
     * (테스트·수동 트리거에서 date 주입 가능. 운영 cron은 [scheduled]가 today로 호출.)
     *
     * @return 처리 요약 [SnapshotResult] (관측·실측 evidence용).
     */
    fun run(today: LocalDate): SnapshotResult {
        // 1. 영업일 게이트 — 비영업일 skip (BusinessDayCalendar 재사용).
        if (!businessDayCalendar.isBusinessDay(today)) {
            log.info("MarketDailySnapshot skip: {} 비영업일", today)
            return SnapshotResult.skipped(today, "non-business-day")
        }

        // 2. MARKET_DAILY universe (보통주 ~2,517 · WS 구독 0). DB 0건 시 데이터 없음 path.
        val codes = marketDailyUniverseProvider.resolveCodes()
        if (codes.isEmpty()) {
            log.warn("MarketDailySnapshot: universe 0건 — instrument 미시드 또는 DB 결함 (데이터 없음 path)")
            return SnapshotResult.skipped(today, "empty-universe")
        }

        val authority = properties.todayCloseAuthority
        val mrktDivCode = properties.marketDivCode()
        val source = sourceFor(authority)
        val expectedCalls = (codes.size + KisMultiPriceChunk.SIZE - 1) / KisMultiPriceChunk.SIZE
        log.info(
            "MarketDailySnapshot 시작: date={} universe={}종목 chunk={} authority={} mrktDivCode={}",
            today,
            codes.size,
            expectedCalls,
            authority,
            mrktDivCode,
        )

        // today 1d ts: 해당 날짜 KST 00:00:00 (DailyCloseFinalizer·KIS stck_bsop_date 관행 정합).
        // ★ today-only LOCK — 모든 변환 봉의 ts가 이 단일 값. 과거 ts 생성 0 → 과거봉 immutable.
        val todayStartInstant = today.atStartOfDay(KST).toInstant()
        val todayTs: KtInstant = KtInstant.fromEpochSeconds(todayStartInstant.epochSecond)

        var fetched = 0
        var upserted = 0
        var sanityDropped = 0
        // 어댑터가 내부에서 30종목 chunk → 1콜. 여기서 전체 코드를 넘기면 어댑터가 chunk 분할 + 합산.
        val quotes: List<MultiPriceQuote> = kisMultiPricePort.fetchMultiPrice(codes, mrktDivCode)
        fetched = quotes.size

        // 응답 → today 1d Candle 변환 (결손 필드 종목 skip = 데이터 없음 path · mock 0).
        val candidates: List<Candle> =
            quotes.mapNotNull { quote -> toTodayCandle(quote, todayTs, source) }
        val toUpsertBeforeSanity = candidates.size

        // sanity check 재사용 (CandleBackfillWorker — 가격 range·OHLC·monotonic). 위반 봉 drop(과거봉 오염 방어).
        val sane = candleBackfillWorker.sanityCheck(candidates, "market-daily-snapshot-$today")
        sanityDropped = toUpsertBeforeSanity - sane.size

        if (sane.isNotEmpty()) {
            // ★ today 1d 봉만 upsertAll(DO UPDATE) — 공식 마감값 교정. (ts=todayTs 단일 → today-only LOCK)
            upserted = candleRepository.upsertAll(sane)
        }

        log.info(
            "MarketDailySnapshot 완료: date={} universe={} expectedCalls={} fetched={} " +
                "candidate={} sanityDropped={} upserted={} authority={}",
            today,
            codes.size,
            expectedCalls,
            fetched,
            toUpsertBeforeSanity,
            sanityDropped,
            upserted,
            authority,
        )

        return SnapshotResult(
            date = today,
            skipped = false,
            skipReason = null,
            universeSize = codes.size,
            expectedCalls = expectedCalls,
            fetched = fetched,
            sanityDropped = sanityDropped,
            upserted = upserted,
            authority = authority,
        )
    }

    /**
     * 멀티시세 단일 응답 → today 1d Candle 변환.
     *
     * OHLC 중 하나라도 null이면 봉 구성 불가 → skip(데이터 없음 path · mock 합성 0).
     * volume null → 0 보정(거래 정지/단일가 등 정상 케이스. sanity의 volume>=0 통과).
     * adj 필드는 snapshot 단계에서 null(수정주가 보정은 별도 corporate-action layer 책임).
     */
    internal fun toTodayCandle(
        quote: MultiPriceQuote,
        todayTs: KtInstant,
        source: String,
    ): Candle? {
        val open = quote.open
        val high = quote.high
        val low = quote.low
        val close = quote.close
        if (open == null || high == null || low == null || close == null) {
            // OHLC 결손 — 봉 구성 불가. 거래 정지·신규 상장 첫날 등 (데이터 없음 path).
            return null
        }
        return Candle(
            code = quote.code,
            interval = CandleInterval.ONE_DAY,
            ts = todayTs,
            openRaw = open,
            highRaw = high,
            lowRaw = low,
            closeRaw = close,
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = quote.accumulatedVolume ?: 0L,
            source = source,
        )
    }

    private fun sourceFor(authority: TodayCloseAuthority): String =
        when (authority) {
            TodayCloseAuthority.NXT_INTEGRATED -> SOURCE_SNAPSHOT_NXT
            TodayCloseAuthority.KRX_REGULAR_WITH_FINALIZER_FALLBACK -> SOURCE_SNAPSHOT_KRX
        }

    /** snapshot 처리 요약 (운영 관측·실측 evidence). */
    data class SnapshotResult(
        val date: LocalDate,
        val skipped: Boolean,
        val skipReason: String?,
        val universeSize: Int,
        val expectedCalls: Int,
        val fetched: Int,
        val sanityDropped: Int,
        val upserted: Int,
        val authority: TodayCloseAuthority,
    ) {
        companion object {
            fun skipped(
                date: LocalDate,
                reason: String,
            ): SnapshotResult =
                SnapshotResult(
                    date = date,
                    skipped = true,
                    skipReason = reason,
                    universeSize = 0,
                    expectedCalls = 0,
                    fetched = 0,
                    sanityDropped = 0,
                    upserted = 0,
                    authority = TodayCloseAuthority.NXT_INTEGRATED,
                )
        }
    }

    /** chunk 크기 상수 — 콜 수 산정용(어댑터 MAX_SYMBOLS_PER_CALL과 동일 raw spec 30). */
    private object KisMultiPriceChunk {
        const val SIZE = 30
    }
}
