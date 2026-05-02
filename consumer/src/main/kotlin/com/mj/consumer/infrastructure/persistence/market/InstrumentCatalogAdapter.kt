package com.mj.consumer.infrastructure.persistence.market

import com.mj.domain.common.Market
import com.mj.domain.market.instrument.Instrument
import com.mj.domain.market.instrument.port.InstrumentRepository
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

@Component
@Primary
@Transactional(readOnly = true)
class InstrumentCatalogAdapter(
    private val jpa: InstrumentJpaRepository,
) : InstrumentRepository {
    override fun findAll(): List<Instrument> = jpa.findAll().map { it.toDomain() }

    override fun findByCode(code: String): Instrument? = jpa.findById(code).orElse(null)?.toDomain()

    override fun findByMarket(market: Market): List<Instrument> = jpa.findByMarket(market).map { it.toDomain() }

    override fun findActive(): List<Instrument> = jpa.findAll().filter { it.active }.map { it.toDomain() }

    override fun search(
        query: String,
        limit: Int,
    ): List<Instrument> {
        val q = query.lowercase()
        return jpa
            .findAll()
            .filter { it.code.lowercase().contains(q) || it.name.lowercase().contains(q) }
            .take(limit)
            .map { it.toDomain() }
    }

    @Transactional
    override fun upsert(instrument: Instrument) {
        upsertAll(listOf(instrument))
    }

    @Transactional
    override fun upsertAll(instruments: List<Instrument>) {
        if (instruments.isEmpty()) return
        val now = Instant.now()
        val existing = jpa.findAllById(instruments.map { it.code }).associateBy { it.code }
        val toSave =
            instruments.map { domain ->
                existing[domain.code]?.apply {
                    isin = domain.isin
                    name = domain.name
                    nameEn = domain.nameEn
                    market = domain.market
                    type = domain.type
                    sector = domain.sector
                    listedAt = domain.listedAt
                    delistedAt = domain.delistedAt
                    parValue = domain.parValue
                    tradingUnit = domain.tradingUnit
                    active = domain.active
                    updatedAt = now
                } ?: InstrumentJpa(
                    code = domain.code,
                    isin = domain.isin,
                    name = domain.name,
                    nameEn = domain.nameEn,
                    market = domain.market,
                    type = domain.type,
                    sector = domain.sector,
                    listedAt = domain.listedAt,
                    delistedAt = domain.delistedAt,
                    parValue = domain.parValue,
                    tradingUnit = domain.tradingUnit,
                    active = domain.active,
                    updatedAt = now,
                )
            }
        jpa.saveAll(toSave)
    }

    @Transactional
    override fun markDelistedIfNotIn(
        market: Market,
        activeCodes: Set<String>,
        delistedAt: LocalDate,
    ): Int =
        if (activeCodes.isEmpty()) {
            0
        } else {
            jpa.markDelistedIfNotIn(market, activeCodes, delistedAt)
        }
}
