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
 * 분봉 캐시 서비스 (candle-data-policy.md §2, T19-T3).
 *
 * 역할:
 * 1. cache hit → DB에서 즉시 반환 + last_accessed_at 갱신
 * 2. cache miss (부분 또는 전무) → D1 즉시 반환 + 백그라운드 fetch trigger
 *    - 1차 (약 5초): 30분봉 3년 fetch → DB 영속
 *    - 2차 (약 35초 후): 5분봉 1년 fetch → DB 영속
 *
 * 충분(hit) 판정:
 *   from~to 사이 영업일 수 ≥ 캐시 row 수의 70% 이상.
 *   TODO(ADR 후속): 더 정밀한 영업일 캘린더 기반 판정으로 개선 가능.
 *
 * 백그라운드 fetch는 @Async 메서드로 분리. batch 모듈 JobTriggerController 호출이
 * 아닌 방식으로 구현한 이유: batch-app이 별도 프로세스이므로 직접 Job 실행 불가.
 * batch-app의 POST /jobs/minute-backfill 엔드포인트를 RestClient로 호출한다.
 * batch-app 미기동 시 예외를 catch·log 후 무시 — 차단 없음.
 *
 * @see MinuteCandleBackfillTriggerService batch-app HTTP 호출 헬퍼
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
     * @param interval 요청 봉 단위 (D1 이외 분봉만 cache miss trigger 수행)
     * @param from     조회 시작 시각 (null이면 봉별 기본 lookback)
     * @param to       조회 종료 시각 (null이면 현재)
     * @return 즉시 응답 캔들 목록 (hit이면 분봉, miss이면 D1 fallback)
     */
    fun fetchOrTrigger(
        code: String,
        interval: Interval,
        from: Instant?,
        to: Instant?,
    ): List<Candle> {
        val toResolved = to ?: Instant.now()
        val fromResolved = from ?: defaultFrom(interval, toResolved)

        // D1은 영구 영속이므로 cache 로직 우회
        if (interval == Interval.D1) {
            return candleRepository.find(code, interval, fromResolved, toResolved)
        }

        val candles = candleRepository.find(code, interval, fromResolved, toResolved)
        val meta = cacheMetaRepository.findByCodeAndInterval(code, interval)

        // 분봉은 있는 그대로 반환 (D1 fallback 정책 폐기 — 사용자가 분봉 선택했는데 일봉이 나오면 혼란).
        // 데이터 부족 시 백그라운드 fetch trigger만 추가로 발사.
        if (candles.isNotEmpty() && meta != null) {
            log.debug("[cache] HIT code={} interval={} rows={}", code, interval, candles.size)
            touchMeta(meta)
        } else if (candles.isEmpty()) {
            log.info("[cache] MISS code={} interval={} — triggering background fetch (returning empty)", code, interval)
            triggerBackgroundFetchAsync(code)
        } else {
            // candles는 있는데 meta는 없음 — V19 적용 전 시드 데이터. 백그라운드 trigger 안 함.
            log.debug("[cache] PARTIAL code={} interval={} rows={} (no meta — legacy seed)", code, interval, candles.size)
        }
        return candles
    }

    /**
     * hit 판정: from~to 영업일 수 ≥ 캐시 row 수의 70%.
     * 영업일 수는 단순 평일(월~금) 카운트로 근사 (캘린더 DB 미조회).
     *
     * TODO: 향후 MarketCalendarRepository를 주입해 정확한 영업일 수로 개선.
     */
    private fun isCacheHit(
        candles: List<Candle>,
        from: Instant,
        to: Instant,
        meta: CacheMeta?,
    ): Boolean {
        if (candles.isEmpty()) return false
        val workingDays = estimateWorkingDays(from, to)
        val hitThreshold = (workingDays * 0.7).toInt().coerceAtLeast(1)
        return candles.size >= hitThreshold
    }

    /**
     * 평일(월~금) 기준 영업일 수 단순 추정.
     * 정확한 공휴일 캘린더는 MarketCalendarRepository로 추후 대체 가능.
     */
    private fun estimateWorkingDays(
        from: Instant,
        to: Instant,
    ): Int {
        var count = 0
        var current = from.atZone(kst).toLocalDate()
        val toDate = to.atZone(kst).toLocalDate()
        while (!current.isAfter(toDate)) {
            val dow = current.dayOfWeek.value // 1=MON, 7=SUN
            if (dow <= 5) count++
            current = current.plusDays(1)
        }
        return count.coerceAtLeast(1)
    }

    private fun touchMeta(meta: CacheMeta) {
        runCatching {
            cacheMetaRepository.save(meta.copy(lastAccessedAt = Instant.now()))
        }.onFailure { e ->
            log.warn("[cache] last_accessed_at update failed code={}: {}", meta.code, e.message)
        }
    }

    /**
     * 백그라운드 분봉 fetch trigger:
     *   1차 — 30분봉 3년 (약 5초 소요)
     *   2차 — 5분봉 1년 (약 30초 소요)
     *
     * batch-app REST 호출로 MinuteCandleBackfillJob을 trigger한다.
     * batch-app 미기동 시 catch·log.
     */
    @Async
    @Transactional
    fun triggerBackgroundFetchAsync(code: String) {
        log.info("[cache] background fetch start — code={}", code)
        val today = LocalDate.now(kst)

        // 1차: 30분봉 3년
        triggerMinuteBackfill(code, interval = Interval.M30, from = today.minusYears(3), to = today)

        // 2차: 5분봉 1년
        triggerMinuteBackfill(code, interval = Interval.M5, from = today.minusYears(1), to = today)

        log.info("[cache] background fetch completed — code={}", code)
    }

    private fun triggerMinuteBackfill(
        code: String,
        interval: Interval,
        from: LocalDate,
        to: LocalDate,
    ) {
        try {
            val request =
                mapOf(
                    "code" to code,
                    "interval" to interval.name,
                    "from" to from.toString(),
                    "to" to to.toString(),
                )
            batchClient
                .post()
                .uri("/jobs/minute-backfill")
                .body(request)
                .retrieve()
                .toBodilessEntity()
            log.info("[cache] minute-backfill triggered code={} interval={} from={} to={}", code, interval, from, to)
        } catch (e: Exception) {
            log.warn(
                "[cache] minute-backfill trigger failed code={} interval={}: {}",
                code,
                interval,
                e.message,
            )
            // batch-app 미기동 시에도 응답 차단하지 않음
        }
    }

    private fun defaultFrom(
        interval: Interval,
        to: Instant,
    ): Instant {
        // KST 자정 align — 시드 분봉(09:00 시작)이 from 이후로 들어오게 보장.
        // CandleQueryService.defaultFrom과 동기화 유지.
        val daysBack =
            when (interval) {
                Interval.D1 -> 180L
                Interval.M1 -> 7L
                Interval.M5 -> 90L
                Interval.M15 -> 180L
                Interval.M30 -> 180L
                Interval.H1 -> 365L
            }
        return to
            .atZone(kst)
            .toLocalDate()
            .minusDays(daysBack)
            .atStartOfDay(kst)
            .toInstant()
    }
}
