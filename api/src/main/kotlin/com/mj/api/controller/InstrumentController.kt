package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentService
import com.mj.api.application.market.CandleQueryService
import com.mj.api.controller.dto.CandleResponse
import com.mj.api.controller.dto.toResponse
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException

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
    private val candleQueryService: CandleQueryService,
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

    @Operation(
        summary = "종목 캔들 조회",
        description = """
            LWC(Lightweight Charts) 호환 OHLCV 배열을 반환한다.

            interval은 'DAY' 또는 'D1' 등 alias 허용. from·to 미지정 시 일봉은 최근 180일 lookback.
            응답 time 필드는 일봉의 경우 'YYYY-MM-DD'(KST 기준), 분봉은 epoch second 문자열.

            005930(삼성전자) 일봉은 Flyway V7로 90 영업일 시드되어 있다 — 차트 검증용.
            실제 운영에서는 batch 모듈의 DailyCandleBackfillJob이 KIS API로 채운다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 (빈 배열도 200)"),
            ApiResponse(responseCode = "400", description = "interval 또는 from/to 파라미터 형식 오류"),
            ApiResponse(responseCode = "404", description = "해당 코드의 종목 없음"),
        ],
    )
    @GetMapping("/{code}/candles")
    fun candles(
        @Parameter(description = "KRX 종목 코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(description = "봉 단위 (DAY/D1, M1, M5, M15, M30, H1)", example = "DAY")
        @RequestParam(defaultValue = "DAY") interval: String,
        @Parameter(description = "조회 시작 시각 (ISO-8601, 미지정 시 봉 단위별 기본 lookback)", example = "2026-01-01T00:00:00Z")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "조회 종료 시각 (ISO-8601, 미지정 시 현재 시각)", example = "2026-04-30T23:59:59Z")
        @RequestParam(required = false) to: String?,
    ): ResponseEntity<List<CandleResponse>> {
        val normalized = code.uppercase()
        instrumentService.get(normalized) ?: return ResponseEntity.notFound().build()

        val intervalEnum =
            runCatching { CandleQueryService.parseInterval(interval) }
                .getOrElse { return ResponseEntity.badRequest().build() }

        val fromInstant =
            if (from.isNullOrBlank()) {
                null
            } else {
                parseInstantOrNull(from) ?: return ResponseEntity.badRequest().build()
            }
        val toInstant =
            if (to.isNullOrBlank()) {
                null
            } else {
                parseInstantOrNull(to) ?: return ResponseEntity.badRequest().build()
            }

        val candles = candleQueryService.query(normalized, intervalEnum, fromInstant, toInstant)
        return ResponseEntity.ok(candles.map { it.toResponse() })
    }

    private fun parseInstantOrNull(raw: String): Instant? =
        try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }
}
