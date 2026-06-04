package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.config.ChartCandleProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 캔들 백필 동시성 throttle gate (rate-limit safety #3 — 2026-06-04 dogfooding fix).
 *
 * ## 배경 (운영 evidence 2026-06-04)
 * 부팅 직후 `LastCandleAtBootstrapRunner`가 watchlist 8종목을 한꺼번에 `candleBackfillWorker.submit()` →
 * 2-thread pool에서 동시에 1m 페이지네이션 폭주 → KIS TPS_EXCEEDED 알림 폭주. 기존 KIS rate limiter
 * (Bucket4j 초당 10건)를 통과하더라도, 동시 종목 수가 많으면 알림 임계 위를 장시간 점유.
 *
 * ## 메커니즘 (기존 백필 설계 위 safety 레이어만 — 보수적)
 * - **직렬화**: `maxConcurrentBackfills` permit semaphore. default 1 (완전 직렬). run 시작 전 acquire.
 * - **run 간 간격**: 직전 run 종료 시각 + `minIntervalMillis` 이전이면 sleep. 백필 burst 분산.
 *
 * `CandleBackfillWorker.submit()` 비동기 path에만 적용 (자동 부팅·모니터 트리거).
 * admin 동기 `runBackfill()`은 운영자 1건 수동 호출이므로 gate 우회(운영자 우회 — 햇징 룰 #10 정합).
 *
 * 자본 영향 0 — 데이터 정합 보강 throttle만.
 */
@Component
class BackfillThrottleGate(
    private val chartCandleProperties: ChartCandleProperties,
) {
    private val log = LoggerFactory.getLogger(BackfillThrottleGate::class.java)

    private val cfg get() = chartCandleProperties.backfillGuard

    private val semaphore: Semaphore by lazy {
        Semaphore(cfg.maxConcurrentBackfills.coerceAtLeast(1), true)
    }

    @Volatile
    private var lastRunEndedAtMillis: Long = 0L

    /**
     * permit 획득 + run 간 최소 간격 보장. block 후 [block] 실행.
     *
     * @return [block] 결과, 또는 permit timeout 시 null(=run skip — 호출자가 다음 사이클로 미룸).
     */
    fun <T> runThrottled(
        code: String,
        block: () -> T,
    ): T? {
        val acquired =
            try {
                semaphore.tryAcquire(cfg.acquireTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!acquired) {
            log.warn(
                "BackfillThrottleGate permit timeout — run skip(다음 사이클로 미룸): code={} timeoutMs={}",
                code,
                cfg.acquireTimeoutMillis,
            )
            return null
        }
        try {
            enforceMinInterval(code)
            return block()
        } finally {
            lastRunEndedAtMillis = System.currentTimeMillis()
            semaphore.release()
        }
    }

    private fun enforceMinInterval(code: String) {
        val minInterval = cfg.minIntervalMillis
        if (minInterval <= 0L) return
        val sinceLast = System.currentTimeMillis() - lastRunEndedAtMillis
        if (lastRunEndedAtMillis > 0L && sinceLast < minInterval) {
            val waitMs = minInterval - sinceLast
            log.debug("BackfillThrottleGate run 간 간격 적용: code={} waitMs={}", code, waitMs)
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
