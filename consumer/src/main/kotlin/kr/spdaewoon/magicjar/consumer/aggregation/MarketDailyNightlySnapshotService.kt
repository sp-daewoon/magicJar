package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.config.MarketDailySnapshotProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisMultiPricePort
import kr.spdaewoon.magicjar.domain.market.port.MultiPriceQuote
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.datetime.Instant as KtInstant

/**
 * MarketDailyNightlySnapshotService — ADR 0118 R2 야간 전종목 일봉 today snapshot 본체.
 *
 * ## 책임 (한 PR = 한 영역 · market-data)
 *   1. [MarketDailyUniverseProvider.resolveCodes] 보통주 ~2,517 코드 확보 (R1 재사용).
 *   2. **30종목 chunk** 로 [KisMultiPricePort.fetchMultiPrice](mrktDivCode="J") 호출 (~84콜) — chunk 간 spacing.
 *   3. 응답 6필드(open/high/low/close/volume) → **그날(today) 1d Candle** 변환 (ts=today 00:00 KST).
 *   4. [CandleRepository.upsertAll](ON CONFLICT DO UPDATE) — today 1d 봉을 KIS 공식 마감값으로 교정/적재.
 *   5. 관측 로그 + audit (처리 종목수·upsert 건수·실패·소요).
 *
 * ## R-BF / R2 / managed 3자 분업 (★ ADR 0118 §2-C·§2-D LOCK)
 *   - **R2(본 서비스, 23:00, 전종목)**: today 1d **1봉만** 멀티시세 공식 종가로 **DO UPDATE**(권위·후행 교정).
 *     과거 gap 채우기 아님 — 그건 R-BF 소관. 시스템 며칠 down 시 누락 과거분은 R-BF 로 메운다.
 *   - **DailyCloseFinalizer(20:10, managed watchlist)**: Redis WS 집계 → DB INSERT(DO NOTHING). WS tick 집계.
 *     R2 가 23:00 에 **나중에 돌아** 같은 (code,1d,today) 키를 멀티시세 공식 종가로 덮어 권위 확정(§2-D).
 *   - **R-BF(MarketDailyBackfillCampaign, admin /run)**: 과거 10년 1d range fetch → saveAll(DO NOTHING).
 *     과거봉 immutable. today 봉은 backfill 종료 경계에서 제외(어제까지).
 *
 *   같은 키 upsert 충돌 없음: R2 는 [CandleRepository.upsertAll](insertOrUpdate · ON CONFLICT DO UPDATE),
 *   DailyCloseFinalizer 는 insertIgnore(DO NOTHING). ts 표준 동일(today 00:00 KST) → 같은 UQ row 를
 *   R2 가 공식값으로 갱신, R-BF 는 today 미적재(어제까지)로 충돌 0.
 *
 * ## rate-limit (ADR 0116 · 0118 §2-C·§5-1 LOCK)
 *   [KisMultiPriceAdapter] 가 chunk 내부에서 BACKFILL(BG) 버킷 경유 — USER_PATH·INGESTION 침범 0.
 *   본 서비스는 chunk 간 [MarketDailySnapshotProperties.chunkSpacingMs] spacing 으로 burst 분산
 *   (2026-06-09 wipe→재적재 교훈: 단일 대량 동시호출 금지).
 *
 * 자본 영향 0 — 데이터 적재만(주문 0). LLM import 0 (룰 5). 외부 fallback 0 (KIS-only · mock 합성 0).
 */
