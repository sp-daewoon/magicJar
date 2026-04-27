package com.mj.api.infrastructure.persistence.market

import com.mj.domain.common.Instrument
import com.mj.domain.common.Market
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "instrument")
class InstrumentJpa(
    @Id
    @Column(name = "code", length = 12, nullable = false)
    var code: String,
    @Column(name = "name", length = 64, nullable = false)
    var name: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "market", length = 16, nullable = false)
    var market: Market,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

fun InstrumentJpa.toDomain(): Instrument =
    Instrument(
        code = code,
        name = name,
        market = market,
    )

fun Instrument.toJpa(updatedAt: Instant = Instant.now()): InstrumentJpa =
    InstrumentJpa(
        code = code,
        name = name,
        market = market,
        updatedAt = updatedAt,
    )
