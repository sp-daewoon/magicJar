package kr.spdaewoon.magicjar.api.controller.dto.performance

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 전략 성과 히스토리 + 메모 응답/요청 DTO 묶음 — spec `2026-06-16-strategy-daily-performance.md` §6 정합.
 *
 * 모든 경로 읽기 전용(메모 UPSERT 제외)·새 KIS 호출 0. 금액은 [BigDecimal](원 단위), 비율은 % 단위 [BigDecimal].
 * 데이터 0건 → 빈 배열/빈 요약(mock 합성 금지 — feedback_no_mock_data).
 *
 * 프런트(R3) 계약 LOCK:
 * - history : GET /api/strategy/{id}/performance/history → [StrategyPerformanceHistoryResponse]
 * - summary : GET /api/strategy/{id}/performance/summary → [StrategyPerformanceSummaryResponse]
 * - note 조회 : GET /api/strategy/{id}/note → [StrategyNoteTimelineResponse]
 * - note 저장 : PUT /api/strategy/{id}/note/{date} (body [StrategyNoteUpsertRequest]) → [StrategyNoteDto]
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (spec §6)
 */

@Schema(description = "전략 성과 히스토리 응답 — 일자별 성과 행 목록(최신일 우선) · GET /api/strategy/{id}/performance/history")
data class StrategyPerformanceHistoryResponse(
    @Schema(description = "전략 ID", example = "86")
    val strategyId: Long,
    @Schema(description = "조회 시작일 (KST, inclusive)", example = "2026-06-11")
    val from: LocalDate,
    @Schema(description = "조회 종료일 (KST, inclusive)", example = "2026-06-16")
    val to: LocalDate,
    @Schema(description = "일자별 성과 행 (trade_date DESC). 청산된 거래가 없으면 빈 배열.")
    val rows: List<StrategyPerformanceRowDto>,
)

@Schema(description = "전략×일자 성과 1행 — 그날 청산된 왕복 매매 집계 + 시장 맥락")
data class StrategyPerformanceRowDto(
    @Schema(description = "영업일 (KST, 왕복 완전 청산일)", example = "2026-06-16")
    val tradeDate: LocalDate,
    @Schema(description = "그날 청산된 왕복(round-trip) 수 (손익=0 포함)", example = "3")
    val roundTripCount: Int,
    @Schema(description = "왕복 손익 > 0 횟수", example = "2")
    val winCount: Int,
    @Schema(description = "왕복 손익 < 0 횟수", example = "1")
    val lossCount: Int,
    @Schema(description = "그날 승률 % = winCount / roundTripCount × 100. 왕복 0 이면 null", example = "66.67", nullable = true)
    val winRatePct: BigDecimal?,
    @Schema(description = "청산 왕복 손익 합 (원). 음수=손실", example = "32000")
    val realizedPnl: BigDecimal,
    @Schema(description = "청산 왕복 진입금액 합 (원) — 수익률 분모", example = "1000000")
    val totalCostBasis: BigDecimal,
    @Schema(description = "그날 수익률 % = realizedPnl / totalCostBasis × 100. 분모 0 이면 null", example = "3.20", nullable = true)
    val returnPct: BigDecimal?,
    @Schema(description = "벤치마크 지수 코드 (0001 코스피 · 1001 코스닥). 미설정 시 null", example = "0001", nullable = true)
    val benchmarkIndexCode: String?,
    @Schema(description = "그날 벤치마크 지수 등락률 % (지수 부재 시 null)", example = "0.85", nullable = true)
    val benchmarkChangePct: BigDecimal?,
    @Schema(description = "초과수익 α % = returnPct − benchmarkChangePct (둘 중 null 이면 null)", example = "2.35", nullable = true)
    val alphaPct: BigDecimal?,
    @Schema(
        description = "그날 시장 레짐 (UPTREND·DOWNTREND·SIDEWAYS·HIGH_VOL). 지수 부재 시 null",
        example = "UPTREND",
        nullable = true,
    )
    val marketRegime: String?,
)

@Schema(
    description = "전략 성과 누적 요약 — 평균 수익률·승률 + 신뢰도(라벨+표본수) + 레짐별 승률 + 평균 α · GET /api/strategy/{id}/performance/summary",
)
data class StrategyPerformanceSummaryResponse(
    @Schema(description = "전략 ID", example = "86")
    val strategyId: Long,
    @Schema(description = "성과 행이 1건이라도 있는지 여부. false 면 이하 통계는 모두 null/0/빈배열(데이터 없음)", example = "true")
    val hasData: Boolean,
    @Schema(description = "누적 왕복 수 (Σ roundTripCount)", example = "42")
    val totalRoundTrips: Int,
    @Schema(description = "거래 발생 일수 (roundTripCount>0 인 일자 수)", example = "9")
    val tradingDays: Int,
    @Schema(description = "누적 승 횟수", example = "28")
    val totalWins: Int,
    @Schema(description = "누적 패 횟수", example = "11")
    val totalLosses: Int,
    @Schema(
        description = "누적 승률 % = totalWins / (totalWins + totalLosses) × 100. 승/패 0 이면 null",
        example = "71.79",
        nullable = true,
    )
    val winRatePct: BigDecimal?,
    @Schema(description = "누적 실현 손익 합 (원)", example = "452000")
    val totalRealizedPnl: BigDecimal,
    @Schema(
        description = "일별 수익률 평균 % (거래 발생일 한정 — 신뢰도 산식의 mean). 표본 없으면 null",
        example = "2.85",
        nullable = true,
    )
    val avgDailyReturnPct: BigDecimal?,
    @Schema(description = "평균 초과수익 α % (α 가 채워진 일자 평균). 없으면 null", example = "1.40", nullable = true)
    val avgAlphaPct: BigDecimal?,
    @Schema(description = "신뢰도 — 표본수·일관성 기준 (도메인 StrategyConfidenceCalculator)")
    val confidence: StrategyConfidenceDto,
    @Schema(description = "레짐별 승률 브레이크다운 (regime GROUP BY). 레짐 미상(지수 부재) 행은 제외. 빈 배열 가능")
    val regimeBreakdown: List<RegimeWinRateDto>,
)

