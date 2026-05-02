package com.mj.consumer.infrastructure.persistence.market

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

/** consumer 전용 Instrument 엔티티. */
@Entity
@Table(name = "instrument")
class InstrumentJpa(
    @Id @Column(name = "code", length = 12) var code: String,
    @Column(name = "isin", length = 12) var isin: String?,
    @Column(name = "name", length = 64) var name: String,
    @Column(name = "name_en", length = 128) var nameEn: String?,
    @Enumerated(EnumType.STRING) @Column(name = "market", length = 16) var market: Market,
    @Enumerated(EnumType.STRING) @Column(name = "type", length = 16) var type: InstrumentType,
    @Column(name = "sector", length = 64) var sector: String?,
    @Column(name = "listed_at") var listedAt: LocalDate?,
    @Column(name = "delisted_at") var delistedAt: LocalDate?,
    @Column(name = "par_value") var parValue: BigDecimal?,
    @Column(name = "trading_unit") var tradingUnit: Int,
    @Column(name = "active") var active: Boolean,
    @Column(name = "updated_at") var updatedAt: Instant,
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
