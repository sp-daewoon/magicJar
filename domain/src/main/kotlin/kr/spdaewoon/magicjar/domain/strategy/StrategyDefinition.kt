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
 * @param trailingStop 트레일링 스탑 비율 (0.0~1.0)
 * @param fixedStopLoss 고정 손절 비율 (0.0~1.0)
 */
data class StrategyDefinition(
    val dsl: DslStrategyDefinition,
    val universe: Universe = Universe.defaultKospi(),
    val tf: String = "D1",
    val timeFilter: TimeFilter? = null,
    val sizing: Sizing? = null,
    val trailingStop: BigDecimal? = null,
    val fixedStopLoss: BigDecimal? = null,
)

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
