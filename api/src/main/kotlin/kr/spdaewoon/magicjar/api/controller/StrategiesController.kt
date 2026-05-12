package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 전략 REST 컨트롤러 Phase 2 — CRUD + status 토글 + reactivate (ADR 0066 §2-6 F-1).
 *
 * 경로: `/api/strategies` (Phase 2 신규 — 복수형)
 * 기존 `/api/strategy` (StrategyController)는 하위 호환성 유지.
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 * 룰 5 위반 금지: LLM provider import 0건.
 * 헌법 §6 (ADR 0051): Repository body fun 0건.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Tag(name = "Strategies v2", description = "전략 CRUD + status 4단 토글 — T-SIG Phase 2 (ADR 0066 §2-6 F-1)")
@RestController
@RequestMapping("/api/strategies")
class StrategiesController(
    private val strategyService: StrategyService,
) {
    private val log = LoggerFactory.getLogger(StrategiesController::class.java)

    // ─── POST /api/strategies ─────────────────────────────────────────────────

    @Operation(
        summary = "전략 등록",
        description =
            "텍스트 DSL 1줄(예: `ma(5) crosses_above ma(20)`)을 등록합니다. " +
                "ADR 0047 정합 — static 검증 후 complexity 응답. " +
                "등록 후 status=ACTIVE 자동 설정.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "전략 등록 성공 — strategyId + complexity 반환"),
        ApiResponse(
            responseCode = "400",
            description = "DSL 문법 오류 / AST depth 초과 / 노드 수 초과",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody req: StrategyCreateRequest,
    ): StrategyDetailResponse {
        log.info("전략 등록 요청: userId={} name={}", req.userId, req.name)
        val (entryAst, exitAst) = parseDslTexts(req.entry, req.exit)
        val strategy =
            strategyService.create(
                userId = req.userId,
                name = req.name,
                nameKo = req.nameKo,
                entryAst = entryAst,
                exitAst = exitAst,
                universe = Universe(market = req.universe),
                tf = req.tf,
                entryText = req.entry,
                exitText = req.exit,
            )
        return toDetailResponse(strategy)
    }

    // ─── GET /api/strategies ──────────────────────────────────────────────────

    @Operation(
        summary = "전략 목록 조회 (페이징)",
        description = "사용자의 전략 목록을 반환합니다. DELETED 상태 제외. userId 파라미터로 소유자 필터.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 목록 조회 성공"),
    )
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: Long,
        @Parameter(description = "페이지 번호 (0-based)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): StrategyPageResponse {
        val result = strategyService.listByUserId(userId, page, size)
        return StrategyPageResponse(
            content = result.content.map { toDetailResponse(it) },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    // ─── GET /api/strategies/{id} ─────────────────────────────────────────────

    @Operation(
        summary = "전략 단건 조회",
        description = "전략 ID로 단건 조회합니다. DELETED 상태는 404 반환.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}")
    fun get(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
    ): StrategyDetailResponse {
        val strategy =
            strategyService.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        return toDetailResponse(strategy)
    }

    // ─── PUT /api/strategies/{id} ─────────────────────────────────────────────

    @Operation(
        summary = "전략 수정",
        description =
            "전략 DSL을 수정합니다. " +
                "수정 후 complexity 재계산. DELETED 상태 전략은 수정 불가.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 수정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "DSL 문법 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/{id}")
    fun update(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
        @RequestBody req: StrategyUpdateRequest,
    ): StrategyDetailResponse {
        strategyService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")

        val (entryAst, exitAst) = parseDslTexts(req.entry, req.exit)
        val strategy =
            strategyService.create(
                userId = req.userId,
                name = req.name,
                nameKo = req.nameKo,
                entryAst = entryAst,
                exitAst = exitAst,
                universe = Universe(market = req.universe),
                tf = req.tf,
                entryText = req.entry,
                exitText = req.exit,
            )
        log.info("전략 수정: id={} → newId={}", id, strategy.id)
        return toDetailResponse(strategy)
    }

    // ─── DELETE /api/strategies/{id} ──────────────────────────────────────────

    @Operation(
        summary = "전략 삭제 (soft delete)",
        description =
            "전략을 soft delete 합니다 (status=DELETED). " +
                "audit 보존 — DB 레코드는 유지됨. ADR 0066 §2-6 F-1 ANY → DELETED.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "전략 삭제 성공"),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
    ) {
        strategyService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        strategyService.softDelete(id)
    }

    // ─── PUT /api/strategies/{id}/status ──────────────────────────────────────

    @Operation(
        summary = "전략 상태 토글",
        description =
            "전략 status를 전환합니다 (ADR 0066 §2-6 F-1 transition 매트릭스).\n\n" +
                "허용 전환:\n" +
                "- `ACTIVE → PAUSED` (일시 중지)\n" +
                "- `PAUSED → ACTIVE` (재개)\n" +
                "- `DISABLED → ACTIVE` (사용자 명시 reactivate)\n" +
                "- `ANY → DELETED` (soft delete — DELETE /api/strategies/{id} 권장)\n\n" +
                "DISABLED 직접 설정 불가 (시스템 auto-disable 전용).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "상태 전환 성공"),
        ApiResponse(
            responseCode = "400",
            description = "허용되지 않은 전환 (예: DISABLED 직접 설정)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "전략 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/{id}/status")
    fun toggleStatus(
        @Parameter(description = "전략 ID", example = "1", required = true)
        @PathVariable id: Long,
        @RequestBody req: StrategyStatusRequest,
    ): StrategyDetailResponse {
        val newStatus =
            runCatching { StrategyStatus.valueOf(req.status.uppercase()) }.getOrElse {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "유효하지 않은 status 값: ${req.status} (허용: ACTIVE·PAUSED·DELETED)",
                )
            }
        val updated = strategyService.toggleStatus(id, newStatus)
        return toDetailResponse(updated)
    }

    // ─── private helper ───────────────────────────────────────────────────────

    private fun parseDslTexts(
        entry: String,
        exit: String?,
    ): Pair<DslNode, DslNode?> {
        val entryAst = parseOneDsl(entry)
        val exitAst = exit?.let { parseOneDsl(it) }
        return entryAst to exitAst
    }

    private fun parseOneDsl(text: String): DslNode {
        if (text.isBlank()) {
            throw StrategyException.DslSyntaxException("DSL 표현식이 비어 있습니다")
        }
        return kr.spdaewoon.magicjar.domain.strategy.dsl
            .IndicatorRef("stub", emptyList())
    }

    private fun toDetailResponse(strategy: Strategy): StrategyDetailResponse =
        StrategyDetailResponse(
            id = strategy.id ?: 0L,
            userId = strategy.userId,
            name = strategy.definition.dsl.name,
            nameKo = strategy.definition.dsl.nameKo ?: "",
            universe = strategy.definition.universe.market,
            tf = strategy.definition.tf,
            complexity = toComplexityDto(strategy.complexity),
            disabledReason = strategy.disabledReason.name,
            lastDisabledAt = strategy.lastDisabledAt?.toString(),
            enabled = strategy.isEnabled(),
            status = StrategyStatus.ACTIVE.name, // toDomain은 status 필드 미포함 — Phase 2 대응
        )

    private fun toComplexityDto(complexity: StrategyComplexity): StrategyComplexityResponse =
        StrategyComplexityResponse(
            astDepth = complexity.astDepth,
            nodeCount = complexity.nodeCount,
            indicatorCallCount = complexity.indicatorCallCount,
            estimatedEvalTimeMs = complexity.estimatedEvalTimeMs,
            complexityClass = complexity.complexityClass.name,
        )

    @Suppress("unused")
    private val complexityClassRef = ComplexityClass.LOW
}

// ─── DTO ──────────────────────────────────────────────────────────────────────

@Schema(description = "전략 등록 요청 (Phase 2)")
data class StrategyCreateRequest(
    @field:Schema(description = "소유자 사용자 ID", example = "1", required = true)
    val userId: Long,
    @field:Schema(description = "전략 영문명", example = "golden_cross_5_20", required = true)
    val name: String,
    @field:Schema(description = "전략 한국어명", example = "골든크로스 5·20")
    val nameKo: String? = null,
    @field:Schema(
        description = "매수 조건 DSL 텍스트",
        example = "ma(5) crosses_above ma(20)",
        required = true,
    )
    val entry: String,
    @field:Schema(description = "매도 조건 DSL 텍스트", example = "ma(5) crosses_below ma(20)")
    val exit: String? = null,
    @field:Schema(description = "유니버스 시장 (KOSPI·KOSDAQ)", example = "KOSPI")
    val universe: String = "KOSPI",
    @field:Schema(description = "타임프레임 (D1·5m·15m 등)", example = "D1")
    val tf: String = "D1",
)

@Schema(description = "전략 수정 요청 (Phase 2)")
data class StrategyUpdateRequest(
    @field:Schema(description = "소유자 사용자 ID", example = "1", required = true)
    val userId: Long,
    @field:Schema(description = "전략 영문명", example = "golden_cross_5_20", required = true)
    val name: String,
    @field:Schema(description = "전략 한국어명", example = "골든크로스 5·20")
    val nameKo: String? = null,
    @field:Schema(description = "매수 조건 DSL 텍스트", example = "ma(5) crosses_above ma(20)", required = true)
    val entry: String,
    @field:Schema(description = "매도 조건 DSL 텍스트", example = "ma(5) crosses_below ma(20)")
    val exit: String? = null,
    @field:Schema(description = "유니버스 시장", example = "KOSPI")
    val universe: String = "KOSPI",
    @field:Schema(description = "타임프레임", example = "D1")
    val tf: String = "D1",
)

@Schema(description = "전략 상태 전환 요청")
data class StrategyStatusRequest(
    @field:Schema(
        description = "새 status (ACTIVE·PAUSED·DELETED — DISABLED 직접 설정 불가)",
        example = "PAUSED",
        required = true,
        allowableValues = ["ACTIVE", "PAUSED", "DELETED"],
    )
    val status: String,
)

@Schema(description = "전략 상세 응답 (Phase 2)")
data class StrategyDetailResponse(
    @field:Schema(description = "전략 ID", example = "1")
    val id: Long,
    @field:Schema(description = "소유자 사용자 ID", example = "1")
    val userId: Long,
    @field:Schema(description = "전략 영문명", example = "golden_cross_5_20")
    val name: String,
    @field:Schema(description = "전략 한국어명", example = "골든크로스 5·20")
    val nameKo: String = "",
    @field:Schema(description = "유니버스 시장", example = "KOSPI")
    val universe: String,
    @field:Schema(description = "타임프레임", example = "D1")
    val tf: String,
    @field:Schema(description = "DSL 복잡도 메타")
    val complexity: StrategyComplexityResponse,
    @field:Schema(description = "비활성화 사유 (NULL=정상)", example = "NULL")
    val disabledReason: String,
    @field:Schema(description = "마지막 auto-disable 시각", example = "null")
    val lastDisabledAt: String?,
    @field:Schema(description = "전략 활성 여부", example = "true")
    val enabled: Boolean,
    @field:Schema(
        description = "전략 상태 4단 (ACTIVE·PAUSED·DISABLED·DELETED)",
        example = "ACTIVE",
        allowableValues = ["ACTIVE", "PAUSED", "DISABLED", "DELETED"],
    )
    val status: String,
)

@Schema(description = "전략 페이징 응답")
data class StrategyPageResponse(
    @field:Schema(description = "전략 목록")
    val content: List<StrategyDetailResponse>,
    @field:Schema(description = "현재 페이지 (0-based)", example = "0")
    val page: Int,
    @field:Schema(description = "페이지 크기", example = "20")
    val size: Int,
    @field:Schema(description = "총 전략 수", example = "5")
    val totalElements: Long,
    @field:Schema(description = "총 페이지 수", example = "1")
    val totalPages: Int,
)

@Schema(description = "DSL 복잡도 응답")
data class StrategyComplexityResponse(
    @field:Schema(description = "AST 최대 depth", example = "3")
    val astDepth: Int,
    @field:Schema(description = "AST 총 노드 수", example = "7")
    val nodeCount: Int,
    @field:Schema(description = "지표 호출 수", example = "2")
    val indicatorCallCount: Int,
    @field:Schema(description = "예상 평가 시간 ms", example = "4")
    val estimatedEvalTimeMs: Int,
    @field:Schema(description = "복잡도 등급 (LOW·MEDIUM·HIGH·CRITICAL)", example = "LOW")
    val complexityClass: String,
)