@Component
class MarketDailyNightlySnapshotService(
    private val universeProvider: MarketDailyUniverseProvider,
    private val kisMultiPricePort: KisMultiPricePort,
    private val candleRepository: CandleRepository,
    private val auditRepository: ConsumerAuditTrailJpaRepository,
    private val objectMapper: ObjectMapper,
    private val properties: MarketDailySnapshotProperties,
) {
    private val log = LoggerFactory.getLogger(MarketDailyNightlySnapshotService::class.java)

    companion object {
        private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val INTERVAL = CandleInterval.ONE_DAY

        /** 한 콜 최대 종목 수 — KIS 멀티시세 raw spec(FID_INPUT_ISCD_N N=1..30) 정합 (어댑터 MAX_SYMBOLS_PER_CALL). */
        const val CHUNK_SIZE = 30

        /**
         * J 채널 — KRX 본시장 (ADR 0118 amend §9-B LOCK).
         * UN/NX 채널은 일부 종목 stale 동결 실측 → deprecated. today 종가 권위 = J 정규장.
         */
        const val MRKT_DIV_CODE_J = "J"

        /**
         * candle.source DB 컬럼 한계 — `VARCHAR(20)`. R-BF [MarketDailyBackfillCampaign.BACKFILL_SOURCE]
         * (`KIS-MKT-DAILY-BF`, 16자) 와 충돌 없는 별도 값. `"KIS-MKT-DAILY-SNAP"` = 18자 ≤ 20 (안전).
         * init require 가드로 회귀(varchar overflow → saveAll 전건 실패) 방지.
         */
        const val SOURCE_MAX_LEN = 20
        const val SNAPSHOT_SOURCE = "KIS-MKT-DAILY-SNAP"

        /** audit_trail.entity_id VARCHAR(40) 한계 — 짧은 고정 식별자(≤40)만 entity_id, runId 는 detail. */
        const val AUDIT_ENTITY_ID = "mkt-daily-snap"
        const val AUDIT_ENTITY_TYPE = "market_daily_snapshot"
    }

    init {
        require(SNAPSHOT_SOURCE.length <= SOURCE_MAX_LEN) {
            "SNAPSHOT_SOURCE '$SNAPSHOT_SOURCE' length=${SNAPSHOT_SOURCE.length} exceeds candle.source VARCHAR($SOURCE_MAX_LEN)"
        }
    }

    enum class SnapshotStatus {
        DISABLED, // ENV enabled=false — 가동 0 (운영 점검)
        EMPTY_UNIVERSE, // universe 0종목 (데이터 없음 path — mock 합성 0)
        COMPLETED, // 정상 완주
    }

    data class SnapshotResult(
        val runId: String,
        val status: SnapshotStatus,
        val universeSize: Int,
        val chunks: Int,
        val quotesReceived: Int,
        val candlesMapped: Int,
        val upsertedRows: Int,
        val skippedQuotes: Int,
        val durationMs: Long,
    )

    /**
     * 야간 snapshot 1회 실행 — today 전종목 1d 봉을 멀티시세 공식 종가로 upsert.
     *
     * 매 호출 멱등(today 봉만 DO UPDATE) — 같은 날 중복 호출 시 행 안 늘고 값만 갱신.
     * ENV off 시 즉시 DISABLED(콜 0). universe 0 시 EMPTY_UNIVERSE(graceful).
     */
    fun runSnapshot(): SnapshotResult {
        val startMs = System.currentTimeMillis()
        val runId = "mkt-daily-snap:$startMs"

        if (!properties.enabled) {
            log.info("MarketDailyNightlySnapshot DISABLED — ENV magicjar.market-daily-snapshot.enabled=false (가동 0)")
            return emptyResult(runId, SnapshotStatus.DISABLED, startMs)
        }

        val codes = universeProvider.resolveCodes()
        if (codes.isEmpty()) {
            log.warn("MarketDailyNightlySnapshot EMPTY_UNIVERSE — 보통주 0종목 (데이터 없음 — mock 합성 0)")
            audit("MARKET_DAILY_SNAPSHOT_EMPTY", mapOf("runId" to runId))
            return emptyResult(runId, SnapshotStatus.EMPTY_UNIVERSE, startMs)
        }

        // today 봉 ts 표준 = 해당 날짜 KST 00:00:00 (DailyCloseFinalizer·KIS stck_bsop_date 관행 정합).
        // 같은 UQ(code,1d,today) row 를 DailyCloseFinalizer(INSERT) 와 공유 → R2 upsert 가 공식값으로 교정.
        val today = LocalDate.now(KST_ZONE)
        val todayTs = KtInstant.fromEpochSeconds(today.atStartOfDay(KST_ZONE).toEpochSecond())

        val chunks = codes.chunked(CHUNK_SIZE)
        val spacingMs = properties.normalizedChunkSpacingMs()
        log.info(
            "MarketDailyNightlySnapshot 시동: runId={} universe={}종목 chunks={} spacingMs={} today={} mrktDivCode={}",
            runId,
            codes.size,
            chunks.size,
            spacingMs,
            today,
            MRKT_DIV_CODE_J,
        )
        audit(
            "MARKET_DAILY_SNAPSHOT_START",
            mapOf("runId" to runId, "universeSize" to codes.size, "chunks" to chunks.size, "today" to today.toString()),
        )

        var quotesReceived = 0
        var candlesMapped = 0
        var upsertedRows = 0
        var skippedQuotes = 0

        chunks.forEachIndexed { idx, chunk ->
            // chunk 간 가벼운 spacing — burst 분산(첫 chunk 는 지연 0). rate-limiter hard ceiling 은 어댑터 내부.
            if (idx > 0 && spacingMs > 0L) {
                try {
                    Thread.sleep(spacingMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.warn("MarketDailyNightlySnapshot chunk spacing 인터럽트 — 중단: runId={} chunkIdx={}", runId, idx)
                    return@forEachIndexed
                }
            }
            // J 채널 단일(ADR 0118 amend §9-B). 어댑터가 chunk 내부에서 BACKFILL 버킷 경유.
            val quotes = kisMultiPricePort.fetchMultiPrice(chunk, MRKT_DIV_CODE_J)
            quotesReceived += quotes.size

            val candles =
                quotes.mapNotNull { quote ->
                    val candle = toTodayCandle(quote, todayTs)
                    if (candle == null) skippedQuotes++
                    candle
                }
            candlesMapped += candles.size
            if (candles.isNotEmpty()) {
                upsertedRows +=
                    try {
                        // today 1d 봉 ON CONFLICT DO UPDATE — DailyCloseFinalizer INSERT 를 공식 종가로 덮음(권위).
                        candleRepository.upsertAll(candles)
                    } catch (e: Exception) {
                        log.warn(
                            "MarketDailyNightlySnapshot upsert 실패(다음 chunk 진행): runId={} chunkIdx={} size={} err={}",
                            runId,
                            idx,
                            candles.size,
                            e.message,
                        )
                        0
                    }
            }
        }

        val durationMs = System.currentTimeMillis() - startMs
        val result =
            SnapshotResult(
                runId = runId,
                status = SnapshotStatus.COMPLETED,
                universeSize = codes.size,
                chunks = chunks.size,
                quotesReceived = quotesReceived,
                candlesMapped = candlesMapped,
                upsertedRows = upsertedRows,
                skippedQuotes = skippedQuotes,
                durationMs = durationMs,
            )

        log.info(
            "MarketDailyNightlySnapshot 종료: runId={} status={} universe={} chunks={} quotes={} mapped={} upserted={} skipped={} durationMs={}",
            runId,
            result.status,
            result.universeSize,
            result.chunks,
            result.quotesReceived,
            result.candlesMapped,
            result.upsertedRows,
            result.skippedQuotes,
            result.durationMs,
        )
        audit(
            "MARKET_DAILY_SNAPSHOT_DONE",
            mapOf(
                "runId" to runId,
                "status" to result.status.name,
                "universeSize" to result.universeSize,
                "chunks" to result.chunks,
                "quotesReceived" to result.quotesReceived,
                "candlesMapped" to result.candlesMapped,
                "upsertedRows" to result.upsertedRows,
                "skippedQuotes" to result.skippedQuotes,
                "durationMs" to result.durationMs,
            ),
        )
        return result
    }

    /**
     * 멀티시세 quote → 그날(today) 1d Candle 변환 (KIS-first 매핑 · ADR 0118 §2-C step 3).
     *   open=open · high=high · low=low · close=close · volume=accumulatedVolume.
     *
     * 결손 graceful: OHLC 4필드 중 하나라도 null 이면 skip(mock 합성 0 — 부분 봉 적재 금지).
     *   volume 결손은 0 으로 적재(거래대금/전일종가는 검증 보강용이라 매핑 0 — Candle 도메인 미보유).
     *   adj 4필드는 null(수정주가 미적용 — 일반 raw 적재 컨벤션 정합).
     */
    internal fun toTodayCandle(
        quote: MultiPriceQuote,
        todayTs: KtInstant,
    ): Candle? {
        val open = quote.open
        val high = quote.high
        val low = quote.low
        val close = quote.close
        if (open == null || high == null || low == null || close == null) {
            log.debug(
                "MarketDailyNightlySnapshot quote OHLC 결손 — skip(부분 봉 적재 금지): code={} o={} h={} l={} c={}",
                quote.code,
                open,
                high,
                low,
                close,
            )
            return null
        }
        return Candle(
            code = quote.code,
            interval = INTERVAL,
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
            source = SNAPSHOT_SOURCE,
        )
    }

    private fun emptyResult(
        runId: String,
        status: SnapshotStatus,
        startMs: Long,
    ): SnapshotResult =
        SnapshotResult(
            runId = runId,
            status = status,
            universeSize = 0,
            chunks = 0,
            quotesReceived = 0,
            candlesMapped = 0,
            upsertedRows = 0,
            skippedQuotes = 0,
            durationMs = System.currentTimeMillis() - startMs,
        )

    private fun audit(
        action: String,
        detail: Map<String, Any?>,
    ) {
        val detailJson =
            try {
                objectMapper.writeValueAsString(detail)
            } catch (e: Exception) {
                log.warn("MarketDailyNightlySnapshot audit JSON 직렬화 실패: action={} err={}", action, e.message)
                "{}"
            }
        try {
            auditRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "market-data",
                    action = action,
                    entityType = AUDIT_ENTITY_TYPE,
                    entityId = AUDIT_ENTITY_ID,
                    detailJson = detailJson,
                ),
            )
        } catch (e: Exception) {
            log.warn("MarketDailyNightlySnapshot audit 저장 실패: action={} err={}", action, e.message)
        }
    }
}
