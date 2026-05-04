package com.mj.api.controller

import com.mj.api.application.auth.AuthService
import com.mj.api.application.auth.KisMode
import com.mj.api.application.auth.LoginRequest
import com.mj.api.application.auth.ModeChangeRequest
import com.mj.api.controller.dto.AuthStatusResponse
import com.mj.api.controller.dto.LoginRequestDto
import com.mj.api.controller.dto.LoginResponse
import com.mj.api.controller.dto.ModeChangeRequestDto
import com.mj.api.controller.dto.ModeChangeResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * KIS 인증 REST endpoint.
 *
 * - GET  /api/auth/status  — 인증 상태 조회
 * - POST /api/auth/login   — App Key·Secret 저장 + 모드 설정
 * - POST /api/auth/mode    — 모드 전환 (vps ↔ prod)
 *
 * 인증 도입 시(ISSUE-006 후속) userId는 SecurityContext에서 추출.
 * 1차에는 ?userId query param 또는 default 시드 사용자 UUID.
 *
 * 보안 원칙:
 * - 응답에 appSecret 평문 미포함.
 * - 로그는 appKey 앞 4자리만 출력, appSecret은 *** 마스킹.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "KIS OpenAPI 자격증명 관리 — App Key·Secret 저장, 모의/실전 모드 전환")
class AuthController(
    private val authService: AuthService,
) {
    @Operation(
        summary = "KIS 인증 상태 조회",
        description = """
            사용자의 현재 KIS 인증 상태를 반환한다.

            - authenticated: appKey·appSecret 모두 설정된 경우 true
            - mode: `vps`(모의투자) 또는 `prod`(실전투자)
            - 자격증명 미설정 시 authenticated=false, mode=null
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "상태 조회 성공"),
        ],
    )
    @GetMapping("/status", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun status(
        @Parameter(description = "사용자 ID (기본: DEFAULT_USER_ID)", example = "00000000-0000-0000-0000-000000000001")
        @RequestParam(required = false) userId: String?,
    ): ResponseEntity<AuthStatusResponse> {
        val result = authService.status(userId ?: DEFAULT_USER_ID)
        return ResponseEntity.ok(
            AuthStatusResponse(
                authenticated = result.authenticated,
                mode = result.mode?.value,
                accountNo = result.accountNo,
                hasAppKey = result.hasAppKey,
                hasAppSecret = result.hasAppSecret,
                lastUpdatedAt = result.lastUpdatedAt?.toString(),
            ),
        )
    }

    @Operation(
        summary = "KIS 자격증명 저장 (로그인)",
        description = """
            App Key·App Secret을 AES-256-GCM 암호화하여 DB에 저장하고 거래 모드를 설정한다.

            - mode: `vps`(모의투자, KIS 개발계), `prod`(실전투자, KIS 운영계)
            - appKey·appSecret: KIS OpenAPI 포털에서 발급한 키
            - accountNo: 계좌번호 10자리 (예: 5000123456)
            - accountProductCode: 계좌상품코드 2자리 (기본: "01")

            **보안**: 응답에 secret 미포함. 로그에 appKey 앞 4자리만 출력.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "자격증명 저장 성공"),
            ApiResponse(responseCode = "400", description = "요청 유효성 오류 (필수 필드 누락 등)"),
        ],
    )
    @PostMapping("/login", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun login(
        @Parameter(description = "사용자 ID (기본: DEFAULT_USER_ID)", example = "00000000-0000-0000-0000-000000000001")
        @RequestParam(required = false) userId: String?,
        @Valid @RequestBody body: LoginRequestDto,
    ): ResponseEntity<LoginResponse> {
        val result =
            authService.login(
                userId ?: DEFAULT_USER_ID,
                LoginRequest(
                    mode = KisMode.fromValue(body.mode),
                    appKey = body.appKey,
                    appSecret = body.appSecret,
                    accountNo = body.accountNo,
                    accountProductCode = body.accountProductCode ?: "01",
                    htsId = body.htsId,
                ),
            )
        return ResponseEntity.ok(
            LoginResponse(
                authenticated = result.authenticated,
                mode = result.mode.value,
            ),
        )
    }

    @Operation(
        summary = "거래 모드 전환 (vps ↔ prod)",
        description = """
            거래 모드를 전환한다. 자격증명(appKey·appSecret)이 먼저 설정되어 있어야 한다.

            - `vps`: 모의투자 (KIS 개발계 endpoint — openapivts.koreainvestment.com)
            - `prod`: 실전투자 (KIS 운영계 endpoint — openapi.koreainvestment.com)

            자격증명 미설정 시 success=false + reason 반환.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "모드 전환 성공 또는 실패 (success 필드로 구분)"),
            ApiResponse(responseCode = "400", description = "요청 유효성 오류"),
        ],
    )
    @PostMapping("/mode", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun switchMode(
        @Parameter(description = "사용자 ID (기본: DEFAULT_USER_ID)", example = "00000000-0000-0000-0000-000000000001")
        @RequestParam(required = false) userId: String?,
        @Valid @RequestBody body: ModeChangeRequestDto,
    ): ResponseEntity<ModeChangeResponse> {
        val result = authService.switchMode(userId ?: DEFAULT_USER_ID, ModeChangeRequest(KisMode.fromValue(body.mode)))
        return ResponseEntity.ok(
            ModeChangeResponse(
                success = result.success,
                mode = result.mode?.value,
                reason = result.reason,
            ),
        )
    }

    companion object {
        const val DEFAULT_USER_ID: String = "74683223"
    }
}

// ─── Request/Response DTOs (controller layer) ─────────────────────────────────

// (DTOs are defined in dto package — see AuthDtos.kt)
