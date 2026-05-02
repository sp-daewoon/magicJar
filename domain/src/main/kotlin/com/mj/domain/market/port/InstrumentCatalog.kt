package com.mj.domain.market.port

import com.mj.domain.market.instrument.Instrument

/**
 * 종목 마스터 카탈로그 포트.
 *
 * @deprecated T3(Instrument Master) 트랙에서 [com.mj.domain.market.instrument.port.InstrumentRepository]로 교체됨.
 * 이 인터페이스는 하위 호환용으로 유지되며 신규 구현에서는 사용하지 않는다.
 */
@Deprecated(
    "InstrumentRepository로 마이그레이션 됨 (T3 트랙). com.mj.domain.market.instrument.port.InstrumentRepository 사용.",
    replaceWith = ReplaceWith("InstrumentRepository", "com.mj.domain.market.instrument.port.InstrumentRepository"),
)
interface InstrumentCatalog {
    fun findAll(): List<Instrument>

    fun findByCode(code: String): Instrument?

    fun upsert(instruments: List<Instrument>)
}
