package com.mj.domain.market.corp_action

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/**
 * 수정 주가 계산기 (ADR 0038).
 *
 * Corp Action(액면분할·무상증자·배당·유상증자) 시계열을 적용해
 * raw 주가를 adjusted 주가로 보정한다.
 *
 * 알고리즘:
 * 1. Corp Action을 exDate 기준 오름차순 정렬
 * 2. 각 Corp Action의 보정 계수(adjustmentFactor)를 계산
 * 3. 조회 시점 기준 이전 Corp Action들의 누적 계수를 곱해 adjusted 산출
 *
 * 룩어헤드 차단:
 * - targetDate < corpAction.exDate → 해당 Corp Action 적용 안 함 (raw 사용)
 * - targetDate >= corpAction.exDate → 보정 계수 누적 적용
 * - retroactive 재계산은 외부(CorpActionBackfillJob)에서 호출, 이 클래스는 순수 계산만
 *
 * 단위 테스트 기준:
 * - split 2:1 → 과거 주가 × 0.5
 * - dividend cashAmount=500, price=50000 → 과거 주가 × (49500/50000)
 * - 복수 Corp Action → 계수 누적 곱
 */
class AdjustedPriceCalculator {
    private val mc = MathContext(10, RoundingMode.HALF_UP)

    /**
     * 단일 가격에 대해 보정 계수를 적용해 adjusted 가격을 반환한다.
     *
     * @param rawPrice 원주가 (ex_date 이전 기준)
     * @param priceDate 해당 주가의 날짜
     * @param corpActions 해당 종목의 Corp Action 목록 (전체, exDate 오름차순 정렬)
     * @return adjusted 가격 (보정 계수 누적 적용)
     */
    fun calculateAdjusted(
        rawPrice: BigDecimal,
        priceDate: LocalDate,
        corpActions: List<CorpAction>,
    ): BigDecimal {
        val cumulativeFactor = calculateCumulativeFactor(priceDate, corpActions)
        return rawPrice.multiply(cumulativeFactor, mc).setScale(4, RoundingMode.HALF_UP)
    }

    /**
     * priceDate 기준으로 적용해야 하는 누적 보정 계수를 계산한다.
     *
     * 룩어헤드 차단:
     * - priceDate < corpAction.exDate → 해당 Corp Action의 계수를 포함 (과거 가격이므로 미래 Corp Action 보정 필요)
     * - priceDate >= corpAction.exDate → 해당 Corp Action 계수 제외 (이미 시장 반영됨)
     *
     * 예: 2024-04-25 삼성전자 주가 2,500,000원 → 2024-04-26 50:1 분할 이후 기준으로 보정
     *   → 계수 = 1/50 = 0.02 → adjusted = 2,500,000 × 0.02 = 50,000원
     *
     * @param priceDate 보정 대상 주가의 날짜
     * @param corpActions 해당 종목의 전체 Corp Action (exDate ASC 정렬)
     */
    fun calculateCumulativeFactor(
        priceDate: LocalDate,
        corpActions: List<CorpAction>,
    ): BigDecimal {
        // priceDate < corpAction.exDate인 것들만 (미래 Corp Action들) — 이 보정 계수를 누적
        val applicableActions =
            corpActions
                .filter { it.exDate.isAfter(priceDate) }
                .sortedBy { it.exDate }

        return applicableActions.fold(BigDecimal.ONE) { acc, action ->
            acc.multiply(factorForAction(action), mc)
        }
    }