@Schema(description = "전략 신뢰도 — 누적 일자행 읽기 시 계산(저장 안 함)")
data class StrategyConfidenceDto(
    @Schema(description = "신뢰도 라벨 (LOW·MEDIUM·HIGH). 표본 부족은 LOW", example = "MEDIUM")
    val label: String,
    @Schema(description = "라벨 한국어 표시 (표본수 명시 — 예 '신뢰도 보통 42건')", example = "신뢰도 보통 42건")
    val labelText: String,
    @Schema(description = "누적 왕복 수 (표본)", example = "42")
    val sampleRoundTrips: Int,
    @Schema(description = "거래 발생 일수", example = "9")
    val tradingDays: Int,
    @Schema(description = "일별 수익률 평균 %. 표본 없으면 null", example = "2.85", nullable = true)
    val meanReturnPct: BigDecimal?,
    @Schema(description = "일별 수익률 표준편차 % (모표준편차). 표본 < 2 면 null", example = "1.92", nullable = true)
    val stdDevReturnPct: BigDecimal?,
    @Schema(description = "Sharpe식 = mean / std. std=0/null 이면 null", example = "1.48", nullable = true)
    val sharpe: BigDecimal?,
)

@Schema(description = "레짐별 승률 1행 — '이런 장에 이 전략이 좋았다'")
data class RegimeWinRateDto(
    @Schema(description = "시장 레짐 (UPTREND·DOWNTREND·SIDEWAYS·HIGH_VOL)", example = "SIDEWAYS")
    val regime: String,
    @Schema(description = "해당 레짐 거래 발생 일수", example = "4")
    val tradingDays: Int,
    @Schema(description = "해당 레짐 누적 왕복 수", example = "15")
    val roundTripCount: Int,
    @Schema(description = "해당 레짐 누적 승", example = "11")
    val winCount: Int,
    @Schema(description = "해당 레짐 누적 패", example = "4")
    val lossCount: Int,
    @Schema(
        description = "해당 레짐 승률 % = winCount / (winCount + lossCount) × 100. 승/패 0 이면 null",
        example = "73.33",
        nullable = true,
    )
    val winRatePct: BigDecimal?,
)

@Schema(description = "전략 메모 타임라인 응답 — 일자별 메모 목록(최신일 우선) · GET /api/strategy/{id}/note")
data class StrategyNoteTimelineResponse(
    @Schema(description = "전략 ID", example = "86")
    val strategyId: Long,
    @Schema(description = "조회 시작일 (KST, inclusive)", example = "2026-06-11")
    val from: LocalDate,
    @Schema(description = "조회 종료일 (KST, inclusive)", example = "2026-06-16")
    val to: LocalDate,
    @Schema(description = "일자별 메모 (trade_date DESC). 메모가 없으면 빈 배열.")
    val notes: List<StrategyNoteDto>,
)

@Schema(description = "전략×일자 메모 1건")
data class StrategyNoteDto(
    @Schema(description = "메모 PK", example = "12")
    val id: Long,
    @Schema(description = "전략 ID", example = "86")
    val strategyId: Long,
    @Schema(description = "메모 귀속일 (KST 영업일 — 성과 일자행과 짝)", example = "2026-06-16")
    val tradeDate: LocalDate,
    @Schema(description = "자유 메모 텍스트 (태그만 달 수도 있어 null 가능)", example = "오후 변동성 확대, 손절 잘 작동", nullable = true)
    val noteText: String?,
    @Schema(description = "태그 배열 (추천 시드 + 자유)", example = "[\"손절잘됨\",\"변동성과다\"]")
    val tags: List<String>,
    @Schema(description = "생성 시각 (KST)", example = "2026-06-16T18:05:00+09:00")
    val createdAt: OffsetDateTime,
    @Schema(description = "수정 시각 (KST)", example = "2026-06-16T18:05:00+09:00")
    val updatedAt: OffsetDateTime,
)

@Schema(description = "전략×일자 메모 UPSERT 요청 — 있으면 수정, 없으면 생성")
data class StrategyNoteUpsertRequest(
    @Schema(description = "자유 메모 텍스트 (선택 — 태그만 저장도 허용)", example = "오후 변동성 확대, 손절 잘 작동", nullable = true)
    val noteText: String? = null,
    @Schema(description = "태그 배열 (선택, 미지정 시 빈 배열)", example = "[\"손절잘됨\",\"변동성과다\"]")
    val tags: List<String>? = null,
)
