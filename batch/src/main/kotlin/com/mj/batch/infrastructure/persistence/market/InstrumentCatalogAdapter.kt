package com.mj.batch.infrastructure.persistence.market

import com.mj.domain.common.Instrument
import com.mj.domain.market.port.InstrumentCatalog
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Primary
@Transactional(readOnly = true)
class InstrumentCatalogAdapter(
    private val jpa: InstrumentJpaRepository,
) : InstrumentCatalog {
    override fun findAll(): List<Instrument> = jpa.findAll().map { it.toDomain() }

    override fun findByCode(code: String): Instrument? = jpa.findById(code).orElse(null)?.toDomain()

    @Transactional
    override fun upsert(instruments: List<Instrument>) {
        if (instruments.isEmpty()) return
        val now = Instant.now()
        val existing = jpa.findAllById(instruments.map { it.code }).associateBy { it.code }
        val toSave =
            instruments.map { domain ->
                existing[domain.code]?.apply {
                    name = domain.name
                    market = domain.market
                    updatedAt = now
                }
                    ?: InstrumentJpa(domain.code, domain.name, domain.market, now)
            }
        jpa.saveAll(toSave)
    }
}
