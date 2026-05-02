package com.mj.api.infrastructure.persistence.market

import com.mj.domain.common.Market
import com.mj.domain.market.instrument.Instrument
import com.mj.domain.market.instrument.port.InstrumentRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

@Component
@Transactional(readOnly = true)
class InstrumentCatalogAdapter(
    private val repository: InstrumentJpaRepository,
) : InstrumentRepository {
    override fun findAll(): List<Instrument> = repository.findAll().map { it.toDomain() }

    override fun findByCode(code: String): Instrument? = repository.findById(code).map { it.toDomain() }.orElse(null)

    override fun findByMarket(market: Market): List<Instrument> = repository.findByMarket(market).map { it.toDomain() }

    override fun findActive(): List<Instrument> = repository.findByActive(true).map { it.toDomain() }

    override fun search(
        query: String,
        limit: Int,
    ): List<Instrument> =
        repository
            .searchByCodeOrName(query)
            .map { it.toDomain() }
            .take(limit)

    @Transactional
    override fun upsert(instrument: Instrument) {
        upsertAll(listOf(instrument))
    }

    @Transactional
    override fun upsertAll(instruments: List<Instrument>) {
        if (instruments.isEmpty()) return
        val now = Instant.now()
        val existing = repository.findAllById(instruments.map { it.code }).associateBy { it.code }
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
                } ?: domain.toJpa(now)
            }
        repository.saveAll(toSave)
    }

    @Transactional
    override fun markDelistedIfNotIn(
        market: Market,
        activeCodes: Set<String>,
        delistedAt: LocalDate,
    ): Int = 0 // api 모듈은 읽기 위주 — 상장폐지 처리는 batch에서 담당
}
