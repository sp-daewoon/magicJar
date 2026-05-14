package kr.spdaewoon.magicjar.api.controller.dto.journal

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 매매일지 entry 응답 DTO — ADR 0072 §6-1·§6-2 Swagger 4단.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Schema(description = "매매일지 entry")
data class TradingJournalEntryDto(
    @Schema(description = "entry ID", example = "1001")
    val id: Long,
    @Schema(
        description = "이벤트 타입",
        example = "ORDER_FILLED",
        allowableValues = ["ORDER_FILLED", "ORDER_REJECTED", "ORDER_CANCELLED", "SIGNAL_GENERATED", "RISK_VIOLATION"],
    )
    val eventType: String,
    @Schema(description = "종목 코드 (nullable)", example = "005930")
    val instrumentCode: String?,
    @Schema(description = "종목명 (nullable)", example = "삼성전자")
    val instrumentName: String?,
    @Schema(description = "발생 시각 (KST)", example = "2026-05-14T14:32:18+09:00")
    val occurredAt: OffsetDateTime,
    @Schema(description = "한국어 요약", example = "체결: 삼성전자 매수 100주 @ 75,000원")
    val summary: String,
    @Schema(description = "실현 손익 (체결 이벤트 한정, 그 외 null)", example = "150000.00")
    val realizedPnl: BigDecimal?,
    @Schema(description = "원본 이벤트 페이로드 (audit + AI context)")
    val payload: Map<String, Any?>,
    @Schema(description = "사용자 메모 목록")
    val notes: List<JournalNoteDto>,
)
