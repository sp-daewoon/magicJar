package com.mj.api.application.backtest

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

@Schema(description = "백테스트 트리거 요청. 전략·종목·기간 3원소.")
data class BacktestTriggerRequest(
    @field:Schema(description = "전략 식별자 (사전 등록된 키)", example = "sma-cross-5-20", required = true)
    @field:NotBlank
    val strategyId: String,
    @field:Schema(description = "KRX 6자리 종목 코드", example = "005930", required = true)
    @field:NotBlank
    val symbol: String,
    @field:Schema(description = "백테스트 시작일 (ISO yyyy-MM-dd)", example = "2024-01-01", required = true)
    @field:Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
    val from: String,
    @field:Schema(description = "백테스트 종료일 (ISO yyyy-MM-dd, from 이후)", example = "2024-12-31", required = true)
    @field:Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
    val to: String,
)

@Schema(description = "백테스트 트리거 응답. 잡이 큐에 들어간 직후 즉시 반환된다.")
data class BacktestTriggerResponse(
    @field:Schema(description = "백테스트 실행 식별자. 이후 GET /api/backtest/{runId}로 폴링.", example = "8f6c1c10-9d0e-4b2c-a5b6-1234567890ab")
    val runId: String,
    @field:Schema(description = "잡 상태. trigger 직후엔 항상 QUEUED.", example = "QUEUED")
    val status: BacktestRunStatus,
    @field:Schema(description = "사람이 읽을 수 있는 메시지", example = "Backtest queued")
    val message: String,
)

@Schema(description = "백테스트 상태/결과 조회 응답.")
data class BacktestStatusResponse(
    @field:Schema(description = "백테스트 실행 식별자", example = "8f6c1c10-9d0e-4b2c-a5b6-1234567890ab")
    val runId: String,
    @field:Schema(description = "전략 식별자", example = "sma-cross-5-20")
    val strategyId: String,
    @field:Schema(description = "종목 코드", example = "005930")
    val symbol: String,
    @field:Schema(description = "시작일", example = "2024-01-01")
    val from: String,
    @field:Schema(description = "종료일", example = "2024-12-31")
    val to: String,
    @field:Schema(description = "현재 상태")
    val status: BacktestRunStatus,
    @field:Schema(description = "잡 시작 시각 (ISO 8601, RUNNING 진입 후 채워짐)", example = "2026-04-28T14:30:00")
    val startedAt: String?,
    @field:Schema(description = "잡 종료 시각 (ISO 8601, COMPLETED/FAILED 시 채워짐)", example = "2026-04-28T14:31:42")
    val finishedAt: String?,
    @field:Schema(
        description = "결과 요약 JSON (COMPLETED 시). 전략별 스키마 — 보통 누적수익률·승률·MDD·체결 수.",
        example = "{\"netReturn\":0.123,\"winRate\":0.55,\"mdd\":-0.082,\"trades\":42}",
    )
    val summaryJson: String?,
    @field:Schema(description = "FAILED 시 에러 메시지", example = "KIS rate limit exceeded")
    val errorMessage: String?,
)

@Schema(description = "백테스트 잡 라이프사이클 상태")
enum class BacktestRunStatus {
    @Schema(description = "큐 등록만 됨, 아직 실행 전")
    QUEUED,

    @Schema(description = "batch-app 워커가 실행 중")
    RUNNING,

    @Schema(description = "정상 완료. summaryJson 참조")
    COMPLETED,

    @Schema(description = "실행 실패. errorMessage 참조")
    FAILED,
}
