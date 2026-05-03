package com.mj.api.application.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.cache.CacheMeta
import com.mj.domain.market.cache.port.CacheMetaRepository
import com.mj.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 분봉 캐시 서비스 — v2 (ADR 0048, candle-data-policy-v2.md §3).
 *
 * 역할:
 *   D1 → DB 직접 반환 (cache meta 없음)
 *   M1 cache hit → DB 즉시 반환 + last_accessed_at 갱신
 *   M1 cache miss → 빈 목록 반환 + 백그라운드 M1 fetch trigger
 *
 * v2 변경: 합성 interval(M5/M30 등)은 이 서비스가 아닌 CandleQueryService.queryByString 경유.
 * 캐시 단위는 M1·D1 only — 합성 결과는 캐시하지 않음 (Redis 캐시는 후속 사이클에서 추가 가능).
 *
 * 백그라운드 fetch: batch-app POST /jobs/minute-backfill (M1 interval).
 * batch-app 미기동 시 catch·log — 응답 차단 없음.
 */
@Service
class CandleCacheService(
    private val candleRepository: CandleRepository,
    private val cacheMetaRepository: CacheMetaRepository,
    private val candleQueryService: CandleQueryService,
    @Value("\${magicjar.batch.base-url:http://localhost:8081}") private val batchBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(CandleCacheService::class.java)
    private val kst = ZoneId.of("Asia/Seoul")
    private val batchClient: RestClient by lazy {
        RestClient.builder().baseUrl(batchBaseUrl).build()
    }

    /**
     * 캔들 조회 + 필요 시 백그라운드 fetch trigger.
     *
     * @param code     종목코드
     * @param interval 요청 봉 단위 (D1·M1 — 합성 interval은 CandleQueryService.queryByString 사용)
     * @param from     조회 시작 시각 (null이면 봉별 기본 lookback)
     * @param to       조회 종료 시각 (null이면 현재)
     * @return 즉시 응답 캔들 목록
     */
    fun fetchOrTrigger(
        code: String,
        interval: Interval,
        from: Instant?,
        to: Instant?,
    ): List<Candle> {
        val toResolved = to ?: Instant.now()
        val fromResolved = from ?: defaultFrom(interval, toResolved)

        // D1은 영구 영속 — cache 로직 우회
        if (interval == Interval.D1) {
            return candleRepository.find(code, Interval.D1, fromResolved, toResolved)
        }

        // M1 캐시 처리
        val candles = candleRepository.find(code, Interval.M1, fromResolved, toResolved)
        val meta = cacheMetaRepository.findByCodeAndInterval(code, Interval.M1)

        if (candles.isNotEmpty() && meta != null) {
            log.debug("[cache] HIT code={} interval=M1 rows={}", code, candles.size)
            touchMeta(meta)
        } else if (candles.isEmpty()) {
            log.info("[cache] MISS code={} interval=M1 — triggering background M1 fetch (returning empty)", code)
            triggerBackgroundFetchAsync(code)
        } else {
            // candles는 있는데 meta는 없음 — V19 적용 전 시드 데이터. 백그라운드 trigger 안 함.
            log.debug("[cache] PARTIAL code={} interval=M1 rows={} (no meta — legacy seed)", code, candles.size)
        }
        return candles
    }

    private fun touchMeta(meta: CacheMeta) {
        runCatching {
            cacheMetaRepository.save(meta.copy(lastAccessedAt = Instant.now()))
        }.onFailure { e ->
            log.warn("[cache] last_accessed_at update failed code={}: {}", meta.code, e.message)
        }
    }

    /**
     * 백그라운드 M1 fetch trigger — v2.
     * M1 only (30분봉·5분봉 trigger 폐기).
     */
    @Async
    @Transactional
    fun triggerBackgroundFetchAsync(code: String) {
        log.info("[cache] background M1 fetch start — code={}", code)
        val today = LocalDate.now(kst)

        // M1 1주 분봉 backfill
        triggerMinuteBackfill(code, intervalStr = "M1", from = today.minusDays(7), to = today)

        log.info("[cache] background M1 fetch completed — code={}", code)
    }

    private fun triggerMinuteBackfill(
        code: String,
        intervalStr: String,
        from: LocalDate,
        to: LocalDate,
    ) {
        try {
            val request =
                mapOf(
                    "code" to code,
                    "interval" to intervalStr,
                    "from" to from.toString(),
                    "to" to to.toString(),
                )
            batchClient
                .post()
                .uri("/jobs/minute-backfill")
                .body(request)
                .retrieve()
                .toBodilessEntity()
            log.info("[cache] minute-backfill triggered code={} interval={} from={} to={}", code, intervalStr, from, to)
        } catch (e: Exception) {
            log.warn(
                "[cache] minute-backfill trigger failed code={} interval={}: {}",
                code,
                intervalStr,
                e.message,
            )
        }
    }

    private fun defaultFrom(
        interval: Interval,
        to: Instant,
    ): Instant {
        val daysBack =
            when (interval) {
                Interval.D1 -> 180L
                Interval.M1 -> 7L
            }
        return to
            .atZone(kst)
            .toLocalDate()
            .minusDays(daysBack)
            .atStartOfDay(kst)
            .toInstant()
    }
}
