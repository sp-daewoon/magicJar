package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentService
import com.mj.domain.common.Instrument
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 종목 카탈로그 엔드포인트.
 *
 * GET /api/instruments          — 종목 전체 목록
 * GET /api/instruments/{code}   — 단건 상세
 */
@RestController
@RequestMapping("/api/instruments")
class InstrumentController(
    private val instrumentService: InstrumentService,
) {
    @GetMapping
    fun list(): ResponseEntity<List<Instrument>> = ResponseEntity.ok(instrumentService.list())

    @GetMapping("/{code}")
    fun get(
        @PathVariable code: String,
    ): ResponseEntity<Instrument> {
        val instrument =
            instrumentService.get(code.uppercase())
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(instrument)
    }
}
