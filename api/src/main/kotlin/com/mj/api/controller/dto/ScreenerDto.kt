package com.mj.api.controller.dto

import com.mj.api.application.screener.ScreenRunStatus
import com.mj.domain.strategy.screener.ScreenMatch
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate

// ──────────────────────────────────────────────────────────
// Request
// ──────────────────────────────────────────────────────────

@Schema(description = "스크리너 실행 요청")
data class ScreenRunRequest(
    @Schema(
        description = """
            대상 유니버스 타입.
            KOSPI200 / KOSDAQ150 / ETF / ETN / ALL_LISTED / CUSTOM / SYMBOL_GROUP
        """,
        example = "KOSPI200",
        allowableValues = ["KOSPI200", "KOSDAQ150", "ETF", "ETN", "ALL_LISTED", "CUSTOM", "SYMBOL_GROUP"],
        required = true,
    )
    val universeType: String,
    @Schema(
        description = """
            universeType=CUSTOM 시 필수. KIS 단축코드 6자리 목록.
            예: ["005930","000660"]
        """,
        example = "[\"005930\",\"000660\"]",
    )
    val customCodes: List<String>? = null,
    @Schema(
        description = "전략 DSL YAML 원문 (ADR 0040 .kis.yaml 포맷)",
        example = """
version: "1.0"
metadata:
  name: 골든크로스 5/20
strategy:
  id: golden_cross_5_20
  indicators:
    - id: ma
      alias: ma5
      params: {period: 5}
    - id: ma
      alias: ma20
      params: {period: 20}
  entry:
    logic: AND
    conditions:
      - lhs: {alias: ma5}
        op: cross_above
        rhs: {alias: ma20}
  exit:
    logic: AND
    conditions:
      - lhs: {alias: ma5}
        op: cross_below
        rhs: {alias: ma20}
        """,
        required = true,
    )
    val strategyYaml: String,
    @Schema(
        description = "기준일 (ISO date yyyy-MM-dd). 미지정 시 오늘.",
        example = "2026-05-02",
    )
    val asOfDate: LocalDate? = null,
)

// ──────────────────────────────────────────────────────────
// Response
// ──────────────────────────────────────────────────────────

@Schema(description = "스크리너 실행 응답 (runId 즉시 반환, 비동기)")
data class ScreenRunResponse(
    @Schema(description = "스크리닝 실행 ID (GET /api/screener/{id} 조회 시 사용)", example = "8f6c1c10-9d0e-4b2c")
    val runId: String,
    @Schema(description = "초기 상태", example = "RUNNING")
    val status: String,
    @Schema(description = "메시지", example = "스크리닝이 시작됐습니다.")
    val message: String,
)

@Schema(description = "스크리닝 상태·결과")
data class ScreenStatusResponse(
    @Schema(description = "실행 ID", example = "8f6c1c10-9d0e-4b2c")
    val runId: String,
    @Schema(description = "상태 (RUNNING / COMPLETED / FAILED)", example = "COMPLETED")
    val status: String,
    @Schema(description = "유니버스 유형", example = "KOSPI200")
    val universeType: String,
    @Schema(description = "기준일", example = "2026-05-02")
    val asOfDate: LocalDate,
    @Schema(description = "평가한 총 종목 수", example = "200")
    val totalScanned: Int,
    @Schema(description = "매칭 종목 수", example = "12")
    val matchCount: Int,
    @Schema(description = "스킵(캔들 부재 등) 종목 수", example = "3")
    val skipCount: Int,
    @Schema(description = "오류 메시지 (FAILED 시만)", example = "null")
    val errorMessage: String?,
    @Schema(description = "생성 시각", example = "2026-05-02T10:00:00Z")
    val createdAt: Instant,
    @Schema(description = "완료 시각 (완료 시만)", example = "2026-05-02T10:00:03Z")
    val finishedAt: Instant?,
)

@Schema(description = "매칭 종목 단건")
data class ScreenMatchResponse(
    @Schema(description = "종목코드 6자리", example = "005930")
    val code: String,
    @Schema(description = "한글 종목명", example = "삼성전자")
    val name: String,
    @Schema(description = "매칭 근거 (DSL 조건 trace 목록)")
    val entryReasons: List<EvalReasonDto>,
    @Schema(description = "매칭 기준 봉의 openTime", example = "2026-05-02T00:00:00Z")
    val matchedAt: Instant,
)

// EvalReasonDto는 StrategyDslDto.kt에 정의됨 (같은 패키지 com.mj.api.controller.dto)

// ──────────────────────────────────────────────────────────
// Mapper functions
// ──────────────────────────────────────────────────────────

fun ScreenRunStatus.toResponse(): ScreenStatusResponse =
    ScreenStatusResponse(
        runId = runId,
        status = status,
        universeType = universeType,
        asOfDate = asOfDate,
        totalScanned = totalScanned,
        matchCount = matchCount,
        skipCount = skipCount,
        errorMessage = errorMessage,
        createdAt = createdAt,
        finishedAt = finishedAt,
    )

fun ScreenMatch.toResponse(): ScreenMatchResponse =
    ScreenMatchResponse(
        code = code,
        name = name,
        entryReasons =
            entryReasons.map { r ->
                EvalReasonDto(kind = r.kind, description = r.description, satisfied = r.satisfied)
            },
        matchedAt = matchedAt,
    )
