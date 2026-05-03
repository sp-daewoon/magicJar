package com.mj.api.controller.dto

import com.mj.domain.strategy.signal.event.SignalGeneratedEvent
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

// ────────────────────────────────────────────────────────────
// Signal 이력 응답
// ────────────────────────────────────────────────────────────

@Schema(description = "Signal 이력 1건 — `signal.generated.v1` Kafka 메시지의 사용자 노출형")
data class SignalHistoryItemResponse(
    @Schema(description = "이벤트 UUID — dedup·tracking", example = "0a8b1f48-7e2a-4d8b-8b5b-9e2c5b7d1f10")
    val eventId: UUID,
    @Schema(description = "전략 id", example = "golden_cross_5_20")
    val strategyId: String,
    @Schema(description = "종목코드", example = "005930")
    val symbol: String,
    @Schema(description = "BUY / SELL / HOLD (이력은 BUY/SELL만 — HOLD는 미발행)", example = "BUY")
    val action: String,
    @Schema(description = "강도 0.0~1.0", example = "1.0")
    val strength: Double,
    @Schema(description = "시그널 발생 시각 (epoch second UTC)", example = "1746263400")
    val generatedAt: Long,
    @Schema(description = "평가 기준 봉의 openTime (epoch second UTC)", example = "1746263400")
    val evaluatedAt: Long,
    @Schema(description = "조건 trace description 리스트")
    val reasons: List<String>,
    @Schema(description = "사용자 식별자 (anonymous=null)", example = "user-123")
    val userId: String?,
)

fun SignalGeneratedEvent.toResponse(): SignalHistoryItemResponse =
    SignalHistoryItemResponse(
        eventId = eventId,
        strategyId = strategyId,
        symbol = symbol,
        action = action,
        strength = strength,
        generatedAt = generatedAt.epochSecond,
        evaluatedAt = evaluatedAt.epochSecond,
        reasons = reasons,
        userId = userId,
    )

@Schema(description = "Signal 이력 페이지 응답")
data class SignalHistoryResponse(
    @Schema(description = "결과 총 개수", example = "100")
    val count: Int,
    @Schema(description = "이력 항목 (시간 내림차순)")
    val items: List<SignalHistoryItemResponse>,
)

// ────────────────────────────────────────────────────────────
// Signal Test (임의 candles + dsl 평가) — 빌더 UI에서 호출
// ────────────────────────────────────────────────────────────

@Schema(description = "Signal Engine 테스트 요청 — 임의 yaml + 캔들로 평가")
data class SignalTestRequest(
    @Schema(description = "전략 yaml (StrategyDsl)")
    val yaml: String,
    @Schema(description = "종목 코드 (가상 OK)", example = "005930")
    val symbol: String,
    @Schema(description = "캔들 시계열 (시간 오름차순)")
    val candles: List<CandleInputDto>,
    @Schema(description = "평가 기준 봉 인덱스 (미지정 시 마지막 봉)", example = "99")
    val asOfBar: Int? = null,
    @Schema(description = "사용자 식별자 (옵션)", example = "user-123")
    val userId: String? = null,
)

@Schema(description = "Signal Engine 테스트 응답")
data class SignalTestResponse(
    @Schema(description = "전략 id", example = "golden_cross_5_20")
    val strategyId: String,
    @Schema(description = "종목 코드", example = "005930")
    val symbol: String,
    @Schema(description = "BUY / SELL / HOLD", example = "BUY")
    val action: String,
    @Schema(description = "강도 0.0~1.0", example = "1.0")
    val strength: Double,
    @Schema(description = "평가 기준 봉의 epoch second", example = "1746263400")
    val evaluatedAt: Long,
    @Schema(description = "이벤트 UUID")
    val eventId: UUID,
    @Schema(description = "조건 trace description")
    val reasons: List<String>,
)

// CandleInputDto는 IndicatorDto.kt에 이미 정의됨 — 같은 패키지에서 재사용.
