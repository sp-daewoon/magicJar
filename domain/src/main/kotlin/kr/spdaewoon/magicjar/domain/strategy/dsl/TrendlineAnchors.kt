package kr.spdaewoon.magicjar.domain.strategy.dsl

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 추세선 anchor 스냅샷 — 불변 VO (ADR 0140 §3-2).
 *
 * 사용자가 차트에서 두 점(anchor1·anchor2)으로 그은 직선을 나타낸다.
 * 임의 시점의 선 가격은 [TrendlineGeometry.priceAt] 선형보간으로 구한다.
 *
 * ### 필드 의미
 * - `anchor1Ts`/`anchor1Price` · `anchor2Ts`/`anchor2Price` — 두 anchor 점의 (시각, 가격).
 *   저장 순서(anchor1/anchor2)는 시간 정렬을 보장하지 않는다 —
 *   [TrendlineGeometry]가 시간 기준으로 정규화한다(anchor 역순 허용).
 * - `extendLeft` — 이른(과거) 쪽 경계 밖으로 선을 연장할지. false면 이른 anchor 이전 = 선 밖(비활성).
 * - `extendRight` — 늦은(미래) 쪽 경계 밖으로 선을 연장할지. false면 늦은 anchor 이후 = 선 밖(비활성).
 *
 * ### timestamp 타입
 * domain [kr.spdaewoon.magicjar.domain.market.candle.Candle].ts 와 정합하도록 `kotlinx.datetime.Instant`.
 * JPA `java.time.Instant` ↔ 변환은 어댑터(R2) 책임.
 *
 * ### BigDecimal
 * 가격은 T-IND IndicatorPoint 정합 scale=8 (선형보간 결과는 [TrendlineGeometry]가 scale 정규화).
 */
data class TrendlineAnchors(
    val anchor1Ts: Instant,
    val anchor1Price: BigDecimal,
    val anchor2Ts: Instant,
    val anchor2Price: BigDecimal,
    val extendLeft: Boolean = false,
    val extendRight: Boolean = false,
)
