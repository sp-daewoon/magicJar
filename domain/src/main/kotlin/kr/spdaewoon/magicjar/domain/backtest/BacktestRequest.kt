package kr.spdaewoon.magicjar.domain.backtest

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * 백테스트 실행 요청 도메인 객체 (ADR 0056 §2-1 LOCK).
 *
 * 이 클래스는 도메인 모듈에만 위치하며 Spring·JPA·Kafka import 절대 금지 (헥사고날 정합).
 *
 * @param strategyId 평가할 전략 ID
 * @param universe 시뮬레이션 종목코드 리스트 (생존자 편향 보정은 universe resolver 별도)
 * @param interval 캔들 인터벌 ("1d" · "1m" · "5m" · "15m" · "1h")
 * @param from 시작일 (포함)
 * @param to 종료일 (포함)
 * @param initialCapital 초기 자본 (KRW)
 * @param taxModel 세금 모델 키 ("korean_default" · "none")
 * @param commissionModel 수수료 모델 키 ("kis_direct" · "none")
 * @param slippageModel 슬리피지 모델 키 ("fixed_0.05" · "none")
 * @param priceAdjustmentMode 가격 모드 (RAW · ADJUSTED)
 * @param sizingPolicy 포지션 사이징 정책 (1차: AllCapital)
 */
data class BacktestRequest(
    val strategyId: Long,
    val universe: List<String>,
    val interval: String,
    val from: LocalDate,
    val to: LocalDate,
    val initialCapital: BigDecimal,
    val taxModel: String = "korean_default",
    val commissionModel: String = "kis_direct",
    val slippageModel: String = "fixed_0.05",
    val priceAdjustmentMode: PriceAdjustmentMode = PriceAdjustmentMode.ADJUSTED,
    val sizingPolicy: SizingPolicy = SizingPolicy.AllCapital,
) {
    init {
        require(from <= to) { "from must be <= to: from=$from, to=$to" }
        require(initialCapital > BigDecimal.ZERO) { "initialCapital must be positive: $initialCapital" }
        require(universe.isNotEmpty()) { "universe must be non-empty" }
    }
}

/** 가격 조정 모드 — RAW(수정 전) · ADJUSTED(수정 주가) */
enum class PriceAdjustmentMode {
    RAW,
    ADJUSTED,
}

/**
 * 포지션 사이징 정책 (ADR 0056 §2-1).
 *
 * 1차는 AllCapital만 지원. 후속 ADR 0080+에서 FixedFractional·Kelly 검토.
 */
sealed class SizingPolicy {
    /** 전액 투입 — 매수 시 보유 자본 전액을 1종목에 투입 (1차 default) */
    object AllCapital : SizingPolicy()

    /** 고정 비율 — 자본의 fraction 비율만 투입 (post-MVP) */
    data class FixedFractional(
        val fraction: BigDecimal,
    ) : SizingPolicy()

    /** 켈리 기준 — 승률·배당 기반 최적 비율 (post-MVP) */
    data class Kelly(
        val winRate: BigDecimal,
        val payoff: BigDecimal,
    ) : SizingPolicy()
}
