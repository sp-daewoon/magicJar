package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisQuoteCacheAdapter
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
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
                    val inserted = candleRepository.saveAll(candles)
                    log.debug("polling DB upsert: code={} fetched={} inserted={}", code, candles.size, inserted)
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
}
