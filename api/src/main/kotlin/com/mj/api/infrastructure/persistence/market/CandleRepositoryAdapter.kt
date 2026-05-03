package com.mj.api.infrastructure.persistence.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.port.CandleRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Component
class CandleRepositoryAdapter(
    private val repository: CandleJpaRepository,
) : CandleRepository {
    @Transactional
    override fun save(candles: List<Candle>) {
        if (candles.isEmpty()) return
        val toSave =
            candles.map { domain ->
                val id = CandleId(domain.code, domain.interval, domain.openTime)
                val existing = repository.findById(id).orElse(null)
                if (existing != null) {
                    existing.open = domain.open
                    existing.high = domain.high
                    existing.low = domain.low
                    existing.close = domain.close
                    existing.volume = domain.volume
                    existing.tradeCount = domain.tradeCount
                    existing
                } else {
                    domain.toJpa()
                }
            }
        repository.saveAll(toSave)
    }

    @Transactional(readOnly = true)
    override fun find(
        code: String,
        interval: Interval,
        from: Instant,
        to: Instant,
    ): List<Candle> =
        repository
            .findByCodeAndIntervalAndOpenTimeBetweenOrderByOpenTimeAsc(code, interval, from, to)
            .map { it.toDomain() }

    @Transactional(readOnly = true)
    override fun findLastClose(
        code: String,
        asOfDate: LocalDate,
    ): Candle? {
        val beforeInstant = asOfDate.plusDays(1).atStartOfDay(KST).toInstant()
        return repository
            .findFirstByCodeAndIntervalAndOpenTimeLessThanEqualOrderByOpenTimeDesc(
                code = code,
                interval = Interval.D1,
                before = beforeInstant,
            )?.toDomain()
    }

    @Transactional(readOnly = true)
    override fun findBefore(
        code: String,
        interval: Interval,
        before: Instant,
        limit: Int,
    ): List<Candle> =
        repository
            .findPageBeforeOrderByOpenTimeDesc(
                code = code,
                interval = interval,
                before = before,
                pageable = PageRequest.ofSize(limit),
            )
            // DESC로 가져왔으므로 ASC 재정렬
            .reversed()
            .map { it.toDomain() }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
