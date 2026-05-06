package kr.spdaewoon.magicjar.domain.instrument.port

import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket

/**
 * 종목 마스터 영속 포트 (ADR 0017).
 */
interface InstrumentRepository {
    fun findByCode(code: String): Instrument?

    fun findActiveByMarket(market: InstrumentMarket): List<Instrument>

    fun upsert(instrument: Instrument): Instrument

    fun upsertAll(instruments: List<Instrument>): Int
}
