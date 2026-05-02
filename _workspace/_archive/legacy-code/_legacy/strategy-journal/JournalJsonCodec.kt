package com.mj.domain.strategy.journal

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

/** JSONB 컬럼(스냅샷·태그) ↔ Kotlin 자료구조 직렬화 헬퍼. */
internal object JournalJsonCodec {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    fun encodeSnapshot(map: Map<String, BigDecimal>): String = mapper.writeValueAsString(map)

    fun decodeSnapshot(json: String): Map<String, BigDecimal> {
        if (json.isBlank()) return emptyMap()
        return mapper.readValue(json, object : TypeReference<Map<String, BigDecimal>>() {})
    }

    fun encodeTags(tags: List<String>): String = mapper.writeValueAsString(tags)

    fun decodeTags(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return mapper.readValue(json, object : TypeReference<List<String>>() {})
    }
}
