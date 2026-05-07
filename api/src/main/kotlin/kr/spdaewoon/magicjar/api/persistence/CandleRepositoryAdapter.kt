package kr.spdaewoon.magicjar.api.persistence

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * CandleRepository 도메인 포트 구현 (api 모듈).
 * saveAll: 각 행을 insertIgnore (ON CONFLICT DO NOTHING)으로 실행.
 * 트랜잭션 내에서 실행 — 호출자가 트랜잭션 없으면 신규 생성.
 */
@Component
class CandleRepositoryAdapter(
    private val candleJpaRepository: CandleJpaRepository,
) : CandleRepository {
    private val log = LoggerFactory.getLogger(CandleRepositoryAdapter::class.java)

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
        log.debug("candle 저장: total={} inserted={}", candles.size, inserted)
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
}
