package com.mj.api.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "개발일지 단일 항목 메타")
data class DevlogEntry(
    @Schema(description = "일자 (YYYY-MM-DD, KST 기준)", example = "2026-05-03")
    val date: String,
    @Schema(description = "요일 (한국어)", example = "토")
    val dayOfWeek: String,
    @Schema(description = "파일 마지막 수정 시각 (ISO-8601 UTC)", example = "2026-05-03T16:30:00Z")
    val mtime: Instant,
    @Schema(description = "파일 크기 bytes", example = "12345")
    val sizeBytes: Long,
    @Schema(description = "한 줄 요약 (있으면 — index.html 또는 본문 메타에서 추출)", example = "v2 베이스라인 + W1·W2·W3·W4 완료 (24 PR 머지)")
    val summary: String?,
)

@Schema(description = "개발일지 목록 응답")
data class DevlogListResponse(
    @Schema(description = "최신 우선 정렬된 일지 목록")
    val entries: List<DevlogEntry>,
)
