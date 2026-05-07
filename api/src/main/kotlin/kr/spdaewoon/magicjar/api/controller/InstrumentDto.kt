package kr.spdaewoon.magicjar.api.controller

/**
 * 종목 검색 응답 DTO (GET /api/instruments/search).
 * dispatch-spec §5-3 LOCK 정합.
 */
data class InstrumentSearchResponseDto(
    val query: String,
    val results: List<InstrumentSearchItemDto>,
    val totalMatched: Int,
    val limit: Int,
)

/**
 * 종목 검색 결과 단건 DTO.
 * score: 검색 일치도 (1.0 = 완전 일치).
 */
data class InstrumentSearchItemDto(
    val code: String,
    val name: String,
    val market: String,
    val sector: String?,
    val score: Double,
)

/**
 * 종목 상세 응답 DTO (GET /api/instruments/{code}).
 * dispatch-spec §5-7 예시 페이로드 정합.
 */
data class InstrumentDetailDto(
    val code: String,
    val isin: String?,
    val name: String,
    val nameEn: String?,
    val market: String,
    val type: String,
    val sector: String?,
    val theme: String?,
    val listedAt: String?,
    val delistedAt: String?,
    val active: Boolean,
    val aliases: List<InstrumentAliasDto>,
)

/**
 * 종목 alias DTO.
 */
data class InstrumentAliasDto(
    val alias: String,
    val source: String,
)

/**
 * 종목 universe 단건 DTO (GET /api/instruments).
 */
data class InstrumentUniverseItemDto(
    val code: String,
    val name: String,
    val market: String,
    val type: String,
    val sector: String?,
    val active: Boolean,
)

/**
 * 종목 universe 응답 DTO.
 */
data class InstrumentUniverseResponseDto(
    val items: List<InstrumentUniverseItemDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

/**
 * 수동 sync trigger 응답 DTO.
 */
data class InstrumentSyncTriggerResponse(
    val triggered: Boolean,
    val activeInstruments: Int,
)
