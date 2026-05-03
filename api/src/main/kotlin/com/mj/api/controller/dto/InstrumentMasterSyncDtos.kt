package com.mj.api.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "종목 마스터파일 수동 수집 트리거 요청")
data class InstrumentMasterSyncRequest(
    @field:Schema(
        description = "수집 대상 시장 (기본: [KOSPI, KOSDAQ, KONEX]). ETF·ETN은 KOSPI·KOSDAQ mst에 포함됨.",
        example = "[\"KOSPI\", \"KOSDAQ\"]",
        nullable = true,
    )
    val markets: List<String>? = null,
)

@Schema(description = "종목 마스터파일 수집 트리거 응답")
data class InstrumentMasterSyncResponse(
    @field:Schema(description = "실행 ID — 상태 조회에 사용", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    val runId: String,
    @field:Schema(description = "시작 상태 (STARTED / BATCH_APP_UNAVAILABLE)", example = "STARTED")
    val status: String,
    @field:Schema(description = "트리거 시각 (ISO-8601)", example = "2026-05-02T12:00:00+09:00")
    val scheduledAt: String,
)

@Schema(description = "종목 마스터파일 수집 상태 응답")
data class InstrumentMasterSyncStatusResponse(
    @field:Schema(description = "실행 ID")
    val runId: String,
    @field:Schema(description = "상태 (STARTED / COMPLETED / FAILED)")
    val status: String,
    @field:Schema(description = "트리거 시각", nullable = true)
    val scheduledAt: String?,
    @field:Schema(description = "완료 시각 (COMPLETED·FAILED 시 설정)", nullable = true)
    val completedAt: String?,
    @field:Schema(description = "오류 메시지 (FAILED 시 설정)", nullable = true)
    val errorMessage: String?,
)
