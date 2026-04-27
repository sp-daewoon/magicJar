package com.mj.domain.market.port

import com.mj.domain.common.Instrument

/**
 * 종목 마스터 카탈로그 포트. 구현체는 market-data 모듈이 제공한다.
 */
interface InstrumentCatalog {
    fun findAll(): List<Instrument>

    fun findByCode(code: String): Instrument?

    fun upsert(instruments: List<Instrument>)
}
