package kr.spdaewoon.magicjar.api.controller.screener

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.spdaewoon.magicjar.api.service.screener.ScreenerService
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Screener REST 컨트롤러 — 3 endpoint (ADR 0071 §6 + spec §6 + 사용자 메모리 feedback_swagger_pattern).
 *
 * Swagger 4단 LOCK:
 * - @Tag: 컨트롤러 레벨
 * - @Operation: 메서드 레벨
 * - @Parameter: 파라미터 레벨
 * - DTO @Schema: DTO 필드 레벨
 *
 * 3 endpoint:
 * - POST /api/screener/evaluate — 동기 inline 평가 (A-1 채택)
 * - GET /api/screener/runs — 이력 조회 (옵션 영속 ON 시)
 * - GET /api/screener/runs/{id} — 단건 상세 (옵션 영속 ON 시)
 *
 * AI 호출 0 LOCK (ADR 0071 §2-9 + 개발 룰 5).
 * KIS endpoint 호출 0 LOCK.
 */
@Tag(name = "Screener", description = "종목 발굴 (universe × DSL → 후보 + 랭킹) — ADR 0071 T-SC Phase 2")
@RestController
@RequestMapping("/api/screener")
class ScreenerController(
    private val screenerService: ScreenerService,
) {
    /**
     * 전략으로 종목 찾기 (inline 동기 평가).
     *
     * 등록된 Strategy DSL을 universe(KOSPI·KOSDAQ active instrument)에 적용해 매수 후보 + 랭킹 반환.
     * 30분 내 동일 DSL + 동일 필터 재호출 시 Redis 캐시 반환 (idempotency, cacheHit=true + elapsedMs < 100ms).
     * universe ~3000 × DSL eval 500ms timeout = 최대 inline ~25s.
     * 동시 호출 시 분산락 — 1회 inline 평가 + 1회 캐시 fetch 또는 409 Conflict.
     */
    @Operation(
        summary = "전략으로 종목 찾기 (inline 평가)",
        description = """
            등록된 Strategy DSL을 universe(KOSPI·KOSDAQ active instrument)에 적용해 매수 후보 + 랭킹 반환.
            30분 내 동일 DSL + 동일 필터 재호출 시 캐시 반환 (idempotency).
            universe size ~3000 × DSL eval 500ms timeout = 최대 inline ~25s.
            동시 호출은 분산락 + 캐시 fetch 또는 409 Conflict.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "screening 결과 + 랭킹"),
            ApiResponse(responseCode = "400", description = "요청 파라미터 오류 (userId·strategyId·limit 검증 실패)"),
            ApiResponse(responseCode = "409", description = "동시 평가 중 (분산락 충돌 + 캐시 부재)"),
            ApiResponse(responseCode = "500", description = "DSL 평가 엔진 오류"),
        ],
    )
    @PostMapping("/evaluate")
    fun evaluate(
        @Valid @RequestBody request: ScreenerEvaluateRequest,
    ): ResponseEntity<ScreenerResultDto> {
        val result = screenerService.evaluate(request)
        return ResponseEntity.ok(result)
    }

    /**
     * screening 실행 이력 조회 (옵션 영속 ON 시).
     *
     * MAGICJAR_SCREENER_PERSIST=true 환경에서만 활성.
     * false 환경에서는 empty page 반환 (404 대신 빈 페이지 — feed_no_mock_data 정합).
     */
    @Operation(
        summary = "screening 실행 이력 조회 (옵션 영속 모드 한정)",
        description = """
            MAGICJAR_SCREENER_PERSIST=true 환경에서만 데이터 반환.
            영속 OFF 시 empty page 반환.
            최신순 페이징 (startedAt DESC).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "screening_run 페이징 결과 (영속 OFF 시 empty page)"),
            ApiResponse(responseCode = "400", description = "파라미터 오류"),
        ],
    )
    @GetMapping("/runs")
    fun listRuns(
        @Parameter(description = "사용자 ID (multi-tenant 격리)", example = "12345678", required = true)
        @RequestParam userId: Long,
        @Parameter(description = "페이지 번호 (0-based)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지당 결과 수 (최대 100)", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<ScreeningRunDto> {
        val safeSize = size.coerceIn(1, 100)
        return screenerService.listRuns(userId, page, safeSize)
    }

    /**
     * screening 단건 상세 조회 (옵션 영속 ON 시).
     *
     * userId mismatch 시 404 반환 (ADR 0010 multi-tenant 격리).
     * 영속 OFF 시 404 반환.
     */
    @Operation(
        summary = "screening 단건 상세 (옵션 영속 모드 한정)",
        description = """
            screening_run + screening_match 배열 상세.
            userId mismatch 시 404 반환 (multi-tenant 격리 ADR 0010).
            MAGICJAR_SCREENER_PERSIST=false 시 404 반환.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "screening_run 단건 상세 + match 배열"),
            ApiResponse(responseCode = "404", description = "미존재 또는 userId mismatch 또는 영속 모드 OFF"),
        ],
    )
    @GetMapping("/runs/{id}")
    fun findDetail(
        @Parameter(description = "screening 실행 ID", example = "1001", required = true)
        @PathVariable id: Long,
        @Parameter(description = "사용자 ID (격리 검증)", example = "12345678", required = true)
        @RequestParam userId: Long,
    ): ScreeningRunDetailDto =
        screenerService.findDetail(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "screening run 미존재 또는 접근 불가 (id=$id, userId=$userId)")
}
