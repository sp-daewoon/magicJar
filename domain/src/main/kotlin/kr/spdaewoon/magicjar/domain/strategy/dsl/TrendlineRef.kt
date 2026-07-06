package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * 기울어진 추세선 참조 DSL 노드 (ADR 0140 §3-1 — 추세매매 Phase 2).
 *
 * DSL 텍스트 `trendline(123)` → `TrendlineRef(trendlineId=123)`.
 *
 * ### [IndicatorRef]와 분리한 이유 (ADR 0140 §2 대안 A 채택)
 * 추세선은 캔들 입력→시계열 출력 계약([kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine])과 근본이 다르다
 * (anchor 2점 기반·캔들 무관). IndicatorRef 경로에 억지로 태우면 ADR 0021 도메인 Indicator 단일 진입점이
 * 오염된다. 전용 노드로 분리해 out-of-range·null 분기를 평가기에서 명시 처리하고, 파싱 시 id 추출로
 * 선-전략 참조 무결성 테이블(R2 `strategy_trendline_ref`)을 자동 유지한다.
 *
 * ### 평가 방식
 * 평가 시점 ts(= 마지막 캔들 ts)에서 [TrendlineAnchors] 2점 선형보간
 * ([TrendlineGeometry.priceAt])으로 선 가격을 산출해 비교 대상(NumEval)로 쓴다.
 * 범위 밖(비활성 선)이면 조건 false (ADR 0140 §3-6).
 *
 * ### requiredDays
 * 추세선은 캔들 lookback과 무관(anchor 기반)하므로 [Condition.requiredDays] 기여 0.
 */
data class TrendlineRef(
    val trendlineId: Long,
) : DslNode()
