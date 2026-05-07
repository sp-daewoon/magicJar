package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import java.time.Instant

/**
 * 관심종목 등록 요청 (spec §4-1-2 LOCK).
 * 6자리 숫자 검증 — 옵션 C 채택 (raw code 저장, T-IM1에서 보강).
 */
@Schema(description = "관심종목 등록 요청")
data class WatchlistAddRequest(
    @field:Pattern(regexp = "^\\d{6}$", message = "종목 코드는 6자리 숫자여야 합니다 (예: 005930)")
    @Schema(description = "KIS 종목 코드 6자리 (KOSPI·KOSDAQ·ETF·ETN)", example = "005930")
    val code: String,
    @Schema(description = "우선순위 (낮은 값 = 높은 우선순위, null = 0 기본)", example = "0", nullable = true)
    val priority: Int? = null,
)

/**
 * 관심종목 응답 DTO (spec §4-1-1 LOCK).
 */
@Schema(description = "관심종목 응답")
data class WatchlistEntryDto(
    @Schema(description = "관심종목 ID", example = "1")
    val id: Long,
    @Schema(description = "사용자 ID (KIS 계좌번호 8자리)", example = "74683223")
    val userId: String,
    @Schema(description = "KIS 종목 코드 6자리", example = "005930")
    val code: String,
    @Schema(description = "우선순위 (낮은 값 = 높은 우선순위)", example = "0")
    val priority: Int,
    @Schema(description = "등록 시각 (UTC ISO-8601)", example = "2026-05-07T10:23:45.123Z")
    val createdAt: Instant,
)
