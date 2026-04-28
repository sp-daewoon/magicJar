package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentService
import com.mj.domain.common.Instrument
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(
    name = "Instrument",
    description = """
        KRX 상장 종목 카탈로그.

        Flyway V0~V4가 시드한 16개 종목(주요 KOSPI 우량주 + 대표 KOSDAQ)을 노출한다.
        실제 운영 시 KIS OpenAPI 종목마스터로부터 일 1회 동기화하는 잡이 추가될 예정(Phase F).
    """,
)
@RestController
@RequestMapping("/api/instruments")
class InstrumentController(
    private val instrumentService: InstrumentService,
) {
    @Operation(summary = "종목 전체 목록", description = "현재 등록된 모든 Instrument를 코드 오름차순으로 반환.")
    @GetMapping
    fun list(): ResponseEntity<List<Instrument>> = ResponseEntity.ok(instrumentService.list())

    @Operation(
        summary = "종목 단건 조회",
        description = "KRX 6자리 코드로 단건 조회. 코드는 자동으로 대문자 정규화된다(예: a005930 → 005930).",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "해당 코드의 종목 없음"),
        ],
    )
    @GetMapping("/{code}")
    fun get(
        @Parameter(description = "KRX 종목 코드 6자리", example = "005930")
        @PathVariable code: String,
    ): ResponseEntity<Instrument> {
        val instrument =
            instrumentService.get(code.uppercase())
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(instrument)
    }
}
