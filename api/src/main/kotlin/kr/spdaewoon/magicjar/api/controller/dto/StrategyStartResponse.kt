package kr.spdaewoon.magicjar.api.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

/**
 * 전략 시작 화면 응답 — 검증 템플릿(SYSTEM) + 내 전략(USER) + 즐겨찾기 합성 (spec 2026-06-18 §6.1).
 *
 * 단일 호출(`GET /api/strategies/start`)로 시작 화면 세 재료를 채워 왕복을 최소화한다.
 * 데이터 0건이면 빈 배열 — mock 합성 금지(메모리 feedback_no_mock_data).
 */
@Schema(description = "전략 시작 화면 — 검증 템플릿 + 내 전략 + 즐겨찾기")
data class StrategyStartResponse(
    @field:Schema(description = "SYSTEM 소유 검증 템플릿 (시드 프리셋)")
    val templates: List<StrategyStartItem>,
    @field:Schema(description = "내(userId) 전략, favorite 플래그 포함")
    val myStrategies: List<StrategyStartItem>,
    @field:Schema(description = "즐겨찾기한 strategyId 목록 (빠른 표시용)", example = "[1, 5]")
    val favoriteIds: List<Long>,
)

/**
 * 시작 화면 카드 1건 — 템플릿·내 전략 공통 표현 (entry/exit DSL은 카드 클릭 시 빌더 로드용).
 */
@Schema(description = "시작 화면 카드 1건")
data class StrategyStartItem(
    @field:Schema(description = "전략 ID", example = "1")
    val id: Long,
    @field:Schema(description = "전략 영문명(식별자)", example = "fvg-bullish-reclaim")
    val name: String,
    @field:Schema(description = "전략 한국어명", example = "FVG 매수 후보")
    val nameKo: String?,
    @field:Schema(description = "타임프레임(제안 인터벌)", example = "3m")
    val tf: String,
    @field:Schema(description = "매수 조건 DSL 텍스트", example = "fvg(50).bullish_active == 1")
    val dslEntry: String,
    @field:Schema(description = "매도 조건 DSL 텍스트(null=미지정)")
    val dslExit: String?,
    @field:Schema(description = "익절 비율 % (null=미지정)", example = "5.00")
    val takeProfitPct: BigDecimal?,
    @field:Schema(description = "고정 손절 비율 % (null=미지정)", example = "3.00")
    val fixedStopLossPct: BigDecimal?,
    @field:Schema(description = "이 사용자의 즐겨찾기 여부", example = "false")
    val favorite: Boolean,
)
