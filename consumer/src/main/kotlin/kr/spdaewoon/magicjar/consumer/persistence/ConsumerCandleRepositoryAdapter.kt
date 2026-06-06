package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import kotlinx.datetime.Instant as KtInstant

/**
 * CandleRepository 도메인 포트 구현 — consumer 모듈.
 * KIS cold-start backfill 저장에 사용 (Yahoo Finance 2026-05-11 폐기 — KIS-only 정책).
 */
@Component
class ConsumerCandleRepositoryAdapter(
    private val candleJpaRepository: ConsumerCandleJpaRepository,
) : CandleRepository {
    private val log = LoggerFactory.getLogger(ConsumerCandleRepositoryAdapter::class.java)

    @Transactional
    override fun saveAll(candles: List<Candle>): Int {
        if (candles.isEmpty()) return 0
        var inserted = 0
        for (candle in candles) {
            val ts = Instant.ofEpochSecond(candle.ts.epochSeconds)
            val count =
                candleJpaRepository.insertIgnore(
                    code = candle.code,
                    interval = candle.interval.code,
                    ts = ts,
                    openRaw = candle.openRaw,
                    highRaw = candle.highRaw,
                    lowRaw = candle.lowRaw,
                    closeRaw = candle.closeRaw,
                    volume = candle.volume,
                    source = candle.source,
                )
            inserted += count
        }
        log.debug("candle 저장 (consumer): total={} inserted={}", candles.size, inserted)
        return inserted
    }

    @Transactional
    override fun upsertAll(candles: List<Candle>): Int {
        if (candles.isEmpty()) return 0
        var affected = 0
        for (candle in candles) {
            val ts = Instant.ofEpochSecond(candle.ts.epochSeconds)
            val count =
                candleJpaRepository.insertOrUpdate(
                    code = candle.code,
                    interval = candle.interval.code,
                    ts = ts,
                    openRaw = candle.openRaw,
                    highRaw = candle.highRaw,
                    lowRaw = candle.lowRaw,
                    closeRaw = candle.closeRaw,
                    volume = candle.volume,
                    source = candle.source,
                )
            affected += count
        }
        log.debug("candle upsert (consumer): total={} affected={}", candles.size, affected)
        return affected
    }

    @Transactional(readOnly = true)
    override fun countByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): Long = candleJpaRepository.countByCodeAndInterval(code, interval.code)

    @Transactional(readOnly = true)
    override fun countByCodeAndIntervalIn(
        code: String,
        intervals: List<CandleInterval>,
    ): Long = candleJpaRepository.countByCodeAndIntervalIn(code, intervals.map { it.code })

    /**
     * 종목·interval 전체 candle 반환 (ts ASC).
     * JPA entity → domain Candle 변환 (adj 필드 null 허용).
     * ADR 0062 §9 AggregateBackfillRunner 1m 전량 fetch 용.
     */
    @Transactional(readOnly = true)
    override fun findByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Candle> =
        candleJpaRepository
            .findByCodeAndIntervalOrderByTsAsc(code, interval.code)
            .map { it.toDomain(interval) }

    @Transactional(readOnly = true)
    override fun findLatestByCodeAndInterval(
        code: String,
        interval: CandleInterval,
        limit: Int,
    ): List<Candle> =
        candleJpaRepository
            .findByCodeAndIntervalLatest(code, interval.code, PageRequest.of(0, limit.coerceAtLeast(1)))
            .sortedBy { it.ts }
            .map { it.toDomain(interval) }

    @Transactional(readOnly = true)
    override fun findLatestBeforeByCodeAndInterval(
        code: String,
        interval: CandleInterval,
        beforeExclusive: Instant,
        limit: Int,
    ): List<Candle> =
        candleJpaRepository
            .findByCodeAndIntervalAndTsBefore(code, interval.code, beforeExclusive, PageRequest.of(0, limit.coerceAtLeast(1)))
            .sortedBy { it.ts }
            .map { it.toDomain(interval) }

    @Transactional(readOnly = true)
    override fun findMaxTsByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): Instant? = candleJpaRepository.findMaxTsByCodeAndInterval(code, interval.code)

    /**
     * 범위 내 ts ASC 조회 — ADR 0086 §2-2 (T-CC Phase 2-A CandleGapDetector 사용).
     */
    @Transactional(readOnly = true)
    override fun findAllTsByCodeAndIntervalBetween(
        code: String,
        interval: CandleInterval,
        from: Instant,
        to: Instant,
    ): List<Instant> =
        candleJpaRepository.findTsByCodeAndIntervalAndTsBetween(
            code = code,
            interval = interval.code,
            fromTs = from,
            toTs = to,
        )

    private fun ConsumerCandleJpaEntity.toDomain(interval: CandleInterval): Candle =
        Candle(
            id = id,
            code = code,
            interval = interval,
            ts = KtInstant.fromEpochSeconds(ts.epochSecond),
            openRaw = openRaw ?: BigDecimal.ZERO,
            highRaw = highRaw ?: BigDecimal.ZERO,
            lowRaw = lowRaw ?: BigDecimal.ZERO,
            closeRaw = closeRaw ?: BigDecimal.ZERO,
            openAdj = openAdj,
            highAdj = highAdj,
            lowAdj = lowAdj,
            closeAdj = closeAdj,
            volume = volume ?: 0L,
            source = source,
        )
}
