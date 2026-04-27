package com.mj.batch.infrastructure.persistence.market

import com.mj.domain.common.Instrument
import com.mj.domain.common.Market
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** batch 전용 Instrument 엔티티 (읽기). */
@Entity
@Table(name = "instrument")
class InstrumentJpa(
    @Id @Column(name = "code", length = 12) var code: String,
    @Column(name = "name", length = 64) var name: String,
    @Enumerated(EnumType.STRING) @Column(name = "market", length = 16) var market: Market,
    @Column(name = "updated_at") var updatedAt: Instant,
)

fun InstrumentJpa.toDomain(): Instrument = Instrument(code, name, market)
