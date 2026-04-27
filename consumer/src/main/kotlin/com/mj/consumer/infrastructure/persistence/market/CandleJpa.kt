package com.mj.consumer.infrastructure.persistence.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant

class CandleId(
    var code: String = "",
    var interval: Interval = Interval.D1,
    var openTime: Instant = Instant.EPOCH,
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CandleId) return false
        return code == other.code && interval == other.interval && openTime == other.openTime
    }

    override fun hashCode(): Int = listOf(code, interval, openTime).hashCode()
}

/** consumer 전용 Candle 엔티티 (쓰기 — TickAggregator → 1m 캔들 영속). */
@Entity
@Table(name = "candle")
@IdClass(CandleId::class)
class CandleJpa(
    @Id @Column(name = "code", length = 12) var code: String,
    @Id @Enumerated(EnumType.STRING) @Column(name = "interval", length = 8) var interval: Interval,
    @Id @Column(name = "open_time") var openTime: Instant,
    @Column(name = "open", precision = 18, scale = 4) var open: BigDecimal,
    @Column(name = "high", precision = 18, scale = 4) var high: BigDecimal,
    @Column(name = "low", precision = 18, scale = 4) var low: BigDecimal,
    @Column(name = "close", precision = 18, scale = 4) var close: BigDecimal,
    @Column(name = "volume") var volume: Long,
    @Column(name = "trade_count") var tradeCount: Long,
)

fun Candle.toJpa(): CandleJpa = CandleJpa(code, interval, openTime, open, high, low, close, volume, tradeCount)

fun CandleJpa.toDomain(): Candle = Candle(code, interval, openTime, open, high, low, close, volume, tradeCount)
