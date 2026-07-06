package kr.spdaewoon.magicjar.consumer.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kotlinx.datetime.toJavaInstant
import kr.spdaewoon.magicjar.consumer.aggregation.BoundaryAggregator
import kr.spdaewoon.magicjar.consumer.market.LiveCandleUpdater
import kr.spdaewoon.magicjar.consumer.order.PositionStopLossTickWatcher
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisOneMinuteCandleRangePort
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.datetime.Instant as KxInstant

/**
 * KIS WS H0UNCNT0 silent 종목 1m 보완 fallback.
 *
 * ## 왜 필요한가
 * Redis flush 또는 consumer 재기동 후 `market:candle:current:code:{code}:interval:1m` 진행봉은 메모리/Redis
 * 상태라 사라진다. 정상 종목은 다음 WS tick으로 즉시 재생성되지만, KIS가 SUBSCRIBE SUCCESS 이후 특정 종목의
 * H0UNCNT0 체결 프레임을 0건 보내는 silent 케이스가 있으면 1m closing·전략 평가가 멈춘다.
 *
 * ## 보수적 복구 정책
 * - DB gap은 KIS REST 1m range로 복구한다.
 * - 전략 평가는 과거 gap 전체를 재생하지 않고 **최신 닫힌 1분봉 1개만** Kafka로 발행한다.
 *   오래된 봉 전체를 늦게 BUY 주문으로 재생하는 위험을 피하기 위해서다.
 * - 호출은 [submit]에서 종목별 dedupe로 제한한다. 무tick watchdog이 30초마다 반복되어도 KIS REST 폭주를 막는다.
 *
 * 자본 영향: 신규 BUY는 최신 fallback candle에 대해서만 발생 가능하며, 기존 SignalDedupeGuard/DB UQ가 중복을 한 번 더 막는다.
 */
