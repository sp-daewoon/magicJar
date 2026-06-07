package kr.spdaewoon.magicjar.domain.strategy

import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import java.math.BigDecimal

/**
 * magicJar 확장 전략 정의 wrapper — ADR 0022 §2-2 정합.
 *
 * KIS 호환 inner [DslStrategyDefinition] + magicJar 확장 필드.
 * `.kis.yaml` import 시 magicJar 확장 default 자동 적용 (tf=D1·session=REGULAR 등).
 *
 * @param dsl KIS 호환 DSL inner (DslStrategyDefinition)
 * @param universe 유니버스 (시장·필터)
 * @param tf 기준 타임프레임 (default: D1)
 * @param timeFilter 시간 필터 (장 시간 제한 등)
 * @param sizing 포지션 사이징
 * @param trailingStop 트레일링 스탑 비율 (%)
 * @param fixedStopLoss 고정 손절 비율 (%). 차트 범위 손절이 없을 때 자동매매 BUY hard stop으로 사용한다.
 * @param takeProfitPct 보유 포지션 평가 수익률 기준 익절 비율 (%). 캔들 DSL이 아닌 계좌/포지션 기반 exit policy.
 * @param scaleIn 분할 매수(scale-in) 정책 (spec §3-2 · ADR 0111). null이면 현행 단일 진입(하위호환 LOCK).
 * @param partialExits 분할 익절 단계 리스트 (spec §3-3 · ADR 0111). null이면 현행 전량 익절(하위호환 LOCK). 단계 ≤ 5 · weight 합 ≤ 100.
 * @param entryPolicy 진입(BUY) 집행 정책 (ADR 0114 §3·§5 · U-1 묶음 객체). null이면 전역 default(env `magicjar.order.entry.*`) — 기존 전략 0 변경(하위호환 LOCK).
 */
data class StrategyDefinition(
    val dsl: DslStrategyDefinition,
    val universe: Universe = Universe.defaultKospi(),
    val tf: String = "D1",
    val timeFilter: TimeFilter? = null,
    val sizing: Sizing? = null,
    val trailingStop: BigDecimal? = null,
    val fixedStopLoss: BigDecimal? = null,
    val takeProfitPct: BigDecimal? = null,
    val scaleIn: ScaleInPlan? = null,
    val partialExits: List<PartialExit>? = null,
    val entryPolicy: EntryPolicy? = null,
) {
    init {
        // 개별 ScaleInPlan·PartialExit 단일 검증은 각 클래스 init 책임.
        // 여기선 StrategyDefinition 수준의 교차 검증(분할 익절 단계 cap·weight 합)만 수행한다.
        partialExits?.let { ExitLimits.validatePartialExits(it) }
    }
}

/**
 * 유니버스 — 매매 대상 시장 + 필터.
 */
data class Universe(
    val market: String = "KOSPI",
    val filter: UniverseFilter? = null,
) {
    companion object {
        fun defaultKospi() = Universe("KOSPI")

        fun kosdaq() = Universe("KOSDAQ")
    }
}

/**
 * 유니버스 필터 — 섹터·최소 시총 등.
 */
data class UniverseFilter(
    val sector: List<String>? = null,
    val minMarketCap: Long? = null,
)

/**
 * 시간 필터 — 장 세션 + 시간 윈도우.
 */
data class TimeFilter(
    val session: String = "REGULAR",
    val windowKst: List<String>? = null,
)

/**
 * 포지션 사이징.
 */
data class Sizing(
    val type: String = "FIXED_FRACTIONAL",
    val fraction: BigDecimal = BigDecimal("0.05"),
)
