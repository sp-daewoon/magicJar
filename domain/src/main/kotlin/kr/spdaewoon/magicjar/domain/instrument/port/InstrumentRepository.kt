package kr.spdaewoon.magicjar.domain.instrument.port

import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentSearchResult
import java.time.LocalDate

/**
 * 종목 마스터 영속 포트 (ADR 0017).
 * PR-11b 보강: findByActiveTrue·findActiveByCodeIn·markDelisted·searchByPgTrgm 추가.
 */
interface InstrumentRepository {
    fun findByCode(code: String): Instrument?

    fun findActiveByMarket(market: InstrumentMarket): List<Instrument>

    /** 활성 종목 전체 조회 (Redis 인덱스 rebuild 용도). */
    fun findByActiveTrue(): List<Instrument>

    /** 종목코드 목록으로 활성 종목 조회 (sync diff 용도). */
    fun findActiveByCodeIn(codes: Collection<String>): List<Instrument>

    /**
     * 상장폐지 처리 — DELETE 금지 (ADR 0017 §2-2 생존자 편향 차단).
     * delisted_at = today, active = false UPDATE.
     */
    fun markDelisted(
        code: String,
        delistedAt: LocalDate,
    )

    /**
     * DB pg_trgm fallback 검색 (Redis miss 시 폴백).
     * design §6-5: query 정규화 + limit + market 필터.
     */
    fun searchByPgTrgm(
        query: String,
        limit: Int,
        market: InstrumentMarket?,
    ): List<InstrumentSearchResult>

    fun upsert(instrument: Instrument): Instrument

    fun upsertAll(instruments: List<Instrument>): Int
}
