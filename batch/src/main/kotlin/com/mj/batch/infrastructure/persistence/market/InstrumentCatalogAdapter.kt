package com.mj.batch.infrastructure.persistence.market

import com.mj.domain.common.Market
import com.mj.domain.market.instrument.Instrument
import com.mj.domain.market.instrument.InstrumentType
import com.mj.domain.market.instrument.port.InstrumentRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/**
 * batch 모듈 InstrumentRepository 구현체.
 * InstrumentMasterSyncJob이 사용한다.
 */
@Component
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
                }
                    ?: InstrumentJpa(
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

/** InstrumentRaw → Instrument 도메인 변환 헬퍼. */
fun com.mj.domain.market.instrument.InstrumentRaw.toDomain(): Instrument =
    Instrument(
        code = code,
        isin = isin,
        name = name,
        nameEn = null,
        market = market,
        type = resolveType(),
        sector = sector,
        listedAt = listedAt() ?: LocalDate.EPOCH,
        delistedAt = null,
        parValue = parValue,
        tradingUnit = tradingUnit,
        active = !tradingHalted,
    )

private fun com.mj.domain.market.instrument.InstrumentRaw.resolveType(): InstrumentType =
    when {
        typeName?.contains("ETF", ignoreCase = true) == true -> InstrumentType.ETF
        typeName?.contains("ETN", ignoreCase = true) == true -> InstrumentType.ETN
        typeName?.contains("우", ignoreCase = true) == true -> InstrumentType.PREFERRED
        typeName?.contains("REIT", ignoreCase = true) == true -> InstrumentType.REIT
        else -> InstrumentType.STOCK
    }
