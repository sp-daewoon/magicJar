package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentMasterSyncApiService
import com.mj.api.application.instrument.InstrumentService
import com.mj.api.application.market.CandleQueryService
import com.mj.api.controller.dto.CandleResponse
import com.mj.api.controller.dto.InstrumentMasterSyncRequest
import com.mj.api.controller.dto.InstrumentMasterSyncResponse
import com.mj.api.controller.dto.InstrumentMasterSyncStatusResponse
import com.mj.api.controller.dto.InstrumentSearchResponse
import com.mj.api.controller.dto.toResponse
import com.mj.api.controller.dto.toSearchResponse
import com.mj.domain.market.instrument.Instrument
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
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
    private val masterSyncService: InstrumentMasterSyncApiService,
) {
    @Operation(summary = "종목 전체 목록", description = "현재 등록된 모든 Instrument를 코드 오름차순으로 반환.")
    @GetMapping
    fun list(): ResponseEntity<List<Instrument>> = ResponseEntity.ok(instrumentService.list())

    @Operation(
        summary = "종목 검색",
        description = """
            종목 코드(prefix) 또는 종목명(substring) 으로 검색한다 (ADR 0037 §2-4).

            - 코드 검색: '005930', '005', '0059' 등 prefix 매칭
            - 이름 검색: '삼성', '전자', 'Samsung' 등 부분 일치
            - 빈 쿼리는 빈 배열 반환 (전체 목록은 GET /api/instruments 사용)
            - limit 기본값 20, 최대 100
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "검색 결과 (0건이어도 200)"),
            ApiResponse(responseCode = "400", description = "limit 파라미터 범위 초과 (>100)"),
        ],
    )
    @GetMapping("/search")
    fun search(
        @Parameter(description = "검색어 — 종목코드 prefix 또는 종목명 substring", example = "삼성")
        @RequestParam(defaultValue = "") q: String,
        @Parameter(description = "최대 결과 수 (기본 20, 최대 100)", example = "20")
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<List<InstrumentSearchResponse>> {
        if (limit > 100) return ResponseEntity.badRequest().build()
        val results = instrumentService.search(q.trim(), limit)
        return ResponseEntity.ok(results.map { it.toSearchResponse() })
    }

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

    @Operation(
        summary = "종목 마스터파일 수동 수집 트리거",
        description = """
            KIS OpenAPI로부터 종목 마스터파일을 즉시 수집한다 (T18a-T4).

            - markets: 수집 대상 시장 목록. 미지정 시 KOSPI·KOSDAQ·KONEX 기본 대상.
            - 비동기 처리 — runId로 상태 폴링 (GET /api/instruments/master/sync/{runId}).
            - batch-app(port 8081)에 위임 실행. batch-app 미가용 시 BATCH_APP_UNAVAILABLE 상태 반환.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "202", description = "수집 시작 (runId 반환)"),
        ],
    )
    @PostMapping("/master/sync", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun triggerMasterSync(
        @RequestBody(required = false) req: InstrumentMasterSyncRequest?,
    ): ResponseEntity<InstrumentMasterSyncResponse> {
        val result = masterSyncService.triggerSync(req?.markets)
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(InstrumentMasterSyncResponse(runId = result.runId, status = result.status, scheduledAt = result.scheduledAt.toString()))
    }

    @Operation(
        summary = "종목 마스터파일 수집 상태 조회",
        description = "triggerMasterSync에서 받은 runId로 수집 진행 상태를 조회한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "상태 조회 성공"),
            ApiResponse(responseCode = "404", description = "해당 runId 없음 또는 batch-app 미가용"),
        ],
    )
    @GetMapping("/master/sync/{runId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun masterSyncStatus(
        @Parameter(description = "triggerMasterSync 응답에서 받은 runId", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        @PathVariable runId: String,
    ): ResponseEntity<InstrumentMasterSyncStatusResponse> {
        val status = masterSyncService.syncStatus(runId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            InstrumentMasterSyncStatusResponse(
                runId = status["runId"] as? String ?: runId,
                status = status["status"] as? String ?: "UNKNOWN",
                scheduledAt = status["scheduledAt"]?.toString(),
                completedAt = status["completedAt"]?.toString(),
                errorMessage = status["errorMessage"]?.toString(),
            ),
        )
    }

    private fun parseInstantOrNull(raw: String): Instant? =
        try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }
}
