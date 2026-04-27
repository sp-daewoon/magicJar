package com.mj.api.infrastructure.persistence.market

import com.mj.domain.common.Instrument
import com.mj.domain.market.port.InstrumentCatalog
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class InstrumentCatalogAdapter(
    private val repository: InstrumentJpaRepository,
) : InstrumentCatalog {
    @Transactional(readOnly = true)
    override fun findAll(): List<Instrument> = repository.findAll().map { it.toDomain() }

    @Transactional(readOnly = true)
    override fun findByCode(code: String): Instrument? = repository.findById(code).map { it.toDomain() }.orElse(null)

    @Transactional
    override fun upsert(instruments: List<Instrument>) {
        if (instruments.isEmpty()) return
        val now = Instant.now()
        val existing = repository.findAllById(instruments.map { it.code }).associateBy { it.code }
        val toSave =
            instruments.map { domain ->
                existing[domain.code]?.apply {
                    name = domain.name
                    market = domain.market
                    updatedAt = now
                } ?: domain.toJpa(now)
            }
        repository.saveAll(toSave)
    }
}
