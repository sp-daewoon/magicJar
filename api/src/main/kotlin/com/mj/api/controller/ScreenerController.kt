package com.mj.api.controller

import com.mj.api.application.screener.ScreenerExecutor
import com.mj.api.controller.dto.EvalReasonDto
import com.mj.api.controller.dto.ScreenMatchResponse
import com.mj.api.controller.dto.ScreenRunRequest
import com.mj.api.controller.dto.ScreenRunResponse
import com.mj.api.controller.dto.ScreenStatusResponse
import com.mj.api.controller.dto.toResponse
import com.mj.api.infrastructure.dsl.StrategyDslParser
import com.mj.api.infrastructure.persistence.screener.ScreenMatchJpaRepository
import com.mj.api.infrastructure.persistence.screener.ScreenRunJpaRepository
import com.mj.domain.strategy.screener.ScreenRequest
import com.mj.domain.strategy.screener.Universe
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(
    name = "Screener",
    description = """
        차트 기반 진입 가능 종목 발굴 (ADR 0040).

        유니버스(KOSPI200·KOSDAQ150·전체·커스텀 등) × DSL 전략 조건으로
        매칭 종목을 배치 방식으로 스크리닝한다.

        흐름:
        1. `POST /api/screener/run` → 즉시 runId 반환 (동기 실행 — 소규모 유니버스)
        2. `GET /api/screener/{id}` → 상태·요약 조회
        3. `GET /api/screener/{id}/matches` → 매칭 종목 페이지 조회

        DSL 포맷은 `.kis.yaml` superset (ADR 0040 §1).
        KOSPI200·KOSDAQ150 유니버스는 instrument_index_member 테이블 기준이므로
        V16 마이그레이션 후 데이터 삽입이 필요하다.
    """,
)
@RestController
@RequestMapping("/api/screener")
class ScreenerController(
    private val screenerExecutor: ScreenerExecutor,
    private val dslParser: StrategyDslParser,
    private val screenRunRepo: ScreenRunJpaRepository,
    private val screenMatchRepo: ScreenMatchJpaRepository,
) {
    @Operation(
        summary = "스크리닝 실행",
        description = """
            유니버스 × DSL 전략 조건으로 매칭 종목을 스크리닝한다.
            동기 실행 (소규모 유니버스 기준 수 초 내 완료).
            CUSTOM 유니버스 시 customCodes 필드가 필수다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "스크리닝 완료 — runId 반환"),
            ApiResponse(responseCode = "400", description = "요청 유효성 오류 (DSL 파싱 실패·유니버스 오류 등)"),
        ],
    )
    @PostMapping("/run")
    fun run(
        @Valid @RequestBody req: ScreenRunRequest,
    ): ResponseEntity<ScreenRunResponse> {
        val dsl =
            try {
                dslParser.parse(req.strategyYaml)
            } catch (e: Exception) {
                return ResponseEntity.badRequest().build()
            }

        val universe =
            parseUniverse(req)
                ?: return ResponseEntity.badRequest().build()

        val screenRequest =
            ScreenRequest(
                universe = universe,
                dsl = dsl,
                asOfDate = req.asOfDate ?: LocalDate.now(),
            )

        val runId =
            screenerExecutor.execute(
                request = screenRequest,
                strategyYaml = req.strategyYaml,
            )

        return ResponseEntity.ok(
            ScreenRunResponse(
                runId = runId,
                status = "RUNNING",
                message = "스크리닝이 시작됐습니다.",
            ),
        )
    }

    @Operation(
        summary = "스크리닝 상태·요약 조회",
        description = """
            runId로 스크리닝 진행 상태와 요약(총 평가·매칭·스킵 수)을 반환한다.
            status: RUNNING / COMPLETED / FAILED.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(schema = Schema(implementation = ScreenStatusResponse::class))],
            ),
            ApiResponse(responseCode = "404", description = "runId 없음"),
        ],
    )
    @GetMapping("/{id}")
    fun getStatus(
        @Parameter(description = "POST /api/screener/run 응답의 runId", example = "8f6c1c10-9d0e-4b2c")
        @PathVariable id: String,
    ): ResponseEntity<ScreenStatusResponse> {
        val status = screenerExecutor.getStatus(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(status.toResponse())
    }

    @Operation(
        summary = "매칭 종목 목록 조회 (페이지네이션)",
        description = """
            스크리닝에서 entry 조건을 만족한 종목 목록을 페이지로 반환한다.
            각 항목에는 entry 근거(entryReasons) trace가 포함된다.
            기본 페이지 크기 20, 최대 100.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "runId 없음"),
        ],
    )
    @GetMapping("/{id}/matches")
    fun getMatches(
        @Parameter(description = "스크리닝 runId", example = "8f6c1c10-9d0e-4b2c")
        @PathVariable id: String,
        @PageableDefault(size = 20, sort = ["id"], direction = Sort.Direction.ASC)
        pageable: Pageable,
    ): ResponseEntity<Page<ScreenMatchResponse>> {
        val run = screenRunRepo.findByRunId(id) ?: return ResponseEntity.notFound().build()
        val matchPage = screenMatchRepo.findByScreenRunId(run.id, pageable)
        val responsePage =
            matchPage.map { m ->
                ScreenMatchResponse(
                    code = m.code,
                    name = m.name,
                    entryReasons = parseEntryReasons(m.entryReason),
                    matchedAt = m.matchedAt,
                )
            }
        return ResponseEntity.ok(responsePage)
    }

    // ──────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────

    private fun parseUniverse(req: ScreenRunRequest): Universe? =
        when (req.universeType.uppercase()) {
            "KOSPI200" -> Universe.Kospi200
            "KOSDAQ150" -> Universe.Kosdaq150
            "ETF" -> Universe.Etf
            "ETN" -> Universe.Etn
            "ALL_LISTED" -> Universe.AllListed
            "CUSTOM" -> {
                val codes = req.customCodes
                if (codes.isNullOrEmpty()) {
                    null
                } else {
                    Universe.Custom(codes)
                }
            }
            "SYMBOL_GROUP" -> Universe.SymbolGroup()
            else -> null
        }

    @Suppress("UNCHECKED_CAST")
    private fun parseEntryReasons(json: String): List<EvalReasonDto> =
        runCatching {
            com.fasterxml.jackson.module.kotlin
                .jacksonObjectMapper()
                .readValue(json, List::class.java)
                .filterIsInstance<Map<String, Any>>()
                .map { m ->
                    EvalReasonDto(
                        kind = m["kind"] as? String ?: "",
                        description = m["description"] as? String ?: "",
                        satisfied = m["satisfied"] as? Boolean ?: false,
                    )
                }
        }.getOrElse { emptyList() }
}
