package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentService
import com.mj.domain.common.Instrument
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 심볼 자동완성 엔드포인트 (spec §11-2 — 차트 외부 검색).
 *
 * GET /api/symbols/search?q={query} — instrument 테이블 like 검색, 최대 20건 반환
 */
@RestController
@RequestMapping("/api/symbols")
class SymbolSearchController(
    private val instrumentService: InstrumentService,
) {
    @GetMapping("/search")
    fun search(
        @RequestParam(defaultValue = "") q: String,
    ): ResponseEntity<List<Instrument>> = ResponseEntity.ok(instrumentService.search(q))
}
