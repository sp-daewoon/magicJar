package kr.spdaewoon.magicjar.api.controller.autopilot

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * owner=AUTOPILOT scoped 실현손익 응답 (개선 F · 이슈 #12).
 *
 * 계좌 전체 성과([kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceSummaryDto])와 달리
 * **autopilot 전략(owner=AUTOPILOT)의 체결만** 집계한다. 수동 주문·USER 전략·오버나잇 후성 등 비-autopilot
 * 거래는 제외 — autopilot 진짜 성과만 본다(autopilot 평가·스킬·모니터링용).
 *
 * KIS 계좌 정합(matched=true)은 계좌 레벨 검증이라 본 owner-scoped 값과 별개다(아래 [kisScopingNote] 참조).
 */
@Schema(description = "owner=AUTOPILOT scoped 실현손익 — 수동·후성 제외 autopilot 진짜 성과")
data class AutopilotPerformanceResponse(
    @Schema(description = "집계 기간 단위", example = "DAY", allowableValues = ["DAY", "WEEK", "MONTH", "YEAR", "ALL"])
    val period: String,
    @Schema(description = "기간 시작 일자(KST)", example = "2026-06-22")
    val periodStart: LocalDate,
    @Schema(description = "기간 종료 일자(KST)", example = "2026-06-22")
    val periodEnd: LocalDate,
    @Schema(description = "거래 모드 필터(null=전체). 운영 REAL", example = "REAL", nullable = true)
    val mode: String?,
    @Schema(description = "owner=AUTOPILOT 실현손익 합계 — net (거래비용 fee/tax 차감 후, 원). 양수=이익", example = "2728.00")
    val realizedPnl: BigDecimal,
    @Schema(
        description = "owner=AUTOPILOT 실현손익 합계 — gross (거래비용 미차감, 원). realizedPnl(net)과의 차이가 누적 거래비용. follow-up H 신설.",
        example = "2950.00",
    )
    val grossRealizedPnl: BigDecimal? = null,
    @Schema(description = "실현 청산 횟수(매도 체결 단위)", example = "8")
    val tradeCount: Int,
    @Schema(description = "이익 청산 수", example = "2")
    val winCount: Int,
    @Schema(description = "손실 청산 수", example = "5")
    val lossCount: Int,
    @Schema(description = "승률(%) — winCount / (win+loss). 청산 0건이면 0", example = "28.57")
    val winRatePct: BigDecimal,
    @Schema(description = "손익비 — 평균이익 / |평균손실|. 손실 0이면 999.99(상한)", example = "1.85")
    val profitLossRatio: BigDecimal,
    @Schema(description = "owner=AUTOPILOT 전략 id 집계 대상 수(DELETED 포함)", example = "6")
    val scopedStrategyCount: Int,
    @Schema(description = "종목별 owner-scoped 실현손익(내림차순)")
    val byCode: List<AutopilotCodePnl>,
    @Schema(
        description =
            "KIS 계좌 정합 안내 — KIS 손익은 계좌 레벨이라 autopilot/비-autopilot 정확 분리 불가. " +
                "본 값은 DB 체결 FIFO 기반 owner-scoped 추정치(KIS net 과 후성 등 비-autopilot 제외 시 정합).",
        example = "owner-scoped (DB FIFO). KIS 계좌정합은 별도(계좌 레벨).",
    )
    val kisScopingNote: String,
    @Schema(description = "집계 시각(KST)")
    val computedAt: OffsetDateTime,
)

/** 종목별 owner-scoped 실현손익 row. */
@Schema(description = "종목별 autopilot 실현손익")
data class AutopilotCodePnl(
    @Schema(description = "종목 코드", example = "080220")
    val code: String,
    @Schema(description = "종목명", example = "제주반도체", nullable = true)
    val name: String?,
    @Schema(description = "해당 종목 owner-scoped 실현손익(원)", example = "8947.00")
    val realizedPnl: BigDecimal,
    @Schema(description = "청산 횟수", example = "1")
    val tradeCount: Int,
)
