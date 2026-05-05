package com.mj.api.application.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.cache.CacheMeta
import com.mj.domain.market.cache.port.CacheMetaRepository
import com.mj.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

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
    private val instrumentService: com.mj.api.application.instrument.InstrumentService,
    private val restClientBuilder: RestClient.Builder,
    @Value("\${magicjar.pyworker.base-url:http://localhost:8000}") private val pyworkerBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(CandleCacheService::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /**
     * 종목별 in-flight 호출 차단 (사용자 결정 2026-05-05).
     * 같은 종목에 대한 *동시* 호출만 fold — 호출이 끝나면 즉시 다음 호출 허용 (cooldown 없음).
     * 사용자가 더 과거 cursor로 빠르게 반복 호출해도 막히지 않음.
     */
    private val inFlightCodes = ConcurrentHashMap.newKeySet<String>()

    private val pyworkerClient: RestClient by lazy {
        // Spring Boot autoconfigure가 제공하는 RestClient.Builder bean 주입 — Jackson messageConverter
        // 자동 등록 상태. RestClient.builder()로 직접 생성하면 messageConverter 미등록 → body Map이
        // 직렬화 안 되어 pyworker가 body=null로 처리, 422 "Field required" 발생 (사용자 보고 2026-05-04).
        //
        // readTimeout 120s — 1분봉 1000건 fetch + Kafka publish는 yfinance 응답 지연 포함해
        // 기본 10s를 자주 초과 (사용자 환경 19:21~22 timeout 5회 보고). 120s면 yfinance 정상
        // 응답 시간 + 안전 마진 (최악 fail-fast).
        val requestFactory =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(120))
            }
        restClientBuilder
            .baseUrl(pyworkerBaseUrl)
            .requestFactory(requestFactory)
            .defaultHeader("Accept", "application/json")
            .build()
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
     * 백그라운드 M1 fetch trigger — ADR 0050 §C-1 정합 (사용자 결정 2026-05-05).
     *
     * 호출 단위: 1분봉 약 1000개 ≈ 2 영업일치 (yfinance days=2).
     * before=null → 최근 2일치, before 지정 → 그 시점 이전 2일치 (cursor 기반 무한 스크롤).
     *
     * 흐름: pyworker `yfinance` → Kafka publish (`market.candle.user.v1`)
     *      → consumer.UserCandleListener → Redis ZADD (DB INSERT 0건, 사용자 차트 전용 path).
     *
     * 동시 호출 차단: 같은 종목에 대한 in-flight 호출만 fold. 끝나면 즉시 다음 호출 허용.
     */
    @Async
    fun triggerBackgroundFetchAsync(
        code: String,
        before: Instant? = null,
    ) {
        if (!inFlightCodes.add(code)) {
            log.debug("[cache] backfill in-flight 중복 호출 무시 code={}", code)
            return
        }

        // instrument.market 조회 — KOSPI/KOSDAQ에 따라 yfinance primary suffix 결정.
        // 누락 시 pyworker가 .KS로 시도 후 .KQ fallback (사용자 보고 2026-05-04 에코플라스틱
        // 케이스: yfinance가 .KS에 일봉 형태 잘못 응답 → fallback 안 됨).
        val market = instrumentService.get(code)?.market?.name ?: "KOSPI"

        log.info(
            "[cache] cold-start backfill 시작 — code={} market={} pyworker={} (yfinance 1m×{}일, before={})",
            code,
            market,
            pyworkerBaseUrl,
            M1_FETCH_DAYS,
            before,
        )
        try {
            val request =
                mutableMapOf<String, Any>(
                    "code" to code,
                    "interval" to "1m",
                    "days" to M1_FETCH_DAYS,
                    "market" to market,
                ).apply {
                    // cursor 모드 — pyworker가 end=before 기준으로 (end-days, end) 윈도우 fetch.
                    before?.let { put("end", ISO_INSTANT.format(it)) }
                }
            val resp =
                pyworkerClient
                    .post()
                    .uri("/backfill/yahoo")
                    .body(request)
                    .retrieve()
                    .toBodilessEntity()
            log.info(
                "[cache] cold-start backfill 요청 성공 — code={} httpStatus={} (Kafka 적재 비동기 진행)",
                code,
                resp.statusCode,
            )
        } catch (e: Exception) {
            log.warn(
                "[cache] cold-start backfill 요청 실패 — code={} pyworker={} error={} (pyworker 미기동 또는 네트워크 오류)",
                code,
                pyworkerBaseUrl,
                e.message,
            )
        } finally {
            inFlightCodes.remove(code)
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
                // WEEK/MONTH는 KIS direct fetch 경로 (ADR 0050 R3-4) — CandleCacheService에서 미사용.
                else -> 180L
            }
        return to
            .atZone(kst)
            .toLocalDate()
            .minusDays(daysBack)
            .atStartOfDay(kst)
            .toInstant()
    }

    companion object {
        /**
         * ADR 0050 §C-1 정합 (사용자 인용 2026-05-04):
         * "분봉은 클릭할때마다 1분봉 기준으로 1000개씩 가져오는거지 야후를 통해서."
         *
         * 1분봉 1000개 ≈ 영업시간 6h × 60min ≈ 2.78 영업일.
         * yfinance 30일 한도는 그대로 두고, 우리는 2일치만 요청해 한도 boundary reject 차단.
         * 사용자가 더 과거 필요 시 frontend cursor(before)로 추가 호출 → Redis ZSet 누적.
         */
        private const val M1_FETCH_DAYS: Int = 2

        private val ISO_INSTANT: DateTimeFormatter =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    }
}
