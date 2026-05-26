package kr.spdaewoon.magicjar.api.persistence.trend

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MarketIndexSnapshotJpaRepository : JpaRepository<MarketIndexSnapshotJpaEntity, Long> {
    @Query(
        value = """
            SELECT *
            FROM market_index_snapshot
            WHERE code = :code
            ORDER BY ts DESC
            LIMIT 1
        """,
        nativeQuery = true,
    )
    fun findLatestByCode(
        @Param("code") code: String,
    ): MarketIndexSnapshotJpaEntity?

    @Query(
        value = """
            SELECT *
            FROM (
                SELECT DISTINCT ON (code) *
                FROM market_index_snapshot
                WHERE code IN (:codes)
                ORDER BY code, ts DESC
            ) latest
            ORDER BY code
        """,
        nativeQuery = true,
    )
    fun findLatestByCodes(
        @Param("codes") codes: Collection<String>,
    ): List<MarketIndexSnapshotJpaEntity>

    @Query(
        value = """
            SELECT *
            FROM (
                SELECT DISTINCT ON ((ts AT TIME ZONE 'Asia/Seoul')::date) *
                FROM market_index_snapshot
                WHERE code = :code
                ORDER BY (ts AT TIME ZONE 'Asia/Seoul')::date DESC, ts DESC
                LIMIT :limit
            ) daily
            ORDER BY ts ASC
        """,
        nativeQuery = true,
    )
    fun findDailySeries(
        @Param("code") code: String,
        @Param("limit") limit: Int,
    ): List<MarketIndexSnapshotJpaEntity>
}
