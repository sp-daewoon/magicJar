package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
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
