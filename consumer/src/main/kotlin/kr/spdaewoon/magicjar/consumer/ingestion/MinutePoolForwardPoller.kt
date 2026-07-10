package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.consumer.config.MinutePoolBackfillProperties
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 분봉 수집 풀 — 장중 전방 수집 poller (2026-07-10 · market-data · minute-pool · 원래 스코프 2순위).
 *
 * 백필([kr.spdaewoon.magicjar.consumer.aggregation.MinutePoolBackfillCampaign])이 채운 후보 풀에 대해
 * 장중 주기적으로 "마지막 닫힌 1분봉 → 최신분" gap 을 KIS REST(BACKFILL 버킷)로 이어 붙인다.
 *
 * ## WS 슬롯 절대 미사용 (ADR 0124 LOCK)
 *   본 poller 는 KIS WS 구독을 전혀 쓰지 않는다(WS 40 slot 은 매매 owner 전용). REST 전용.
 *
 * ## 안전
 *   - 세션 게이트: 정규장(09:00~15:35 KST · 평일)에만 발사. 세션 밖은 즉시 return(콜 0).
 *   - 종목당 gap 만 fetch(maxTs+1분 → now) — 1페이지 내외. 실패는 카운터 후 다음 종목(전략 평가 무영향).
 *   - 동일 BG 버킷 재사용 — USER_PATH·INGESTION 침범 0.
 *
 * ## rate-limit 예산
 *   풀 30종목 × gap(≤ few 봉) = cycle 당 ~30 콜. default 5분 주기 → ~6 콜/분 = BG 버킷(3/초=180/분)의 ~3%.
 *
 * enabled(마스터) AND forwardPoller.enabled 둘 다 true 여야 가동. 자본 영향 0(데이터 적재만).
 */
@Component
class MinutePoolForwardPoller(
    private val poolProvider: MinutePoolProvider,
    private val collectionService: MinutePoolCollectionService,
    private val candleRepository: CandleRepository,
    private val properties: MinutePoolBackfillProperties,
) {
    private val log = LoggerFactory.getLogger(MinutePoolForwardPoller::class.java)

    private companion object {
        val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        val SESSION_OPEN: LocalTime = LocalTime.of(9, 0)

        // 15:30 정규장 마감 + 5분 grace (마감 직전 분봉 확정 여유).
        val SESSION_CLOSE_GRACE: LocalTime = LocalTime.of(15, 35)
    }

    private val cycleCounter = AtomicLong(0)

    @Scheduled(
        fixedDelayString = "\${magicjar.minute-pool-backfill.forward-poller.fixed-delay-ms:300000}",
        initialDelayString = "\${magicjar.minute-pool-backfill.forward-poller.initial-delay-ms:90000}",
    )
    fun poll() {
        if (!properties.enabled || !properties.forwardPoller.enabled) return
        val nowKst = ZonedDateTime.now(KST_ZONE)
        if (!isInSession(nowKst)) {
            log.debug("minute-pool 전방 수집 — 세션 밖({}KST) skip", nowKst.toLocalTime())
            return
        }

        val pool = poolProvider.resolvePool()
        if (pool.isEmpty()) {
            log.debug("minute-pool 전방 수집 — 풀 비어 있음 skip")
            return
        }

        val to = nowKst.truncatedTo(ChronoUnit.MINUTES).minusMinutes(1).toInstant()
        val sessionOpenInstant =
            nowKst
                .toLocalDate()
                .atTime(SESSION_OPEN)
                .atZone(KST_ZONE)
                .toInstant()
        val cycle = cycleCounter.incrementAndGet()

        var collected = 0
        var skipped = 0
        var failed = 0
        var inserted = 0L
        for (code in pool) {
            val lastTs =
                runCatching { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN) }
                    .getOrDefault(null)
            val from: Instant = lastTs?.plusSeconds(60) ?: sessionOpenInstant
            if (from.isAfter(to)) {
                skipped++
                continue
            }
            try {
                val outcome = collectionService.collect(code, from, to)
                collected++
                inserted += outcome.inserted
            } catch (e: Exception) {
                failed++
                log.warn("minute-pool 전방 수집 실패: cycle={} code={} err={} (다음 종목 진행)", cycle, code, e.message)
            }
        }
        log.info(
            "minute-pool 전방 수집 cycle={}: pool={} collected={} skipped={} failed={} inserted={} to={}",
            cycle,
            pool.size,
            collected,
            skipped,
            failed,
            inserted,
            to,
        )
    }

    private fun isInSession(now: ZonedDateTime): Boolean {
        val dow = now.dayOfWeek
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false
        val t = now.toLocalTime()
        return !t.isBefore(SESSION_OPEN) && t.isBefore(SESSION_CLOSE_GRACE)
    }
}
