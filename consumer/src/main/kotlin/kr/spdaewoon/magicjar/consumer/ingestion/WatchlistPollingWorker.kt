package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisQuoteCacheAdapter
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisRestPriceAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Watchlist polling worker (ADR 0048 §D §3-2 · ADR 0124 결정 2 정합).
 *
 * ## ADR 0124 — watchlist 전용 5초 REST polling
 * watchlist 종목은 KIS WS 구독 0(ADR 0124 결정 2)이므로 본 worker가 watchlist 시세의 유일한 공급원이다.
 * pool에는 watchlist 종목 전체가 들어온다(부팅 reconcile·ADDED 시 add, REMOVED 시 remove).
 *
 * ## KIS Rate Limit 가드 (docs/architecture/external-api-policy.md — 초당 10 TPS, INGESTION 버킷)
 * - 각 fetch 는 [KisRestCandleAdapter] 내부에서 `KisRateLimiterPort.acquire(INGESTION)` 를 거친다 →
 *   limiter 가 초당 10건 절대 한도를 강제(우회 0). pool 이 커도 한도는 limiter 가 막는다.
 * - **주기 자동 완화**: `@Scheduled(fixedDelay)` — 이전 tick 완료 후 5초 대기. pool 이 커서 한 tick 처리가
 *   5초를 넘겨도 다음 tick 이 겹치지 않고 자연히 밀린다(스케줄 폭주 방지). 종목당 100ms sleep + REST 지연
 *   누적으로 cadence 가 늘어나면 실효 polling 주기가 자동 완화된다(현 watchlist ~14종목은 5초 내 완료).
 * - pool 이 [POOL_WARN_THRESHOLD] 초과 시 1회 WARN — 운영자가 watchlist 비대화를 인지하도록.
 *
 * - running=false 이면 스케줄 루프 skip (graceful stop)
 *
 * ## 현재가 단건 fetch 전환 (결함④ fix · 2026-06-15)
 * 기존 tick 은 `kisRestPort.fetchCandles(code, ONE_DAY, 1)` 로 "최신 1건" 을 의도했으나, KIS candle
 * adapter 는 `count` 를 무시하고 1d max page(50페이지)까지 페이지네이션 → **종목당 최대 5,000봉** 을
 * 매 tick 마다 받아오며 단일 스케줄러 스레드(`scheduling-1`)를 점유했다. 그 결과 같은 스레드에서 순차
 * 실행되는 [RealtimeStatusSnapshotPublisher] 의 5초 발행이 밀려 `admin:realtime-status` TTL(60s) 이
 * 만료 → 관리자 화면 "실시간 경로 비정상" 빨간 배너(false positive)가 떴다.
 *
 * fix: 코드 주석에 예고돼 있던 대로(L97 "PR-9b KisRestPriceAdapter 머지 후 교체 예정") 현재가 단건
 * endpoint [KisRestPriceAdapter.fetchPrice] (FHKST01010100 inquire-price · USER_PATH 버킷 · 3s timeout ·
 * 1 call/종목) 로 교체한다. tick 의 본 책임(실시간 시세 Redis quote cache 공급)만 수행한다.
 *
 * **candle DB upsert 제거**: 기존 hotfix 의 일봉 upsert 는 진행 중(오늘) stub 1d 봉을 적재하던 부가
 * 경로였고, fetchPrice 는 candle 이 아닌 현재가(stck_prpr)만 반환한다. 일봉 영속은
 * MarketDailyNightlySnapshotService(R2 야간 23:00 KIS 공식 마감값 upsert)·MarketDailyBackfillCampaign·
 * CandleBackfillWorker 가 권위로 담당하므로 polling 경로의 incidental upsert 제거는 데이터 손실이 없다.
 *
 * ## KIS-first raw 출처 (헌법 §3)
 * FHKST01010100 inquire-price: docs/operations/kis-api-reference.md §3 #1 — 구현은 [KisRestPriceAdapter].
 */
@Component
class WatchlistPollingWorker(
    private val redisQuoteCacheAdapter: RedisQuoteCacheAdapter,
    private val kisRestPriceAdapter: KisRestPriceAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
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
     * 5초 주기 polling (ADR 0048 §3-2 — 5초 LOCK + ADR 0124 fixedDelay 자동 완화).
     *
     * fixedDelay — 이전 tick 완료 후 5초 대기. pool 비대화 시 한 tick 이 5초를 넘겨도 다음 tick 이
     * 겹치지 않고 자연히 밀린다(KIS REST burst·스케줄 폭주 방지). limiter(INGESTION)가 초당 10 한도를 강제.
     * running=false 이면 skip.
     */
    @Scheduled(fixedDelay = 5000)
    fun tick() {
        if (!running || pool.isEmpty()) return
        val codes = pool.toSet()
        if (codes.size > POOL_WARN_THRESHOLD) {
            log.warn(
                "watchlist polling pool 비대화: size={} (>{}). 5초 polling 실효 주기 자동 완화 + KIS INGESTION 버킷 경합 가능 — watchlist 정리 권장.",
                codes.size,
                POOL_WARN_THRESHOLD,
            )
        }
        log.debug("polling tick: codes={}건", codes.size)
        // KIS 현재가 단건 endpoint(FHKST01010100)는 userId(REAL 자격증명)로 토큰을 조회한다.
        // tick 단위로 1회 resolve(전 종목 동일 system userId 재사용) — 종목당 재조회 회피.
        val userId = resolveSystemUserId()
        if (userId == null) {
            log.warn("watchlist polling: REAL 자격증명 없음 — 현재가 fetch skip (poolSize={})", codes.size)
            return
        }
        for (code in codes) {
            try {
                // 현재가 단건 fetch (FHKST01010100 inquire-price · USER_PATH 버킷 · 3s timeout · 1 call).
                // 결함④ fix: 기존 fetchCandles(ONE_DAY, 1) 은 count 무시 → 50페이지 5,000봉 폭주로
                // 단일 스케줄러 스레드를 점유해 status 발행을 굶겼다. 본 책임(시세 quote cache)만 수행.
                val tick = kisRestPriceAdapter.fetchPrice(userId, code)
                redisQuoteCacheAdapter.putQuote(
                    code = code,
                    price = tick.price,
                    volume = tick.volume,
                    source = "polling",
                )
                // Rate Limit 마진 (ADR 0037 대비 — 종목 간 100ms sleep)
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
     * system userId resolve — 첫 REAL 자격증명의 userId (WatchlistIngestionWorker 동일 패턴).
     *
     * 정보 수집(시세·캔들·호가)은 모두 REAL 키(사용자 메모리 feedback_kis_key_realonly_for_data). 빈 문자열
     * userId 는 DB 결함 방어로 null 취급(KisRestPriceAdapter 내부도 토큰 cache pollution 방어).
     */
    private fun resolveSystemUserId(): String? =
        credentialRepository
            .findAllByAccountType(AccountType.REAL.name)
            .firstOrNull()
            ?.id
            ?.userId
            ?.takeIf { it.isNotBlank() }

    private companion object {
        /**
         * polling pool 비대화 WARN 임계 (ADR 0124).
         * 종목당 100ms sleep + REST 지연 누적으로 5초 주기 내 안전 처리 가능 규모를 넘어서면 WARN.
         * 30 = 과거 WS PRICE_TICK_MAX 동등 규모(watchlist는 통상 ~14, 이 임계 초과는 비정상).
         */
        private const val POOL_WARN_THRESHOLD = 30
    }
}
