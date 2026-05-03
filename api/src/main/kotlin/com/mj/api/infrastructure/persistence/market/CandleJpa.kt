package com.mj.api.infrastructure.persistence.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
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

@Entity
@Table(
    name = "candle",
    indexes = [
        Index(name = "idx_candle_code_interval_open_time", columnList = "code,interval,open_time"),
        Index(name = "idx_candle_code_interval_time", columnList = "code,interval,open_time"),
    ],
)
@IdClass(CandleId::class)
class CandleJpa(
    @Id
    @Column(name = "code", length = 12, nullable = false)
    var code: String,
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "interval", length = 8, nullable = false)
    var interval: Interval,
    @Id
    @Column(name = "open_time", nullable = false)
    var openTime: Instant,
    @Column(name = "open", nullable = false, precision = 18, scale = 4)
    var open: BigDecimal,
    @Column(name = "high", nullable = false, precision = 18, scale = 4)
    var high: BigDecimal,
    @Column(name = "low", nullable = false, precision = 18, scale = 4)
    var low: BigDecimal,
    @Column(name = "close", nullable = false, precision = 18, scale = 4)
    var close: BigDecimal,
    @Column(name = "volume", nullable = false)
    var volume: Long,
    @Column(name = "trade_count", nullable = false)
    var tradeCount: Long,
    /** 수정 시가 (Corp Action 보정, ADR 0038). NULL = 미보정. */
    @Column(name = "open_adjusted", precision = 18, scale = 4)
    var openAdjusted: BigDecimal? = null,
    /** 수정 고가 (Corp Action 보정, ADR 0038). NULL = 미보정. */
    @Column(name = "high_adjusted", precision = 18, scale = 4)
    var highAdjusted: BigDecimal? = null,
    /** 수정 저가 (Corp Action 보정, ADR 0038). NULL = 미보정. */
    @Column(name = "low_adjusted", precision = 18, scale = 4)
    var lowAdjusted: BigDecimal? = null,
    /** 수정 종가 (Corp Action 보정, ADR 0038). NULL = 미보정. 차트 기본 표시 컬럼. */
    @Column(name = "close_adjusted", precision = 18, scale = 4)
    var closeAdjusted: BigDecimal? = null,
    /** 수정 거래량 (Corp Action 보정). NULL = 미보정. */
    @Column(name = "volume_adjusted")
    var volumeAdjusted: Long? = null,
    /** 누적 보정 계수 (e.g. 0.02000000 = 1/50 분할). NULL = 미보정. */
    @Column(name = "adjustment_factor", precision = 20, scale = 8)
    var adjustmentFactor: BigDecimal? = null,
)

fun CandleJpa.toDomain(): Candle =
    Candle(
        code = code,
        interval = interval,
        openTime = openTime,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        tradeCount = tradeCount,
    )

fun Candle.toJpa(): CandleJpa =
    CandleJpa(
        code = code,
        interval = interval,
        openTime = openTime,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        tradeCount = tradeCount,
    )
