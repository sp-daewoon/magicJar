package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.signal.EnrichedSignal
import kr.spdaewoon.magicjar.api.service.signal.SignalQueryService
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant

/**
 * 시그널 REST 컨트롤러 — T-SIG Phase 2 (ADR 0066 §5-3 + §7-2 단계 5).
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 * 룰 5 위반 금지: LLM provider import 0건.
 * 헌법 §6 (ADR 0051): Repository body fun 0건.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Tag(name = "Signal", description = "시그널 시계열 조회 — T-SIG Phase 2 (ADR 0066 §5-3)")
@RestController
@RequestMapping("/api/signals")
class SignalController(
    private val signalQueryService: SignalQueryService,
) {
    private val log = LoggerFactory.getLogger(SignalController::class.java)

    // ─── GET /api/signals ─────────────────────────────────────────────────────

    @Operation(
        summary = "시그널 시계열 조회 (페이징)",
        description =
            "사용자의 시그널 목록을 시계열로 조회합니다 (ts DESC). " +
                "from·to는 ISO-8601 UTC 형식 (예: `2026-05-12T00:00:00Z`). " +
                "ADR 0066 §5-3 정합.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "시그널 목록 조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "파라미터 형식 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
        @Parameter(
            description = "조회 시작 시각 (ISO-8601 UTC)",
            example = "2026-05-01T00:00:00Z",
            required = true,
        )
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        from: Instant,
        @Parameter(
            description = "조회 종료 시각 (ISO-8601 UTC)",
            example = "2026-05-12T23:59:59Z",
            required = true,
        )
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        to: Instant,
        @Parameter(description = "종목 코드 부분 검색 (선택)", example = "005930")
        @RequestParam(required = false) code: String?,
        @Parameter(description = "평가 인터벌 필터 (선택)", example = "1m")
        @RequestParam(required = false) interval: String?,
        @Parameter(
            description = "매매 방향 필터 (선택)",
            example = "BUY",
            schema = Schema(allowableValues = ["BUY", "SELL", "HOLD"]),
        )
        @RequestParam(required = false) side: String?,
        @Parameter(description = "발행 전략 ID 필터 (선택)", example = "8")
        @RequestParam(required = false) strategyId: Long?,
        @Parameter(description = "페이지 번호 (0-based)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기", example = "50")
        @RequestParam(defaultValue = "50") size: Int,
    ): SignalPageResponse {
        if (from.isAfter(to)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from은 to보다 이전이어야 합니다")
        }
        log.debug(
            "시그널 목록 조회: userId={} from={} to={} code={} interval={} side={} strategyId={} page={} size={}",
            userId,
            from,
            to,
            code,
            interval,
            side,
            strategyId,
            page,
            size,
        )
        val result = signalQueryService.list(userId, from, to, code, interval, side, strategyId, page, size)
        return SignalPageResponse(
            content = result.content.map { toResponse(it) },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            last = result.isLast,
        )
    }

    // ─── GET /api/signals/{id} ────────────────────────────────────────────────

    @Operation(
        summary = "시그널 단건 조회",
        description = "시그널 ID로 단건 조회합니다. userId 불일치 시 404 반환.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "시그널 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "시그널 없음 또는 소유자 불일치",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}")
    fun get(
        @Parameter(description = "시그널 ID", example = "1", required = true)
        @PathVariable id: Long,
        @Parameter(description = "소유자 사용자 ID (검증용)", example = "1", required = true)
        @RequestParam userId: String,
    ): SignalResponse {
        val signal = signalQueryService.get(userId, id)
        return toResponse(signal)
    }

    // ─── private helper ───────────────────────────────────────────────────────

    private fun toResponse(enriched: EnrichedSignal): SignalResponse {
        val signal = enriched.signal
        return SignalResponse(
            id = signal.id ?: 0L,
            strategyId = signal.strategyId,
            strategyName = enriched.strategyName,
            strategyNameKo = enriched.strategyNameKo,
            buyCondition = enriched.buyCondition,
            sellCondition = enriched.sellCondition,
            userId = signal.userId,
            code = signal.code,
            interval = signal.interval.code,
            ts = signal.ts.toString(),
            side = signal.side.name,
            strength = signal.strength,
            reason = signal.reason,
            priceAtSignal = signal.priceAtSignal,
            contextJson = signal.contextJson,
            createdAt = signal.createdAt?.toString(),
        )
    }
}

// ─── DTO ──────────────────────────────────────────────────────────────────────

@Schema(description = "시그널 단건 응답")
data class SignalResponse(
    @field:Schema(description = "시그널 ID", example = "1")
    val id: Long,
    @field:Schema(description = "시그널 발행 전략 ID", example = "1")
    val strategyId: Long,
    @field:Schema(description = "발행 전략명", example = "ma20_test")
    val strategyName: String?,
    @field:Schema(description = "발행 전략명(한글)", example = "이평선 돌파")
    val strategyNameKo: String?,
    @field:Schema(description = "발행 전략 매수 조건 DSL", example = "ma(5) crosses_above ma(20)")
    val buyCondition: String?,
    @field:Schema(description = "발행 전략 매도 조건 DSL", example = "close < ma(10)")
    val sellCondition: String?,
    @field:Schema(description = "전략 소유자 userId", example = "1")
    val userId: String,
    @field:Schema(description = "종목 코드 (KIS 6자리)", example = "005930")
    val code: String,
    @field:Schema(description = "평가 인터벌 code", example = "5m")
    val interval: String,
    @field:Schema(description = "boundary closing ts (ISO-8601 UTC)", example = "2026-05-12T00:05:00Z")
    val ts: String,
    @field:Schema(
        description = "시그널 방향",
        example = "BUY",
        allowableValues = ["BUY", "SELL", "HOLD"],
    )
    val side: String,
    @field:Schema(description = "룰 매칭 강도 0~100", example = "80")
    val strength: Int,
    @field:Schema(description = "룰 매칭 사유", example = "MA(5) > MA(20) at 09:05")
    val reason: String,
    @field:Schema(description = "평가 시점 종가", example = "73400.0000")
    val priceAtSignal: BigDecimal,
    @field:Schema(description = "평가 context JSON (indicator values)", example = "null")
    val contextJson: String?,
    @field:Schema(description = "시그널 생성 시각 (ISO-8601 UTC)", example = "2026-05-12T00:05:01Z")
    val createdAt: String?,
)

@Schema(description = "시그널 페이징 응답")
data class SignalPageResponse(
    @field:Schema(description = "시그널 목록")
    val content: List<SignalResponse>,
    @field:Schema(description = "현재 페이지 (0-based)", example = "0")
    val page: Int,
    @field:Schema(description = "페이지 크기", example = "50")
    val size: Int,
    @field:Schema(description = "총 시그널 수", example = "100")
    val totalElements: Long,
    @field:Schema(description = "총 페이지 수", example = "2")
    val totalPages: Int,
    @field:Schema(description = "마지막 페이지 여부", example = "true")
    val last: Boolean,
)
