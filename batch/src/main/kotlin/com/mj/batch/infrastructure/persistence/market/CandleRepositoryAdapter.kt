package com.mj.batch.infrastructure.persistence.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.port.CandleRepository
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Primary
@Transactional(readOnly = true)
class CandleRepositoryAdapter(
    private val jpa: CandleJpaRepository,
) : CandleRepository {
    override fun save(candles: List<Candle>) {
        // batch는 캔들을 읽기 전용으로 사용 — consumer가 쓰기 담당
    }

    override fun find(
        code: String,
        interval: Interval,
        from: Instant,
        to: Instant,
    ): List<Candle> =
        jpa
            .findByCodeAndIntervalAndOpenTimeBetween(code, interval, from, to)
            .map { it.toDomain() }
}
