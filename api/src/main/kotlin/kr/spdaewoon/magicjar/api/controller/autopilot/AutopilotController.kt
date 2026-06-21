package kr.spdaewoon.magicjar.api.controller.autopilot

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

/**
 * magicjar-autopilot **제어 평면 REST 컨트롤러** (spec 2026-06-22 §2-0-B·§3 · Phase 0 Task 4).
 *
 * ### 엔드포인트
 * - `GET  /api/autopilot/status`   — kill·풀 한도·손실(cum/daily/lifetime)·owner=AUTOPILOT 전략수 (read-only).
 * - `POST /api/autopilot/stop`     — 수동 kill(control STOP publish), 202.
 * - `POST /api/autopilot/resume`   — kill 해제(control RESUME publish, **reason 필수** — 빈값 400), 202.
 * - `POST /api/autopilot/rollback` — owner=AUTOPILOT 청산+PAUSE+해제(control ROLLBACK publish), 202.
 *
 * 제어(stop·resume·rollback)는 api → Kafka cmd → consumer `AutopilotControlListener` 로 비동기 처리한다
 * (api 는 consumer 모듈 의존 0 — 모듈 경계 LOCK. `ReconcileManualTriggerService` 패턴). 202 = 접수 완료(처리는 consumer).
 *
 * ## 사용자 결재(2026-06-22)
 * kill 은 종료가 아니라 **학습 체크포인트** — Claude 가 사후분석·보정 후 RESUME 으로 재개한다(보정 사유 body 의무).
 * 단, lifetime 손실이 풀 전소(하드 플로어)면 consumer 가 RESUME 을 코드로 거부한다(사용자 재가만 해제).
 *
 * Swagger 4단 어노테이션(컨트롤러/메서드/파라미터/DTO + 한국어 설명 + example — feedback_swagger_pattern 정합).
 * 헌법 §6 (ADR 0051): Repository body fun 0건. 룰 5: LLM provider import 0건.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 제어 (ADR 0023 §2-4 정합)
 */
