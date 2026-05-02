package com.mj.api.infrastructure.persistence.market

import com.mj.domain.common.Market
import com.mj.domain.market.instrument.Instrument
import com.mj.domain.market.instrument.InstrumentType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "instrument")
class InstrumentJpa(
    @Id
    @Column(name = "code", length = 12, nullable = false)
    var code: String,
    @Column(name = "isin", length = 12)
    var isin: String?,
    @Column(name = "name", length = 64, nullable = false)
    var name: String,
    @Column(name = "name_en", length = 128)
    var nameEn: String?,
    @Enumerated(EnumType.STRING)
    @Column(name = "market", length = 16, nullable = false)
    var market: Market,
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 16, nullable = false)
    var type: InstrumentType,
    @Column(name = "sector", length = 64)
    var sector: String?,
    @Column(name = "listed_at")
    var listedAt: LocalDate?,
    @Column(name = "delisted_at")
    var delistedAt: LocalDate?,
    @Column(name = "par_value")
    var parValue: BigDecimal?,
    @Column(name = "trading_unit", nullable = false)
    var tradingUnit: Int,
    @Column(name = "active", nullable = false)
    var active: Boolean,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

fun InstrumentJpa.toDomain(): Instrument =
    Instrument(
        code = code,
        isin = isin,
        name = name,
        nameEn = nameEn,
        market = market,
        type = type,
        sector = sector,
        listedAt = listedAt ?: LocalDate.EPOCH,
        delistedAt = delistedAt,
        parValue = parValue,
        tradingUnit = tradingUnit,
        active = active,
    )

fun Instrument.toJpa(updatedAt: Instant = Instant.now()): InstrumentJpa =
    InstrumentJpa(
        code = code,
        isin = isin,
        name = name,
        nameEn = nameEn,
        market = market,
        type = type,
        sector = sector,
        listedAt = listedAt,
        delistedAt = delistedAt,
        parValue = parValue,
        tradingUnit = tradingUnit,
        active = active,
        updatedAt = updatedAt,
    )
