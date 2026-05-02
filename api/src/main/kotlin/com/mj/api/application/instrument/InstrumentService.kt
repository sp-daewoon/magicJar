package com.mj.api.application.instrument

import com.mj.domain.market.instrument.Instrument
import com.mj.domain.market.instrument.port.InstrumentRepository
import org.springframework.stereotype.Service

@Service
class InstrumentService(
    private val repository: InstrumentRepository,
) {
    fun list(): List<Instrument> = repository.findAll()

    fun get(code: String): Instrument? = repository.findByCode(code)

    fun search(
        query: String,
        limit: Int = 20,
    ): List<Instrument> {
        if (query.isBlank()) return emptyList()
        return repository.search(query, limit)
    }
}
