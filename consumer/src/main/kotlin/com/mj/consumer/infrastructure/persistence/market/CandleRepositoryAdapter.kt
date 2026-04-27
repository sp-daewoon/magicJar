package com.mj.consumer.infrastructure.persistence.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.port.CandleRepository
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Primary
@Transactional
class CandleRepositoryAdapter(
    private val jpa: CandleJpaRepository,
) : CandleRepository {
    override fun save(candles: List<Candle>) {
        if (candles.isEmpty()) return
        // upsert 의미론: 동일 PK 있으면 덮어쓰기 (JPA saveAll은 merge 호출)
        jpa.saveAll(candles.map { it.toJpa() })
    }

    @Transactional(readOnly = true)
    override fun find(
        code: String,
        interval: Interval,
        from: Instant,
        to: Instant,
    ): List<Candle> = jpa.findByCodeAndIntervalAndOpenTimeBetween(code, interval, from, to).map { it.toDomain() }
}
