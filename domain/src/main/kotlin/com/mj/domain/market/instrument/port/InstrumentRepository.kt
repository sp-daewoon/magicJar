package com.mj.domain.market.instrument.port

import com.mj.domain.common.Market
import com.mj.domain.market.instrument.Instrument
import java.time.LocalDate

/**
 * Instrument 저장소 포트 (ADR 0037).
 *
 * - 글로벌 (user 비종속)
 * - 상장폐지 종목 보존 의무 — delistedAt 설정, 행 삭제 X
 */
interface InstrumentRepository {
    fun findAll(): List<Instrument>

    fun findByCode(code: String): Instrument?

    fun findByMarket(market: Market): List<Instrument>

    fun findActive(): List<Instrument>

    /** code로 prefix 또는 name substring 검색. limit 기본 20. */
    fun search(
        query: String,
        limit: Int = 20,
    ): List<Instrument>

    /**
     * 단건 upsert (code 기준 ON CONFLICT UPDATE).
     * 상장폐지 종목은 행 삭제 대신 delistedAt 설정.
     */
    fun upsert(instrument: Instrument)

    /**
     * 일괄 upsert (code 기준 ON CONFLICT UPDATE).
     */
    fun upsertAll(instruments: List<Instrument>)

    /**
     * 지정 시장에서 주어진 코드 목록에 없는 종목을 상장폐지 처리.
     * delistedAt = today, active = false 로 업데이트.
     * 행 삭제 X.
     * @return 상장폐지 처리된 코드 수
     */
    fun markDelistedIfNotIn(
        market: Market,
        activeCodes: Set<String>,
        delistedAt: LocalDate,
    ): Int
}
