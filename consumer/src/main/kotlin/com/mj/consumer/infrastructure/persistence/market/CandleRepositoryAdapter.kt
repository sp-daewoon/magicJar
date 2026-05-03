package com.mj.consumer.infrastructure.persistence.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.port.CandleRepository
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

    @Transactional(readOnly = true)
    override fun findLastClose(
        code: String,
        asOfDate: LocalDate,
    ): Candle? {
        // asOfDate 당일 자정(KST) 이하의 가장 최근 D1 캔들
        val beforeInstant = asOfDate.plusDays(1).atStartOfDay(KST).toInstant()
        return jpa
            .findFirstByCodeAndIntervalAndOpenTimeLessThanEqualOrderByOpenTimeDesc(
                code = code,
                interval = Interval.D1,
                before = beforeInstant,
            )?.toDomain()
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
