package com.mj.api.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

// ─── Request DTOs ─────────────────────────────────────────────────────────────

@Schema(description = "KIS 자격증명 저장 요청")
data class LoginRequestDto(
    @field:Schema(
        description = "거래 모드 — vps(모의투자) 또는 prod(실전투자)",
        example = "vps",
        allowableValues = ["vps", "prod"],
    )
    @field:NotBlank
    @field:Pattern(regexp = "vps|prod", message = "mode는 'vps' 또는 'prod'만 허용")
    val mode: String,
    @field:Schema(description = "KIS App Key (KIS 개발자 포털 발급)", example = "PSYmn3sPxxxxxx")
    @field:NotBlank
    @field:Size(min = 10, max = 128, message = "appKey 길이 오류 (10~128자)")
    val appKey: String,
    @field:Schema(description = "KIS App Secret (KIS 개발자 포털 발급 — 응답에 미포함)", example = "NOT_RETURNED")
    @field:NotBlank
    @field:Size(min = 10, max = 256, message = "appSecret 길이 오류 (10~256자)")
    val appSecret: String,
    @field:Schema(description = "계좌번호 10자리", example = "5000123456")
    @field:NotBlank
    @field:Size(min = 8, max = 12, message = "accountNo 길이 오류 (8~12자)")
    val accountNo: String,
    @field:Schema(description = "계좌상품코드 2자리 (기본: 01)", example = "01", defaultValue = "01")
    val accountProductCode: String? = "01",
    @field:Schema(description = "HTS ID (KIS 홈트레이딩 ID, 선택)", example = "thdeodns")
    val htsId: String? = null,
)

@Schema(description = "거래 모드 전환 요청")
data class ModeChangeRequestDto(
    @field:Schema(
        description = "전환할 모드 — vps(모의투자) 또는 prod(실전투자)",
        example = "prod",
        allowableValues = ["vps", "prod"],
    )
    @field:NotBlank
    @field:Pattern(regexp = "vps|prod", message = "mode는 'vps' 또는 'prod'만 허용")
    val mode: String,
)

// ─── Response DTOs ────────────────────────────────────────────────────────────

@Schema(description = "KIS 인증 상태 응답")
data class AuthStatusResponse(
    @field:Schema(description = "인증 여부 (appKey·appSecret 모두 설정됨)", example = "true")
    val authenticated: Boolean,
    @field:Schema(description = "현재 거래 모드 (vps/prod), 미설정 시 null", example = "vps", nullable = true)
    val mode: String?,
    @field:Schema(description = "계좌번호, 미설정 시 null", example = "5000123456", nullable = true)
    val accountNo: String?,
    @field:Schema(description = "App Key 설정 여부", example = "true")
    val hasAppKey: Boolean,
    @field:Schema(description = "App Secret 설정 여부 (응답에 secret 평문 미포함)", example = "true")
    val hasAppSecret: Boolean,
    @field:Schema(description = "마지막 자격증명 갱신 시각 (ISO-8601), 미설정 시 null", example = "2026-05-02T12:00:00+09:00", nullable = true)
    val lastUpdatedAt: String?,
)

@Schema(description = "로그인(자격증명 저장) 응답")
data class LoginResponse(
    @field:Schema(description = "인증 성공 여부", example = "true")
    val authenticated: Boolean,
    @field:Schema(description = "설정된 거래 모드 (vps/prod)", example = "vps")
    val mode: String,
)

@Schema(description = "모드 전환 응답")
data class ModeChangeResponse(
    @field:Schema(description = "전환 성공 여부", example = "true")
    val success: Boolean,
    @field:Schema(description = "전환된 모드 (success=true 시 설정), null if failed", example = "prod", nullable = true)
    val mode: String?,
    @field:Schema(description = "실패 사유 (success=false 시 설정)", example = "자격증명이 설정되지 않았습니다.", nullable = true)
    val reason: String?,
)
