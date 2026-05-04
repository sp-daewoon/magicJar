package com.mj.batch.messaging.listener

import com.mj.batch.infrastructure.persistence.market.CandleJpa
import com.mj.batch.infrastructure.persistence.market.CandleJpaRepository
import com.mj.domain.common.Interval
import com.mj.domain.market.event.MarketCandleBackfillCommandEvent
import com.mj.domain.market.port.KisRestPort
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * `market.candle.backfill.command.v1` → KIS REST 일봉 fetch + DB 적재.
 *
 * 멱등성 (2-단계):
 *   1) **DB 검사** — `findFirstByCodeAndIntervalOrderByOpenTimeDesc`. 마지막 candle.openTime이
 *      어제(KST) 이상이면 이미 최신 → skip.
 *   2) **In-memory dedup** — 60초 TTL recent fetch set. 짧은 시간 내 같은 종목 명령 폭주 방지.
 *
 * 명령 발행처:
 *   - api QuoteController (사용자 진입 시)
 *   - api DailyCandleBackfillScheduler (16:30 KST default5)
 *
 * 일봉만 지원 (interval=D1). 분봉은 후속 (T19 분봉 backfill).
 *
 * Refs: handoff 2026-05-04 D — 사용자 보고 "4-30 마지막 캔들".
 */
@Component
class MarketCandleBackfillCommandListener(
    private val kisRestPort: KisRestPort,
    private val candleJpaRepository: CandleJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val kst: ZoneId = ZoneId.of("Asia/Seoul")

    /** 종목 → 마지막 fetch epoch millis (TTL 60초). */
    private val recentFetch = ConcurrentHashMap<String, Long>()

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.marketCandleBackfillCommand:market.candle.backfill.command.v1}"],
        groupId = "magicjar-batch-candle-backfill",
    )
    fun onCommand(event: MarketCandleBackfillCommandEvent) {
        val code = event.code
        if (code.isBlank()) {
            log.warn("[BACKFILL] blank code — skip event={}", event)
            return
        }

        if (event.interval != Interval.D1.name) {
            log.warn("[BACKFILL] unsupported interval={} code={} — skip", event.interval, code)
            return
        }

        // (1) in-memory dedup
        val now = System.currentTimeMillis()
        val lastFetch = recentFetch[code]
        if (lastFetch != null && now - lastFetch < DEDUP_TTL_MS) {
            log.debug("[BACKFILL] dedup hit code={} (last fetch {}ms ago)", code, now - lastFetch)
            return
        }

        try {
            // (2) DB 검사 — 어제까지 데이터 있으면 skip
            val today = LocalDate.now(kst)
            val yesterday = today.minusDays(1)
            val latest = candleJpaRepository.findFirstByCodeAndIntervalOrderByOpenTimeDesc(code, Interval.D1)
            val from: LocalDate
            if (latest != null) {
                val latestDate = latest.openTime.atZone(kst).toLocalDate()
                if (latestDate >= yesterday) {
                    log.debug(
                        "[BACKFILL] up-to-date code={} latest={} >= yesterday={} — skip",
                        code,
                        latestDate,
                        yesterday,
                    )
                    recentFetch[code] = now
                    return
                }
                from = latestDate.plusDays(1)
            } else {
                from = today.minusYears(1)
            }
            val to = today

            log.info("[BACKFILL] fetching code={} from={} to={}", code, from, to)
            val candles = kisRestPort.fetchCandles(code, Interval.D1, from, to)
            recentFetch[code] = now

            if (candles.isEmpty()) {
                log.warn("[BACKFILL] empty candles code={}", code)
                return
            }

            val entities =
                candles.map { c ->
                    CandleJpa(
                        code = c.code,
                        interval = c.interval,
                        openTime = c.openTime,
                        open = c.open,
                        high = c.high,
                        low = c.low,
                        close = c.close,
                        volume = c.volume,
                        tradeCount = c.tradeCount,
                    )
                }
            candleJpaRepository.saveAll(entities)
            log.info("[BACKFILL] saved code={} rows={} from={} to={}", code, entities.size, from, to)

            // recentFetch TTL 정리 (저용량 — 종목 4500 이내라 cleanup 단순)
            cleanupRecentFetch(now)
        } catch (e: Exception) {
            log.warn("[BACKFILL] failed code={}: {}", code, e.message)
            // 실패 시 dedup TTL 짧게 (15초) — 너무 빠른 재시도 방지하면서 회복은 빠르게
            recentFetch[code] = now - (DEDUP_TTL_MS - SHORT_RETRY_TTL_MS)
        }
    }

    private fun cleanupRecentFetch(nowMillis: Long) {
        val cutoff = nowMillis - DEDUP_TTL_MS
        recentFetch.entries.removeIf { it.value < cutoff }
    }

    /** 명령 발행처가 dedup 상태를 검사할 수 있는 helper (테스트용). */
    fun isRecentlyFetched(
        code: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val lastFetch = recentFetch[code] ?: return false
        return nowMillis - lastFetch < DEDUP_TTL_MS
    }

    /** Instant → LocalDate (KST). */
    @Suppress("unused")
    private fun toKstDate(instant: Instant): LocalDate = instant.atZone(kst).toLocalDate()

    companion object {
        const val DEDUP_TTL_MS: Long = 60_000L
        const val SHORT_RETRY_TTL_MS: Long = 15_000L
    }
}
