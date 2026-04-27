package com.mj.consumer.infrastructure.persistence.journal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.math.BigDecimal

object JournalJsonCodec {
    private val mapper =
        ObjectMapper().apply {
            registerModule(
                com.fasterxml.jackson.module.kotlin
                    .kotlinModule(),
            )
        }

    fun encodeSnapshot(map: Map<String, BigDecimal>): String = mapper.writeValueAsString(map)

    fun decodeSnapshot(json: String): Map<String, BigDecimal> =
        runCatching { mapper.readValue<Map<String, BigDecimal>>(json) }.getOrElse { emptyMap() }

    fun encodeTags(tags: List<String>): String = mapper.writeValueAsString(tags)

    fun decodeTags(json: String): List<String> = runCatching { mapper.readValue<List<String>>(json) }.getOrElse { emptyList() }
}