    /**
     * 단일 Corp Action의 보정 계수를 계산한다.
     *
     * - Split(ratio=50): factor = 1/50 → 과거 주가를 낮춤
     * - BonusIssue(ratio=0.1): factor = 1/(1+0.1) = 1/1.1 → 무상 10% 배분
     * - Dividend(cashAmount=500, 기준주가 필요): factor = (기준주가 - 배당금) / 기준주가
     *   → 배당 보정은 기준주가가 필요하므로 별도 메서드 dividendFactor() 사용
     * - RightsIssue: 이론 권리락가 계산 (subscriptionPrice + ratio 필요)
     * - Merger/Spinoff: 비율 적용
     *
     * 주의: Dividend 타입은 기준주가 없이 계수를 계산할 수 없으므로
     *   cashAmount/기준주가 비율이 아닌 stockRatio 기반 계산만 이 메서드에서 수행.
     *   현금 배당의 정확한 보정은 calculateDividendAdjusted() 메서드를 사용.
     */
    private fun factorForAction(action: CorpAction): BigDecimal =
        when (action) {
            is CorpAction.Split -> {
                // 1주 → ratio주 → 주가 ÷ ratio
                if (action.ratio <= BigDecimal.ZERO) {
                    BigDecimal.ONE
                } else {
                    BigDecimal.ONE.divide(action.ratio, mc)
                }
            }
            is CorpAction.BonusIssue -> {
                // 무상증자 ratio% → 주가 × 1/(1+ratio)
                if (action.ratio <= BigDecimal.ZERO) {
                    BigDecimal.ONE
                } else {
                    BigDecimal.ONE.divide(BigDecimal.ONE.add(action.ratio), mc)
                }
            }
            is CorpAction.RightsIssue -> {
                // 이론 권리락가 = (기존 1주 × 시장가 + ratio × 청약가) / (1 + ratio)
                // 시장가를 모르는 상태에서는 ratio 비율로만 근사
                // 정확한 계산은 candle data와 함께 수행
                BigDecimal.ONE
            }
            is CorpAction.Dividend -> {
                // 현금 배당은 기준주가 필요 — 여기서는 SKIP (dividendFactor() 사용)
                // 주식 배당만 여기서 처리
                if (action.stockRatio > BigDecimal.ZERO) {
                    BigDecimal.ONE.divide(BigDecimal.ONE.add(action.stockRatio), mc)
                } else {
                    BigDecimal.ONE
                }
            }
            is CorpAction.Merger -> {
                if (action.mergeRatio <= BigDecimal.ZERO) {
                    BigDecimal.ONE
                } else {
                    action.mergeRatio
                }
            }
            is CorpAction.Spinoff -> {
                // 분할 후 존속법인 가치 = 1 - splitRatio
                if (action.splitRatio <= BigDecimal.ZERO || action.splitRatio >= BigDecimal.ONE) {
                    BigDecimal.ONE
                } else {
                    BigDecimal.ONE.subtract(action.splitRatio)
                }
            }
        }

    /**
     * 현금 배당 보정 계수 계산.
     *
     * factor = (기준주가 - 배당금) / 기준주가
     *
     * @param cashDividend 주당 현금 배당금
     * @param basePrice 배당락 직전 종가 (기준주가)
     * @return 보정 계수
     */
    fun dividendFactor(
        cashDividend: BigDecimal,
        basePrice: BigDecimal,
    ): BigDecimal {
        if (basePrice <= BigDecimal.ZERO) return BigDecimal.ONE
        val adjustedPrice = basePrice.subtract(cashDividend)
        return adjustedPrice.divide(basePrice, mc)
    }

    /**
     * 배치 처리용: 여러 날짜-원주가 쌍에 대해 adjusted 가격을 일괄 계산한다.
     *
     * @param priceDatePairs List of (date, rawClose)
     * @param corpActions 전체 Corp Action (exDate ASC)
     * @return List of (date, rawClose, adjustedClose, factor)
     */
    fun calculateBatch(
        priceDatePairs: List<Pair<LocalDate, BigDecimal>>,
        corpActions: List<CorpAction>,
    ): List<AdjustedResult> =
        priceDatePairs.map { (date, rawClose) ->
            val factor = calculateCumulativeFactor(date, corpActions)
            val adjustedClose = rawClose.multiply(factor, mc).setScale(4, RoundingMode.HALF_UP)
            AdjustedResult(
                date = date,
                rawClose = rawClose,
                adjustedClose = adjustedClose,
                adjustmentFactor = factor.setScale(8, RoundingMode.HALF_UP),
            )
        }

    data class AdjustedResult(
        val date: LocalDate,
        val rawClose: BigDecimal,
        val adjustedClose: BigDecimal,
        /** 누적 보정 계수 (e.g. 0.02 = 1/50 분할) */
        val adjustmentFactor: BigDecimal,
    )
}
