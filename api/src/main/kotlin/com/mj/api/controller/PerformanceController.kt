package com.mj.api.controller

import com.mj.api.application.journal.PerformanceQueryService
import com.mj.api.controller.dto.EquityCurvePointDto
import com.mj.api.controller.dto.PerformancePeriodEntryDto
import com.mj.api.controller.dto.PerformanceSummaryResponse
import com.mj.api.controller.dto.toDto
import com.mj.api.controller.dto.toResponse
import com.mj.domain.journal.performance.PerformancePeriod
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 실거래 매매일지 기반 성과 조회 REST controller (T14 ADR 0046 정합).
 *
 * 본 트랙은 **오프라인 통계만**. AI 복기·코멘트는 W4 옵션 트랙.
 * Multi-tenant: 모든 endpoint userId 필수 (ADR 0043).
 *
 * 1차(R1) stub adapter — 실제 fill 매핑은 R2에서 [RealizedTradeRepository] 본격 구현.
 */
@RestController
@RequestMapping("/api/performance")
@Tag(name = "Performance", description = "실거래 매매일지 기반 성과 조회 (오프라인 통계)")
class PerformanceController(
    private val performanceQueryService: PerformanceQueryService,
) {
    @Operation(
        summary = "전체 통계 요약",
        description = """
            지정 기간 [from..to] 범위 실거래 성과 요약을 반환한다.

            - 총 수익률·CAGR·MDD·Sharpe·승률·profit factor·max consecutive losses 포함
            - equity curve(일별 자산 곡선) 동봉
            - 종결된 라운드트립 trade만 집계 (미청산 포지션 제외)
        """,
    )
    @GetMapping("/summary")
    fun summary(
        @Parameter(description = "사용자 식별자 UUID", example = "00000000-0000-0000-0000-000000000001", required = true)
        @RequestParam("userId")
        userId: String,
        @Parameter(description = "조회 시작일 (yyyy-MM-dd, inclusive)", example = "2026-01-01", required = true)
        @RequestParam("from")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate,
        @Parameter(description = "조회 종료일 (yyyy-MM-dd, inclusive)", example = "2026-12-31", required = true)
        @RequestParam("to")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate,
    ): PerformanceSummaryResponse {
        val (summary, curve) = performanceQueryService.summary(userId, from, to)
        return summary.toResponse(curve)
    }

    @Operation(
        summary = "Equity curve 조회 (차트용)",
        description = "일별 포트폴리오 자산 곡선만 반환. 시작일 자본부터 종료일 자본까지 매일 누적.",
    )
    @GetMapping("/equity-curve")
    fun equityCurve(
        @Parameter(description = "사용자 식별자", required = true)
        @RequestParam("userId") userId: String,
        @Parameter(description = "조회 시작일", required = true)
        @RequestParam("from")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @Parameter(description = "조회 종료일", required = true)
        @RequestParam("to")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): List<EquityCurvePointDto> = performanceQueryService.equityCurve(userId, from, to).map { it.toDto() }

    @Operation(
        summary = "기간별 P&L 집계",
        description = """
            DAILY·WEEKLY·MONTHLY·YEARLY·ALL_TIME 중 한 단위로 집계.

            - DAILY: 일별 (해당일 청산 trade pnl 합)
            - WEEKLY: ISO week (월요일 시작)
            - MONTHLY/YEARLY: 캘린더 월/연
            - ALL_TIME: 전체 단일 항목
        """,
    )
    @GetMapping("/period")
    fun byPeriod(
        @Parameter(description = "사용자 식별자", required = true)
        @RequestParam("userId") userId: String,
        @Parameter(
            description = "집계 단위",
            example = "MONTHLY",
            required = true,
        )
        @RequestParam("period") period: PerformancePeriod,
        @Parameter(description = "조회 시작일", required = true)
        @RequestParam("from")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @Parameter(description = "조회 종료일", required = true)
        @RequestParam("to")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): List<PerformancePeriodEntryDto> = performanceQueryService.byPeriod(userId, period, from, to).map { it.toDto() }
}
