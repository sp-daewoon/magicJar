package com.mj.batch.infrastructure.persistence.market

import com.mj.domain.common.Market
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface InstrumentJpaRepository : JpaRepository<InstrumentJpa, String> {
    fun findByMarket(market: Market): List<InstrumentJpa>

    /** 활성 종목 전체 조회 (Corp Action backfill 대상) */
    fun findByActiveTrue(): List<InstrumentJpa>

    @Modifying
    @Query(
        """
        UPDATE InstrumentJpa i SET i.delistedAt = :delistedAt, i.active = false, i.updatedAt = CURRENT_TIMESTAMP
        WHERE i.market = :market AND i.code NOT IN :activeCodes AND i.active = true
        """,
    )
    fun markDelistedIfNotIn(
        @Param("market") market: Market,
        @Param("activeCodes") activeCodes: Collection<String>,
        @Param("delistedAt") delistedAt: LocalDate,
    ): Int
}