@Tag(name = "Autopilot", description = "magicjar-autopilot 자율 루프 제어 평면 — status·stop·resume·rollback (spec 2026-06-22 Phase 0 Task 4)")
@RestController
@RequestMapping("/api/autopilot")
class AutopilotController(
    private val statusService: AutopilotStatusService,
    private val controlProducer: AutopilotControlProducer,
) {
    private val log = LoggerFactory.getLogger(AutopilotController::class.java)

    // ─── GET /api/autopilot/status ────────────────────────────────────────────

    @Operation(
        summary = "autopilot 상태 조회",
        description =
            "자율 루프 안전 하니스 상태를 조회합니다(read-only). " +
                "kill switch 활성 여부 · 풀/건당/동시보유/일일·누적 손실 한도(%) · " +
                "현재 손실(누적·당일·영구 lifetime) · owner=AUTOPILOT 전략 수를 반환합니다. " +
                "lifetimeRealizedLoss 는 kill 해제(reset)에도 보존되는 하드 플로어 추적 값입니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "상태 조회 성공"),
        ApiResponse(
            responseCode = "500",
            description = "서버 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/status")
    fun status(): AutopilotStatusResponse {
        log.debug("autopilot status 조회")
        return statusService.status()
    }

    // ─── POST /api/autopilot/stop ─────────────────────────────────────────────

    @Operation(
        summary = "autopilot 수동 정지(kill)",
        description =
            "kill switch 를 **수동 발동**합니다. consumer 가 owner=AUTOPILOT 전략 전부 PAUSE + REAL 경로 freeze + " +
                "사후분석 저널 기록 + Slack P0 를 수행합니다(비동기). 사용자 수동 전략·후성은 비간섭(격리). " +
                "이미 kill 상태면 멱등(추가 알림 없음).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "STOP 접수(consumer 비동기 처리)"),
        ApiResponse(
            responseCode = "503",
            description = "제어 cmd publish 실패",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/stop")
    fun stop(
        @RequestBody(required = false) body: AutopilotStopRequest?,
    ): ResponseEntity<AutopilotControlAccepted> {
        val requestId = controlProducer.publish("STOP", body?.reason, null)
        log.warn("autopilot STOP 접수: requestId={} reason={}", requestId, body?.reason)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AutopilotControlAccepted("STOP", requestId))
    }

    // ─── POST /api/autopilot/resume ───────────────────────────────────────────

    @Operation(
        summary = "autopilot 재개(kill 해제)",
        description =
            "kill switch 를 **해제**합니다(학습 체크포인트 — Claude 사후분석·보정 후 재개). " +
                "**reason 필수**(빈 문자열이면 400). correctedLimits 로 한도를 더 보수적으로 보정 가능(부분 patch). " +
                "consumer 가 하드 플로어(lifetime 손실 ≥ 풀 전소)를 검사해 초과 시 **해제를 거부**합니다(사용자 재가 필요). " +
                "해제 시 누적·당일 손실 envelope 는 리셋되지만 lifetime 은 보존됩니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "RESUME 접수(consumer 가 하드 플로어 검사 후 해제/거부)"),
        ApiResponse(
            responseCode = "400",
            description = "reason 누락(빈 문자열)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "제어 cmd publish 실패",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/resume")
    fun resume(
        @RequestBody body: AutopilotResumeRequest,
    ): ResponseEntity<AutopilotControlAccepted> {
        if (body.reason.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "resume reason 은 필수입니다(사후분석 사유 기재 의무).")
        }
        val requestId = controlProducer.publish("RESUME", body.reason, body.correctedLimits)
        log.warn("autopilot RESUME 접수: requestId={} reason={} correctedLimits={}", requestId, body.reason, body.correctedLimits)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AutopilotControlAccepted("RESUME", requestId))
    }

    // ─── POST /api/autopilot/rollback ─────────────────────────────────────────

    @Operation(
        summary = "autopilot 롤백(청산+PAUSE+해제)",
        description =
            "owner=AUTOPILOT 포지션을 **시장가 청산** + 전략 전부 PAUSE + kill 해제(kill 상태면)합니다(비동기). " +
                "사용자 수동 전략·후성은 절대 비대상(격리 LOCK). 코드 되감기(git reset)는 본 API 가 아니라 " +
                "scripts/autopilot-rollback.mjs 안내를 따릅니다(돈 청산만 본 API 경유 — KIS 실주문).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "ROLLBACK 접수(consumer 비동기 청산·PAUSE·해제)"),
        ApiResponse(
            responseCode = "503",
            description = "제어 cmd publish 실패",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/rollback")
    fun rollback(
        @RequestBody(required = false) body: AutopilotRollbackRequest?,
    ): ResponseEntity<AutopilotControlAccepted> {
        val requestId = controlProducer.publish("ROLLBACK", body?.reason, null)
        log.warn("autopilot ROLLBACK 접수: requestId={} reason={}", requestId, body?.reason)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AutopilotControlAccepted("ROLLBACK", requestId))
    }
}

// ─── DTO ──────────────────────────────────────────────────────────────────────

/** autopilot 상태 응답(read-only). */
@Schema(description = "autopilot 자율 루프 안전 하니스 상태")
data class AutopilotStatusResponse(
    @Schema(description = "kill switch 활성 여부 — true면 owner=AUTOPILOT REAL 경로 freeze", example = "false")
    val killActive: Boolean,
    @Schema(description = "루프 전용 자본 풀 상한 = 계좌 순자산 %", example = "5.000")
    val poolPct: BigDecimal,
    @Schema(description = "건당 진입 캡 = 풀 %", example = "20.000")
    val perTradePct: BigDecimal,
    @Schema(description = "동시 보유 종목수 상한", example = "3")
    val maxPositions: Int,
    @Schema(description = "일일 손실 한도 = 풀 % → 당일 halt", example = "30.000")
    val dailyLossPct: BigDecimal,
    @Schema(description = "누적 손실 한도 = 풀 % → kill switch", example = "50.000")
    val cumLossPct: BigDecimal,
    @Schema(description = "루프 누적 실현 손실(양수=손실, 원). kill 해제 시 리셋", example = "0.0000")
    val cumRealizedLoss: BigDecimal,
    @Schema(description = "당일 실현 손실(양수=손실, 원)", example = "0.0000")
    val dailyRealizedLoss: BigDecimal,
    @Schema(description = "영구 누적 실현 손실(하드 플로어 — kill 해제에도 보존, 원)", example = "0.0000")
    val lifetimeRealizedLoss: BigDecimal,
    @Schema(description = "owner=AUTOPILOT 미삭제 전략 수", example = "0")
    val autopilotStrategyCount: Long,
)

/** STOP 요청(사유 옵션). */
@Schema(description = "autopilot STOP 요청")
data class AutopilotStopRequest(
    @Schema(description = "정지 사유(옵션)", example = "이상 손실 감지 — 수동 정지")
    val reason: String? = null,
)

/** RESUME 요청 — reason 필수(빈값 400). */
@Schema(description = "autopilot RESUME 요청 — reason 필수")
data class AutopilotResumeRequest(
    @Schema(description = "재개 사유(필수 — 사후분석 결론)", example = "누적손실 원인=전략42 과적합. PAUSE 후 풀 3%로 축소 재개")
    val reason: String = "",
    @Schema(description = "보정 한도(옵션 — 부분 patch, null 필드는 기존 유지)")
    val correctedLimits: AutopilotCorrectedLimits? = null,
)

/** ROLLBACK 요청(사유 옵션). */
@Schema(description = "autopilot ROLLBACK 요청")
data class AutopilotRollbackRequest(
    @Schema(description = "롤백 사유(옵션)", example = "Phase 0 검증 종료 — owner=AUTOPILOT 전량 청산")
    val reason: String? = null,
)

/**
 * RESUME 보정 한도(부분 patch) — consumer `AutopilotLimitCorrection` 와 필드 1:1 (Kafka JSON 계약).
 * null 필드는 기존 값 유지.
 */
@Schema(description = "autopilot 보정 한도(부분 patch — null 필드는 기존 유지)")
data class AutopilotCorrectedLimits(
    @Schema(description = "풀 상한 = 순자산 %", example = "3.0")
    val poolPct: BigDecimal? = null,
    @Schema(description = "건당 진입 캡 = 풀 %", example = "15.0")
    val perTradePct: BigDecimal? = null,
    @Schema(description = "동시 보유 종목수 상한", example = "2")
    val maxPositions: Int? = null,
    @Schema(description = "일일 손실 한도 = 풀 %", example = "20.0")
    val dailyLossPct: BigDecimal? = null,
    @Schema(description = "누적 손실 한도 = 풀 %", example = "40.0")
    val cumLossPct: BigDecimal? = null,
)

/** 제어 접수 응답(202) — action + 추적 requestId. */
@Schema(description = "autopilot 제어 접수 응답(202)")
data class AutopilotControlAccepted(
    @Schema(description = "접수된 제어 action", example = "STOP")
    val action: String,
    @Schema(description = "멱등·추적용 요청 ID(UUID)", example = "3f2a...")
    val requestId: String,
)
