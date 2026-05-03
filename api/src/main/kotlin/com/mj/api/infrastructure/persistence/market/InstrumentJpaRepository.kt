package com.mj.api.infrastructure.persistence.market

import com.mj.domain.common.Market
import com.mj.domain.market.instrument.InstrumentType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface InstrumentJpaRepository : JpaRepository<InstrumentJpa, String> {
    fun findByMarket(market: Market): List<InstrumentJpa>

    fun findByActive(active: Boolean): List<InstrumentJpa>

    fun findByTypeAndActive(
        type: InstrumentType,
        active: Boolean,
    ): List<InstrumentJpa>

    @Query(
        "SELECT i FROM InstrumentJpa i WHERE LOWER(i.code) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%'))",
    )
    fun searchByCodeOrName(query: String): List<InstrumentJpa>
}
