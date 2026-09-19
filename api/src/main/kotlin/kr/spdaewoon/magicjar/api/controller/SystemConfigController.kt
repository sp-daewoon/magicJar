package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.spdaewoon.magicjar.api.service.KillSwitchService
import kr.spdaewoon.magicjar.api.service.KisCredentialChangeService
import kr.spdaewoon.magicjar.api.service.OnboardingService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// ADR 0074 §2-3 + spec §2-3 정합
// 2 endpoint:
//   GET /api/system-config/kill-switch   — 현재 상태 조회
//   PUT /api/system-config/kill-switch   — 수동 토글
//
// Extension point: T-AI 진입 시 /api/system-config/ai-credentials 동일 controller mount 가능 (ADR 0074 §2-4-2).
//
// 인증 정책: OnboardingGuardFilter에서 /api/system-config/ 패스 추가 — KisCredentialChangeService.resolveUserId() 로 userId 결정.
// ADR 0043 §2-3: KillSwitch OFF = 사용자 수동만 (자동 OFF 0 LOCK).

/**
 * 시스템 설정 컨트롤러 — KillSwitch 수동 토글 (ADR 0074 §2-3 + spec §2-3 정합).
 *
 * SettingsModal Section 4: 자본 보호 (KillSwitchSection).
 * T-AI 진입 시 AiCredentialSection endpoint 동일 controller 합류 예정 (ADR 0074 §2-4-2).
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
@Tag(
    name = "시스템 설정",
    description =
        "SettingsModal — KillSwitch 수동 토글 + 향후 AI 키 카테고리 (ADR 0074 §2-3·§2-4). " +
            "KillSwitch OFF = 사용자 수동만 (ADR 0043 §2-3 자동 OFF 0 LOCK).",
)
@RestController
@RequestMapping("/api/system-config")
class SystemConfigController(
    private val killSwitchService: KillSwitchService,
    private val kisCredentialChangeService: KisCredentialChangeService,
    private val onboardingService: OnboardingService,
) {
    @Operation(
        summary = "시스템 전역 설정 조회",
        description =
            "DART API 키와 Slack webhook URL을 조회합니다. " +
                "DB 저장은 AES-256-GCM 암호문을 유지하고, 설정 화면 재진입 UX를 위해 응답에서만 복호화 평문을 반환합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "500",
            description = "시스템 설정 row 없음 또는 DB 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping
    fun getSystemConfig(): SystemConfigResponse = onboardingService.getSystemConfig()

    @Operation(
        summary = "활성 주문 모드 변경 (재기동 없는 MOCK/REAL 전환)",
        description =
            "주문 경로의 활성 모드를 REAL 또는 MOCK로 전환합니다(감사 2026-09-19 §5). " +
                "시세/차트/지표는 이와 무관하게 항상 REAL로 조회됩니다. " +
                "MOCK 전환은 항상 허용됩니다. REAL 전환은 env 상한(MAGICJAR_MODE=REAL + MAGICJAR_ALLOW_REAL=true)과 " +
                "사용자 실거래 동의가 모두 충족될 때만 허용되며, 하나라도 미충족 시 412를 반환합니다. " +
                "저장 후에도 실 REAL 주문은 TradingModeGate 3중 게이트를 다시 통과해야 합니다(이중 방어).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공 — 적용된 활성 모드 반환"),
        ApiResponse(
            responseCode = "400",
            description = "mode 값 오류 (REAL/MOCK 외)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "412",
            description = "REAL 전환 조건 미충족 (env 상한 잠금 또는 동의 미완)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/trading-mode")
    fun updateTradingMode(
        @Parameter(description = "활성 주문 모드 변경 요청 (REAL/MOCK)")
        @Valid
        @RequestBody request: TradingModeUpdateRequest,
    ): TradingModeUpdateResponse = onboardingService.updateActiveTradingMode(request.mode)

    @Operation(
        summary = "KillSwitch 현재 상태 조회",
        description =
            "KillSwitch enabled/reason/updatedAt/updatedBy를 반환합니다. " +
                "DB 단일 source — Redis cache 0건 (ADR 0043 §2-5). " +
                "row 없으면 enabled=false 초기 상태 반환 (합성 데이터 없음).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "412",
            description = "온보딩 미완료 — userId 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/kill-switch")
    fun getKillSwitchState(): KillSwitchStateResponse {
        val userId = kisCredentialChangeService.resolveUserId()
        return killSwitchService.getState(userId)
    }

    @Operation(
        summary = "KillSwitch 수동 토글",
        description =
            "KillSwitch를 ON 또는 OFF로 전환합니다. " +
                "사유(reason) 5자 이상 64자 이하 필수. " +
                "성공 시 Kafka killswitch.engaged.v1 publish (schema 확장 — engaged:boolean). " +
                "60초 cooldown은 frontend 담당 (backend 강제 없음 — ADR 0074 §3-4 §j). " +
                "ADR 0043 §2-3: KillSwitch OFF = 사용자 수동만 (자동 OFF 0 LOCK). " +
                "ON 시: T-O OrderRouter 주문 전면 차단. OFF 시: 자동 매매 재개.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "토글 성공 — 새 상태 반환"),
        ApiResponse(
            responseCode = "400",
            description = "입력값 오류 (reason 길이 미달 등)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "412",
            description = "온보딩 미완료 — userId 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/kill-switch")
    fun toggleKillSwitch(
        @Parameter(description = "KillSwitch 토글 요청 — engaged + 사유 필수")
        @Valid
        @RequestBody request: KillSwitchToggleRequest,
    ): KillSwitchStateResponse {
        val userId = kisCredentialChangeService.resolveUserId()
        return killSwitchService.toggle(userId, request)
    }
}
