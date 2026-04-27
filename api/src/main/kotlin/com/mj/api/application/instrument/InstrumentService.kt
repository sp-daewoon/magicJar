package com.mj.api.application.instrument

import com.mj.domain.common.Instrument
import com.mj.domain.market.port.InstrumentCatalog
import org.springframework.stereotype.Service

@Service
class InstrumentService(
    private val catalog: InstrumentCatalog,
) {
    fun list(): List<Instrument> = catalog.findAll()

    fun get(code: String): Instrument? = catalog.findByCode(code)

    fun search(query: String): List<Instrument> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return catalog
            .findAll()
            .filter { it.code.lowercase().contains(q) || it.name.lowercase().contains(q) }
            .take(20)
    }
}
