package kr.spdaewoon.magicjar.consumer.market

import kr.spdaewoon.magicjar.domain.market.candle.CandleSession
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Redis 진행봉 갱신 단일 경로.
 *
 * WS tick(H0UNCNT0/H0STOUP0)과 ACTIVE 전략 degraded polling이 같은 OHLCV/TTL/source 규칙을 공유한다.
 * 1d는 기존과 동일하게 공식 seed가 없으면 tick-only 생성하지 않는다.
 */
@Component
class LiveCandleUpdater(
    private val redisTemplate: StringRedisTemplate,
    /**
     * 예상체결 세션 tick 캔들 반영 제외 게이트 (ADR 0124 수용 기준 #2 — 2026-07-03 감사 §2′-⑥).
     * true(default)여도 [expectedConclusionSessionCodesRaw]가 비어 있으면 제외 0 (아래 참조).
     */
    @Value("\${magicjar.candle.exclude-expected-conclusion:true}")
    private val excludeExpectedConclusion: Boolean = true,
    /**
     * 예상체결로 판정할 NEW_MKOP_CLS_CODE 값 목록 (comma 구분, default 빈 값 = 제외 0).
     *
     * TODO(ADR 0124 수용기준 #2): NEW_MKOP_CLS_CODE 값 테이블(예상체결 코드) KIS-first 원문 미확보 —
     *   인덱스([34])는 확정돼 TickEvent.session 태그·Kafka payload 전파가 이미 동작한다. 라이브 세션 코드
     *   분포 관측으로 예상체결 코드가 확정되면 본 설정에 코드를 채워 제외를 활성한다(코드 무변경).
     *   임의 값 default는 실체결(NXT pre-hour 08:00~09:00 정책상 의도된 적재)을 오제외할 risk라 금지.
     */
    @Value("\${magicjar.candle.expected-conclusion-session-codes:}")
    private val expectedConclusionSessionCodesRaw: String = "",
) {
    private val log = LoggerFactory.getLogger(LiveCandleUpdater::class.java)
    private val kstZone = ZoneId.of("Asia/Seoul")

    /** 예상체결 판정 코드 set — 설정 문자열 1회 파싱 (blank 항목 제거). */
    private val expectedConclusionSessionCodes: Set<String> =
        expectedConclusionSessionCodesRaw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    fun update(tick: TickEvent) {
        try {
            // tick 가격 sanity 가드 (2026-07-03 감사 §2′-⑤ 2차 방어선) — 파서 reject를 우회한 polling/합성
            // source의 0원·음수 tick이 진행봉 OHLC를 오염(low=0 영속)하지 않도록 캔들 반영만 차단.
            if (tick.price.signum() <= 0) {
                log.warn(
                    "live candle 갱신 skip — 무효 가격 tick(≤0): code={} price={} source={}",
                    tick.code,
                    tick.price.toPlainString(),
                    tick.source,
                )
                return
            }
            // 예상체결 세션 제외 게이트 (ADR 0124 수용 기준 #2) — 캔들 반영만 skip.
            // quote:last·Kafka tick 발행은 KisWebSocketBridge 책임이라 본 게이트와 무관하게 계속 흐른다.
            if (excludeExpectedConclusion &&
                expectedConclusionSessionCodes.isNotEmpty() &&
                tick.session in expectedConclusionSessionCodes
            ) {
                log.debug(
                    "live candle 갱신 skip — 예상체결 세션 tick 제외: code={} session={} price={}",
                    tick.code,
                    tick.session,
                    tick.price.toPlainString(),
                )
                return
            }
            LIVE_CANDLE_INTERVALS.forEach { interval ->
                val start = liveCandleStart(tick, interval)
                updateLiveCandleHash(
                    key = RedisKeyRegistry.Market.candleCurrent(tick.code, interval),
                    interval = interval,
                    start = start,
                    tick = tick,
                    ttl = liveCandleTtl(interval),
                )

                // 기존 API/운영 키 호환. 신규 경로는 candle:current:{code}:1d를 우선 사용한다.
                if (interval == "1d") {
                    updateLiveCandleHash(
                        key = RedisKeyRegistry.Market.candleToday1d(tick.code),
                        interval = interval,
                        start = start,
                        tick = tick,
                        ttl = Duration.ofHours(24),
                    )
                }
            }
        } catch (e: Exception) {
            log.error("live candle 갱신 실패: code={} source={} error={}", tick.code, tick.source, e.message)
        }
    }

    private fun updateLiveCandleHash(
        key: String,
        interval: String,
        start: Instant,
        tick: TickEvent,
        ttl: Duration,
    ) {
        val existing = redisTemplate.opsForHash<String, String>().entries(key)
        val startTs = start.toString()
        val sameBar = (existing["startTs"] ?: existing["ts"]) == startTs
        val priceStr = tick.price.toPlainString()

        // 공식 KIS 시드가 먼저 Hash를 채웠는지. seeded=true 이면 open/고저/거래량 공식 baseline을 보존한다.
        val seeded = sameBar && existing["seeded"] == "true"
        if (interval == "1d" && !seeded) {
            log.debug("1d live candle tick-only 생성 skip — 공식 seed 대기: key={} startTs={}", key, startTs)
            return
        }

        val open =
            if (sameBar) {
                existing["open"] ?: priceStr
            } else {
                priceStr
            }
        val high =
            if (sameBar) {
                existing["high"]?.let { h -> maxOf(h.toBigDecimalOrNull() ?: tick.price, tick.price).toPlainString() } ?: priceStr
            } else {
                priceStr
            }
        val low =
            if (sameBar) {
                existing["low"]?.let { l -> minOf(l.toBigDecimalOrNull() ?: tick.price, tick.price).toPlainString() } ?: priceStr
            } else {
                priceStr
            }

        val baseFields: Map<String, String>
        val volume: Long
        if (seeded) {
            val acmlBase = existing["acmlBase"]?.toLongOrNull() ?: 0L
            val wsVolDelta = (existing["wsVolDelta"]?.toLongOrNull() ?: 0L) + tick.volume
            volume = acmlBase + wsVolDelta
            baseFields = mapOf("seeded" to "true", "acmlBase" to acmlBase.toString(), "wsVolDelta" to wsVolDelta.toString())
        } else {
            volume = (if (sameBar) existing["volume"]?.toLongOrNull() ?: 0L else 0L) + tick.volume
            baseFields = emptyMap()
        }

        redisTemplate.opsForHash<String, String>().putAll(
            key,
            mapOf(
                "interval" to interval,
                "open" to open,
                "high" to high,
                "low" to low,
                "close" to priceStr,
                "volume" to volume.toString(),
                "startTs" to startTs,
                "ts" to startTs,
                "source" to tick.source,
                "updatedAt" to tick.ts.toString(),
            ) + baseFields,
        )
        redisTemplate.expire(key, ttl)
    }

    private fun liveCandleStart(
        tick: TickEvent,
        interval: String,
    ): Instant {
        val tickInstant = Instant.ofEpochMilli(tick.ts.toEpochMilliseconds())
        val zdt = tickInstant.atZone(kstZone)
        val dayStart = zdt.toLocalDate().atStartOfDay(kstZone)
        val minOfDay = zdt.hour * 60 + zdt.minute

        return when (interval) {
            "1m" -> zdt.truncatedTo(ChronoUnit.MINUTES).toInstant()
            "3m" -> floorKstMinute(dayStart, minOfDay, 3)
            "5m" -> floorKstMinute(dayStart, minOfDay, 5)
            "15m" -> floorKstMinute(dayStart, minOfDay, 15)
            "30m" -> floorKstMinute(dayStart, minOfDay, 30)
            "90m" -> dayStart.plusMinutes(ninetyMinuteStart(minOfDay).toLong()).toInstant()
            "1h" -> zdt.truncatedTo(ChronoUnit.HOURS).toInstant()
            "4h" -> anchoredIntegratedStart(dayStart, minOfDay, 4 * 60)
            "12h" -> dayStart.plusMinutes(NXT_INTEGRATED_OPEN_MIN.toLong()).toInstant()
            "1d" -> dayStart.toInstant()
            "1wk" -> {
                val monday = zdt.toLocalDate().minusDays((zdt.dayOfWeek.value - 1).toLong())
                monday.atStartOfDay(kstZone).toInstant()
            }
            "1mo" -> LocalDate.of(zdt.year, zdt.month, 1).atStartOfDay(kstZone).toInstant()
            "1y" -> LocalDate.of(zdt.year, 1, 1).atStartOfDay(kstZone).toInstant()
            else -> dayStart.toInstant()
        }
    }

    private fun floorKstMinute(
        dayStart: java.time.ZonedDateTime,
        minOfDay: Int,
        intervalMinutes: Int,
    ): Instant {
        val startMinute = (minOfDay / intervalMinutes) * intervalMinutes
        return dayStart.plusMinutes(startMinute.toLong()).toInstant()
    }

    private fun anchoredIntegratedStart(
        dayStart: java.time.ZonedDateTime,
        minOfDay: Int,
        intervalMinutes: Int,
    ): Instant {
        val boundedMinute = minOfDay.coerceAtLeast(NXT_INTEGRATED_OPEN_MIN)
        val startMinute =
            NXT_INTEGRATED_OPEN_MIN +
                ((boundedMinute - NXT_INTEGRATED_OPEN_MIN) / intervalMinutes) * intervalMinutes
        return dayStart.plusMinutes(startMinute.toLong()).toInstant()
    }

    private fun ninetyMinuteStart(minOfDay: Int): Int {
        var picked = NINETY_MIN_BOUNDARY_STARTS.first()
        for (anchor in NINETY_MIN_BOUNDARY_STARTS) {
            if (anchor <= minOfDay) picked = anchor else break
        }
        return picked
    }

    private fun liveCandleTtl(interval: String): Duration =
        when (interval) {
            "1m" -> Duration.ofMinutes(2)
            "3m", "5m", "15m", "30m", "90m", "1h", "4h", "12h" -> Duration.ofHours(24)
            "1d" -> Duration.ofDays(2)
            "1wk" -> Duration.ofDays(14)
            "1mo" -> Duration.ofDays(62)
            "1y" -> Duration.ofDays(400)
            else -> Duration.ofDays(2)
        }

    companion object {
        val LIVE_CANDLE_INTERVALS =
            listOf("1m", "3m", "5m", "15m", "30m", "90m", "1h", "4h", "12h", "1d", "1wk", "1mo", "1y")

        private const val NXT_INTEGRATED_OPEN_MIN = CandleSession.NXT_INTEGRATED_OPEN_MIN
        private val NINETY_MIN_BOUNDARY_STARTS = CandleSession.NINETY_MIN_START_MIN.toList()
    }
}