@Component
class NoTickMinuteFallbackService(
    private val candleRepository: CandleRepository,
    private val kisOneMinuteCandleRangePort: KisOneMinuteCandleRangePort,
    private val boundaryAggregator: BoundaryAggregator,
    private val liveCandleUpdater: LiveCandleUpdater,
    private val positionStopLossTickWatcher: PositionStopLossTickWatcher,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.kis.ws.no-tick-fallback-enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${magicjar.kis.ws.no-tick-fallback-dedupe-seconds:60}")
    private val dedupeSeconds: Long = 60L,
    @Value("\${magicjar.kis.ws.no-tick-fallback-min-call-interval-ms:1000}")
    private val minCallIntervalMs: Long = 1000L,
    @Value("\${magicjar.kis.ws.no-tick-fallback-backoff-base-seconds:60}")
    private val backoffBaseSeconds: Long = 60L,
    @Value("\${magicjar.kis.ws.no-tick-fallback-backoff-cap-seconds:600}")
    private val backoffCapSeconds: Long = 600L,
) {
    private val log = LoggerFactory.getLogger(NoTickMinuteFallbackService::class.java)
    private val inFlightUntilMs = ConcurrentHashMap<String, Long>()
    private val lastPublishedTsByCode = ConcurrentHashMap<String, Instant>()

    /**
     * 만성 silent 종목 종목별 지수 백오프 상태.
     *
     * 저유동성 종목은 WS tick이 분당 거의 없어 무tick watchdog이 매 점검(30s)마다 [submit]을 호출한다. 새 1분봉이
     * 실제로 유입되지 않는 한(published/inserted 0) fallback REST는 매분 KIS 공유 TPS를 소모하고, 경합 시
     * EGW00201(초당 거래건수 초과)를 분당 반복 생산한다. 진전 없는 연속 실패마다 다음 호출 자격 시각을
     * base(1분)→2→4→…cap(10분)으로 늘려 공유 TPS 압력을 완화한다. 신선한 봉 유입([recordBackoffOutcome]
     * madeProgress) 또는 실시간 tick 재개([onTickResumed]) 시 즉시 base cadence로 리셋해 데이터 신선도를 지킨다.
     */
    private val backoffByCode = ConcurrentHashMap<String, BackoffState>()
    private var nextRestCallAtMs: Long = 0L
    private val threadCounter = AtomicInteger(0)
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(
            1,
            ThreadFactory { r ->
                Thread(r, "ws-no-tick-1m-fallback-${threadCounter.incrementAndGet()}").also {
                    it.isDaemon = true
                }
            },
        )

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val SESSION_OPEN = java.time.LocalTime.of(8, 0)
        private const val SOURCE = "KIS-NOTICK-FALLBACK"
    }

    fun submit(
        code: String,
        ageSeconds: Long,
    ) {
        if (!enabled) return
        if (code.isBlank()) return

        val now = System.currentTimeMillis()
        val dedupeMs = dedupeSeconds.coerceAtLeast(10L) * 1000L
        val inFlightUntil = inFlightUntilMs[code]
        if (inFlightUntil != null && now < inFlightUntil) return

        // 만성 silent 백오프 gate — 진전 없는 연속 실패로 늘어난 자격 시각 전에는 REST를 발사하지 않는다.
        val backoff = backoffByCode[code]
        if (backoff != null && now < backoff.nextEligibleAtMs) {
            log.debug(
                "WS no-tick 1m fallback 백오프 skip: code={} remainMs={} failureStreak={}",
                code,
                backoff.nextEligibleAtMs - now,
                backoff.failureStreak,
            )
            return
        }

        inFlightUntilMs[code] = now + dedupeMs
        executor.submit {
            runCatching { runFallbackNow(code = code, ageSeconds = ageSeconds) }
                .onSuccess { result -> recordBackoffOutcome(code, madeProgress = result.published || result.inserted > 0) }
                .onFailure { e ->
                    log.warn("WS no-tick 1m fallback 실패: code={} err={}", code, e.message)
                    recordBackoffOutcome(code, madeProgress = false)
                }
        }
    }

    /**
     * fallback 실행 결과로 종목별 백오프를 갱신한다.
     *
     * - madeProgress(신선한 봉 저장·발행) → 백오프 제거 → 다음 무tick 감지 시 base cadence 즉시 복귀(신선도 우선).
     * - 진전 없음/예외(EGW00201 등) → 연속 실패 streak 증가 → 다음 자격 시각을 base<<(streak-1)로, cap까지 늘린다.
     */
    internal fun recordBackoffOutcome(
        code: String,
        madeProgress: Boolean,
    ) {
        if (madeProgress) {
            backoffByCode.remove(code)
            return
        }
        val baseMs = backoffBaseSeconds.coerceAtLeast(1L) * 1000L
        val capMs = backoffCapSeconds.coerceAtLeast(backoffBaseSeconds).coerceAtLeast(1L) * 1000L
        val state = backoffByCode.getOrPut(code) { BackoffState() }
        synchronized(state) {
            val streak = state.failureStreak + 1
            state.failureStreak = streak
            val shift = (streak - 1).coerceIn(0, 20)
            val scaledMs = (baseMs shl shift).let { if (it <= 0L) capMs else it }
            val intervalMs = scaledMs.coerceAtMost(capMs)
            state.nextEligibleAtMs = System.currentTimeMillis() + intervalMs
        }
    }

    /**
     * WS 실시간 tick이 재개된 종목의 백오프를 즉시 리셋한다.
     *
     * silent→active 전이 시 [kr.spdaewoon.magicjar.consumer.ingestion.KisWsNoTickFallbackWiring]가
     * KisWebSocketBridge.onTickResumed hook 경유로 호출한다. tick이 정상 유입되면 fallback REST 자체가 불필요하므로
     * 다시 silent해지더라도 base cadence부터 재출발하도록 streak을 청산한다.
     */
    fun onTickResumed(code: String) {
        if (code.isBlank()) return
        backoffByCode.remove(code)
    }

    /** 종목별 지수 백오프 상태 — [submit] 호출 스레드와 단일 fallback executor가 공유. */
    internal class BackoffState {
        @Volatile
        var nextEligibleAtMs: Long = 0L

        @Volatile
        var failureStreak: Int = 0
    }

    /** 테스트 관측용 — 종목 현재 실패 streak(백오프 미설정 시 0). */
    internal fun backoffStreak(code: String): Int = backoffByCode[code]?.failureStreak ?: 0

    /** 테스트 관측용 — 종목 다음 fallback 자격까지 남은 ms(백오프 미설정·만료 시 0). */
    internal fun backoffRemainMs(
        code: String,
        now: Long = System.currentTimeMillis(),
    ): Long = backoffByCode[code]?.let { (it.nextEligibleAtMs - now).coerceAtLeast(0L) } ?: 0L

    internal fun runFallbackNow(
        code: String,
        ageSeconds: Long,
    ): FallbackResult {
        if (!enabled) return FallbackResult(code, fetched = 0, inserted = 0, published = false, currentSeeded = false, latestTs = null)

        val targetTo = latestClosedMinute()
        val lastTs = candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN)
        val targetFrom = lastTs?.plusSeconds(60) ?: sessionOpenFor(targetTo)

        val fetched =
            if (targetFrom.isAfter(targetTo)) {
                emptyList()
            } else {
                waitForRestSlot()
                kisOneMinuteCandleRangePort
                    .fetchOneMinuteCandlesBetween(code, targetFrom, targetTo)
                    .map { it.copy(source = SOURCE) }
            }

        val inserted = if (fetched.isEmpty()) 0 else candleRepository.saveAll(fetched)
        val latest = fetched.maxByOrNull { it.ts }
        val latestForCurrent =
            latest ?: candleRepository.findLatestByCodeAndInterval(code, CandleInterval.ONE_MIN, 1).lastOrNull()
        val currentSeeded = latestForCurrent?.let { seedCurrentCandle(it) } ?: false
        val published = latest?.let { publishLatestIfNew(code, it, ageSeconds) } ?: false

        log.info(
            "WS no-tick 1m fallback 완료: code={} range={}..{} fetched={} inserted={} latestTs={} currentSeeded={} published={} ageSeconds={}",
            code,
            if (targetFrom.isAfter(targetTo)) null else targetFrom,
            targetTo,
            fetched.size,
            inserted,
            latestForCurrent?.ts,
            currentSeeded,
            published,
            ageSeconds,
        )
        return FallbackResult(
            code = code,
            fetched = fetched.size,
            inserted = inserted,
            published = published,
            currentSeeded = currentSeeded,
            latestTs = latestForCurrent?.ts?.toJavaInstant(),
        )
    }

    private fun seedCurrentCandle(candle: Candle): Boolean {
        val price = candle.closeRaw.takeIf { it > BigDecimal.ZERO } ?: return false
        val tick =
            TickEvent(
                code = candle.code,
                price = price,
                volume = 0L,
                ts = KxInstant.fromEpochMilliseconds(Instant.now().toEpochMilli()),
                source = SOURCE,
                stale = true,
            )
        liveCandleUpdater.update(tick)
        positionStopLossTickWatcher.onFallbackPriceTick(tick)
        return true
    }

    private fun publishLatestIfNew(
        code: String,
        candle: Candle,
        ageSeconds: Long,
    ): Boolean {
        val latestTs = candle.ts.toJavaInstant()
        val previous = lastPublishedTsByCode[code]
        if (previous != null && !latestTs.isAfter(previous)) return false

        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "code" to code,
                    "interval" to CandleInterval.ONE_MIN.code,
                    "ts" to latestTs.toString(),
                    "open" to candle.openRaw.toPlainString(),
                    "high" to candle.highRaw.toPlainString(),
                    "low" to candle.lowRaw.toPlainString(),
                    "close" to candle.closeRaw.toPlainString(),
                    "openRaw" to candle.openRaw.toPlainString(),
                    "highRaw" to candle.highRaw.toPlainString(),
                    "lowRaw" to candle.lowRaw.toPlainString(),
                    "closeRaw" to candle.closeRaw.toPlainString(),
                    "volume" to candle.volume,
                    "source" to SOURCE,
                    "publishedAt" to Instant.now().toString(),
                    "reason" to "ws-no-tick-fallback",
                    "noTickAgeSeconds" to ageSeconds,
                ),
            )
        kafkaTemplate.send(KafkaTopics.Candle.MINUTE_CLOSED, code, payload)
        boundaryAggregator.onTickClosed(code, latestTs)
        lastPublishedTsByCode[code] = latestTs
        return true
    }

    @Synchronized
    private fun waitForRestSlot() {
        val intervalMs = minCallIntervalMs.coerceAtLeast(0L)
        if (intervalMs == 0L) return

        val now = System.currentTimeMillis()
        val waitMs = nextRestCallAtMs - now
        if (waitMs > 0L) {
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        val baseMs = maxOf(System.currentTimeMillis(), nextRestCallAtMs)
        nextRestCallAtMs = baseMs + intervalMs
    }

    private fun latestClosedMinute(): Instant =
        ZonedDateTime
            .now(KST)
            .truncatedTo(ChronoUnit.MINUTES)
            .minusMinutes(1)
            .toInstant()

    private fun sessionOpenFor(targetTo: Instant): Instant =
        targetTo
            .atZone(KST)
            .toLocalDate()
            .atTime(SESSION_OPEN)
            .atZone(KST)
            .toInstant()

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }

    data class FallbackResult(
        val code: String,
        val fetched: Int,
        val inserted: Int,
        val published: Boolean,
        val currentSeeded: Boolean,
        val latestTs: Instant?,
    )
}
