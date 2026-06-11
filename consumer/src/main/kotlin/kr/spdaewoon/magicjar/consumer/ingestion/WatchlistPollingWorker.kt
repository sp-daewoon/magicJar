package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisQuoteCacheAdapter
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.candle.TodayCandleSeedWindow
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Watchlist polling worker (ADR 0048 §D §3-2 정합).
 * - eviction 후 WS 슬롯 부족 종목을 5초 주기 KIS REST polling으로 시세 수집
 * - KIS Rate Limit: 풀 30 × 5s = 6 req/s ≤ 10 한도 (1차 MVP — Thread.sleep(100ms) 마진)
 * - running=false 이면 스케줄 루프 skip (graceful stop)
 *
 * PR-9b KisRestPriceAdapter 미구현 시: KisRestPort.fetchCandles 1건 호출로 임시 대체.
 * market-data PR-9b 머지 후 fetchPrice로 교체 예정.
 *
 * hotfix: tick 시 Redis quote cache 갱신 외 DB candle upsert 추가 (ON CONFLICT DO NOTHING — 멱등성).
 * KIS WS 끊김 시 REST fallback 경로도 candle 영속을 보장.
 */
@Component
class WatchlistPollingWorker(
    private val redisQuoteCacheAdapter: RedisQuoteCacheAdapter,
    private val kisRestPort: KisRestPort,
    private val candleRepository: CandleRepository,
) {
    private val log = LoggerFactory.getLogger(WatchlistPollingWorker::class.java)

    /** thread-safe polling pool */
    private val pool: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var _running: Boolean = false
    val running: Boolean get() = _running

    fun add(code: String) {
        pool.add(code)
        log.debug("polling pool add: code={} poolSize={}", code, pool.size)
    }

    fun remove(code: String) {
        pool.remove(code)
        log.debug("polling pool remove: code={} poolSize={}", code, pool.size)
    }

    fun start() {
        _running = true
        log.info("WatchlistPollingWorker 시작: poolSize={}", pool.size)
    }

    fun stop() {
        _running = false
        log.info("WatchlistPollingWorker 중단")
    }

    fun poolSize(): Int = pool.size

    fun poolCodes(): Set<String> = pool.toSet()

    /**
     * 5초 주기 polling (ADR 0048 §3-2 — 5초 LOCK).
     * running=false 이면 skip.
     */
    @Scheduled(fixedRate = 5000)
    fun tick() {
        if (!running || pool.isEmpty()) return
        val codes = pool.toSet()
        log.debug("polling tick: codes={}건", codes.size)
        for (code in codes) {
            try {
                // 1차 MVP: KIS REST candle 1건으로 최신 가격 fetch (1d interval 마지막 close)
                // PR-9b KisRestPriceAdapter(FHKST01010100) 머지 후 교체 예정
                val candles = kisRestPort.fetchCandles(code, CandleInterval.ONE_DAY, 1)
                if (candles.isNotEmpty()) {
                    val latest = candles.maxByOrNull { it.ts.epochSeconds }!!
                    // Redis quote cache 갱신 (실시간 시세 공급)
                    redisQuoteCacheAdapter.putQuote(
                        code = code,
                        price = latest.closeRaw,
                        volume = latest.volume,
                        source = "polling",
                    )
                    // DB candle upsert — ON CONFLICT DO NOTHING (멱등성 보장, hotfix)
                    // candleRepository.saveAll → ConsumerCandleRepositoryAdapter.saveAll → insertIgnore
                    //
                    // 개장 전 today stub 가드 (2026-06-11 hotfix): KIS daily-itemchart는 개장 전
                    // "오늘 날짜" stub 행(시=고=저=종=전일종가·volume=0)을 줄 수 있다. ON CONFLICT DO NOTHING
                    // 때문에 이 stub가 먼저 들어가면 장중 실데이터 insert가 막힌다. DB 적재 직전에 제외한다.
                    // (과거 날짜 행은 그대로 적재 — 거래정지 이력 보존)
                    val now = Instant.now()
                    val persistable = candles.filterNot { shouldSkipTodayStub(it, now) }
                    val inserted =
                        if (persistable.isNotEmpty()) candleRepository.saveAll(persistable) else 0
                    log.debug(
                        "polling DB upsert: code={} fetched={} persistable={} inserted={}",
                        code,
                        candles.size,
                        persistable.size,
                        inserted,
                    )
                }
                // Rate Limit 마진 (ADR 0037 대비 — 1차 MVP: 100ms sleep)
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                log.warn("polling fetch 실패: code={} err={}", code, e.message)
            }
        }
    }

    /**
     * today stub 일봉 적재 가드 (2026-06-11 hotfix).
     *
     * 두 조건을 OR 로 판정해 skip 한다 (둘 다 today 행에만 적용 — 과거 행은 항상 적재):
     *  (1) 시간 가드: ts 의 KST 날짜 == 오늘 && 개장 전(주말 + 08:00 NXT 통합 개장 전).
     *      판정은 도메인 [TodayCandleSeedWindow.shouldSkipRedisCandle] 재사용 (신규 시간 상수 금지).
     *  (2) 시그니처 가드: ts == 오늘 && volume == 0 && open == close (KIS 가 개장 직후에도 잠깐
     *      stub 을 줄 가능성 방어 — 시간 가드와 독립).
     */
    private fun shouldSkipTodayStub(
        candle: Candle,
        now: Instant,
    ): Boolean {
        val candleTsJava = Instant.ofEpochSecond(candle.ts.epochSeconds)
        val isToday = candleTsJava.atZone(KST).toLocalDate() == now.atZone(KST).toLocalDate()
        if (!isToday) return false

        // (1) 개장 전 시간 가드 — 도메인 재사용
        if (TodayCandleSeedWindow.shouldSkipRedisCandle(now, candleTsJava, KST)) {
            log.debug(
                "polling skip today stub (pre-open): code={} ts={}",
                candle.code,
                candle.ts,
            )
            return true
        }

        // (2) stub 시그니처 가드 (장중 잠깐 stub 방어)
        if (candle.volume == 0L && candle.openRaw.compareTo(candle.closeRaw) == 0) {
            log.debug(
                "polling skip today stub (signature vol=0 open==close): code={} ts={}",
                candle.code,
                candle.ts,
            )
            return true
        }
        return false
    }

    private companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
