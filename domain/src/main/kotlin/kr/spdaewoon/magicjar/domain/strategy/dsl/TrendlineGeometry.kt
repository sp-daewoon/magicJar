package kr.spdaewoon.magicjar.domain.strategy.dsl

import kotlinx.datetime.Instant
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 추세선 기하 — 선형보간 순수함수 (ADR 0140 §3-1·§3-6).
 *
 * Spring·JPA 의존 0. DB 접근 없음(anchor는 [TrendlinePricePort]가 조회해 넘긴다).
 * 테스트 용이 + 이식 용이하도록 보간·범위 판정 로직을 domain 순수함수로 격리한다.
 *
 * ### 선형보간 산식 (ADR 0140 §1)
 * ```
 * linePrice(ts) = a1Price + (a2Price − a1Price) × (ts − a1Ts) / (a2Ts − a1Ts)
 * ```
 * (a1 = 시간상 이른 anchor, a2 = 늦은 anchor로 정규화 후 적용)
 */
object TrendlineGeometry {
    /** 시간 비율 분모/분자는 epoch millis 기반 — 캔들 ts 정밀도 충분. */
    private const val RATIO_SCALE = 20

    /** 결과 가격 scale — LiteralValue/IndicatorPoint 정합 (scale=8, HALF_EVEN). */
    private const val PRICE_SCALE = 8

    /**
     * 평가 시점 `ts`의 선 가격. 범위 밖(비활성 선)이면 `null`.
     *
     * 거동 (ADR 0140 §3-6):
     * - `ts`가 이른 anchor 이전 → `extendLeft`면 선 기울기 연장값, 아니면 `null`(선 밖).
     * - `ts`가 늦은 anchor 이후 → `extendRight`면 연장값, 아니면 `null`(선 밖).
     * - 두 anchor 사이 → 선형보간.
     * - 수평선(두 anchor 가격 동일·기울기 0) → 상수(anchor 가격).
     * - anchor 시각 역순 → 시간 기준 정규화 후 동일 처리.
     * - **퇴화(두 anchor 시각 동일 — 수직선)** → 시간 기반 보간 불가 → `null`.
     *
     * @return 선 가격(scale=8) 또는 범위 밖/퇴화 시 `null`.
     */
    fun priceAt(
        anchors: TrendlineAnchors,
        ts: Instant,
    ): BigDecimal? {
        // 시간 기준 정규화 — early = 이른 anchor, late = 늦은 anchor.
        val forward = anchors.anchor1Ts <= anchors.anchor2Ts
        val earlyTs = if (forward) anchors.anchor1Ts else anchors.anchor2Ts
        val earlyPrice = if (forward) anchors.anchor1Price else anchors.anchor2Price
        val lateTs = if (forward) anchors.anchor2Ts else anchors.anchor1Ts
        val latePrice = if (forward) anchors.anchor2Price else anchors.anchor1Price

        val t1 = earlyTs.toEpochMilliseconds()
        val t2 = lateTs.toEpochMilliseconds()
        if (t1 == t2) {
            // 수직선(두 anchor 시각 동일) — 시간 함수로 표현 불가.
            return null
        }

        val t = ts.toEpochMilliseconds()

        // 범위 판정 — extend 플래그는 시간 방향(left=과거, right=미래)에 결속.
        if (t < t1 && !anchors.extendLeft) return null
        if (t > t2 && !anchors.extendRight) return null

        // ratio = (t − t1) / (t2 − t1). 범위 밖 연장 시 <0 또는 >1 가능(의도된 외삽).
        val span = BigDecimal(t2 - t1)
        val offset = BigDecimal(t - t1)
        val ratio = offset.divide(span, RATIO_SCALE, RoundingMode.HALF_EVEN)

        val price = earlyPrice.add(latePrice.subtract(earlyPrice).multiply(ratio))
        return price.setScale(PRICE_SCALE, RoundingMode.HALF_EVEN)
    }
}
