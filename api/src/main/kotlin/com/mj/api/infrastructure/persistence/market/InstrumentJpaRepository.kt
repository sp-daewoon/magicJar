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

    /**
     * 일반 보통주만 검색 (ETF·ETN·펀드·REIT·공모주·상장폐지 제외).
     *
     * 필터:
     * - active=true + delisted_at IS NULL — 상장 종목만
     * - ISIN LIKE 'KR7%' — 보통주·우선주 (KR5는 펀드, KRG는 REIT 등 제외)
     * - code ~ '^[0-9]{6}$' — 영문 포함 코드(파생 ETF 등) 제외
     * - name이 ETF·ETN 키워드(KODEX·TIGER·ACE·ARIRANG·KBSTAR·MAX·KOSEF·
     *   KINDEX·HANARO·SOL·RISE·FOCUS·PLUS·SMART·MASTERS·HK) 미포함
     */
    @Query(
        nativeQuery = true,
        value = """
            SELECT * FROM instrument i
            WHERE i.active = true
              AND i.delisted_at IS NULL
              AND i.isin LIKE 'KR7%'
              AND i.code ~ '^[0-9]{6}$'
              AND UPPER(i.name) NOT LIKE 'KODEX%'
              AND UPPER(i.name) NOT LIKE 'TIGER%'
              AND UPPER(i.name) NOT LIKE 'ACE %'
              AND UPPER(i.name) NOT LIKE 'ARIRANG%'
              AND UPPER(i.name) NOT LIKE 'KBSTAR%'
              AND UPPER(i.name) NOT LIKE 'MAX %'
              AND UPPER(i.name) NOT LIKE 'KOSEF%'
              AND UPPER(i.name) NOT LIKE 'KINDEX%'
              AND UPPER(i.name) NOT LIKE 'HANARO%'
              AND UPPER(i.name) NOT LIKE 'SOL %'
              AND UPPER(i.name) NOT LIKE 'RISE %'
              AND UPPER(i.name) NOT LIKE 'FOCUS%'
              AND UPPER(i.name) NOT LIKE 'PLUS %'
              AND UPPER(i.name) NOT LIKE 'SMART%'
              AND UPPER(i.name) NOT LIKE 'MASTERS%'
              AND UPPER(i.name) NOT LIKE '%H ETN%'
              -- 추가 ETF 발행사 + 액티브 ETF 패턴 (UNICORN·KoAct·KIWOOM·BNK·KGI·SHINHAN)
              AND UPPER(i.name) NOT LIKE 'UNICORN%'
              AND UPPER(i.name) NOT LIKE 'KOACT%'
              AND UPPER(i.name) NOT LIKE 'KIWOOM%'
              AND UPPER(i.name) NOT LIKE 'BNK %'
              AND UPPER(i.name) NOT LIKE 'KGI %'
              AND UPPER(i.name) NOT LIKE 'SHINHAN %'
              AND UPPER(i.name) NOT LIKE 'NH-AMUNDI%'
              -- 액티브·합성·커버드콜·채권혼합·ETN 등 ETF 시리즈 패턴
              AND i.name NOT LIKE '%액티브%'
              AND i.name NOT LIKE '%합성%'
              AND i.name NOT LIKE '%커버드콜%'
              AND i.name NOT LIKE '%채권혼합%'
              AND i.name NOT LIKE '%만기형%'
              AND (LOWER(i.code) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%')))
            ORDER BY i.code
            LIMIT 100
        """,
    )
    fun searchByCodeOrName(query: String): List<InstrumentJpa>
}
