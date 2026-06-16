package kr.spdaewoon.magicjar.batch.persistence.market

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * market_index_snapshot Spring Data JPA Repository (batch 모듈 — T-MARKET Phase 1).
 *
 * ADR 0051 §2 + 헌법 §6 — body 가진 fun 0건 LOCK.
 * UPSERT는 nativeQuery (ON CONFLICT DO NOTHING — 1분 cron 멱등성 보장).
 */
interface MarketIndexSnapshotJpaRepository : JpaRepository<MarketIndexSnapshotJpaEntity, Long> {
    /**
     * 1분 snapshot 멱등 insert — UNIQUE(code, ts) 충돌 시 skip.
     *
     * @return inserted row count (0 = 충돌 skip, 1 = 신규 insert)
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO market_index_snapshot
                (code, market, ts, price, open, high, low, close, change_rate, volume, raw_payload, created_at)
            VALUES
                (:code, :market, :ts, :price, :open, :high, :low, :close, :changeRate, :volume,
                 CAST(:rawPayload AS jsonb), now())
            ON CONFLICT (code, ts) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("code") code: String,
        @Param("market") market: String,
        @Param("ts") ts: Instant,
        @Param("price") price: BigDecimal,
        @Param("open") open: BigDecimal?,
        @Param("high") high: BigDecimal?,
        @Param("low") low: BigDecimal?,
        @Param("close") close: BigDecimal?,
        @Param("changeRate") changeRate: BigDecimal?,
        @Param("volume") volume: Long?,
        @Param("rawPayload") rawPayload: String?,
    ): Int

    /**
     * 최신 N건 조회 (code 기준 ts DESC).
     */
    @Query(
        value = """
            SELECT * FROM market_index_snapshot
            WHERE code = :code
            ORDER BY ts DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findLatest(
        @Param("code") code: String,
        @Param("limit") limit: Int,
    ): List<MarketIndexSnapshotJpaEntity>

    /**
     * since 이후 모든 snapshot 조회 (code 기준 ts ASC).
     */
    @Query("SELECT s FROM MarketIndexSnapshotJpaEntity s WHERE s.code = :code AND s.ts >= :since ORDER BY s.ts ASC")
    fun findSince(
        @Param("code") code: String,
        @Param("since") since: Instant,
    ): List<MarketIndexSnapshotJpaEntity>

    /**
     * 특정 KST 영업일에 snapshot 이 존재하는 지수 code 목록 (지수 일봉 롤업 대상 자동 탐색).
     *
     * spec 2026-06-16 §5-1 · ADR 0126 §3-3 — 새 KIS 호출 0 (적재된 영속 snapshot 만 읽음).
     */
    @Query(
        value = """
            SELECT DISTINCT code
            FROM market_index_snapshot
            WHERE (ts AT TIME ZONE 'Asia/Seoul')::date = CAST(:tradeDate AS date)
            ORDER BY code
        """,
        nativeQuery = true,
    )
    fun findDistinctCodesByTradeDate(
        @Param("tradeDate") tradeDate: LocalDate,
    ): List<String>

    /**
     * 지수 일봉 롤업 집계 — 특정 KST 영업일 1개 code 의 OHLC (spec 2026-06-16 §3 V86 source).
     *
     * `ts` 는 TIMESTAMPTZ(KST 의미) — `AT TIME ZONE 'Asia/Seoul'` 으로 KST 달력일을 잘라 그날치만 묶는다.
     * open=첫 틱(ts ASC 1번)·high=max·low=min·close=막 틱(ts DESC 1번). 그날 snapshot 0건이면 행 미반환.
     * api `MarketIndexSnapshotJpaRepository.aggregateDailyOhlc` 와 동일 산식(모듈 경계상 batch-side 재구현).
     */
    @Query(
        value = """
            SELECT
                (array_agg(price ORDER BY ts ASC))[1]  AS open,
                MAX(price)                              AS high,
                MIN(price)                              AS low,
                (array_agg(price ORDER BY ts DESC))[1] AS close
            FROM market_index_snapshot
            WHERE code = :code
              AND (ts AT TIME ZONE 'Asia/Seoul')::date = CAST(:tradeDate AS date)
            HAVING COUNT(*) > 0
        """,
        nativeQuery = true,
    )
    fun aggregateDailyOhlc(
        @Param("code") code: String,
        @Param("tradeDate") tradeDate: LocalDate,
    ): BatchMarketIndexDailyOhlcProjection?
}

/**
 * 지수 일봉 OHLC 집계 결과 projection (Spring Data interface projection).
 * native query column alias(open/high/low/close)와 getter 명이 정합해야 한다.
 */
interface BatchMarketIndexDailyOhlcProjection {
    fun getOpen(): BigDecimal?

    fun getHigh(): BigDecimal?

    fun getLow(): BigDecimal?

    fun getClose(): BigDecimal?
}
