package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.strategy.entry.KrxTickSize
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 단일가(동시호가) 구간 청산용 marketable SELL LIMIT 가격 산정 순수 함수 —
 * 2026-06-29 국순당(043650) -9% 오버나잇 사고 D1 fix.
 *
 * ## 왜 marketable LIMIT 인가 (롱온리 SELL = 체결 보장 · 헌법 §13)
 * 단일가(동시호가) 구간([kr.spdaewoon.magicjar.domain.market.session.KrxAuctionWindow.isSinglePriceAuction])엔
 * KIS 가 시장가(MARKET) 주문을 거부한다. 롱온리 청산(손절·EOD·수동)은 **반드시 팔려야** 하므로, 시장가 대신
 * 단일가 체결 가격보다 **충분히 낮은 지정가**(하한가 방향)로 낸다. 단일가 매칭은 가격 우선이라, 예상 체결가보다
 * 낮게 낸 SELL 은 그 단일가에 **무조건 체결**된다(공매도 0 — 보유분 청산이므로 음수·초과 risk 0).
 *
 * ## 가격 산정 (결정론)
 * `referencePrice × (1 - DISCOUNT_PCT/100)` 를 KRX 호가단위로 내림([KrxTickSize.floorToTick]).
 * - referencePrice = 손절 watcher 의 현재가(tick) 또는 청산 service 의 평단(avgPrice) — caller 가 가진 권위 가격.
 * - DISCOUNT_PCT = 9% — KRX 일일 가격제한폭(±30%) 안에서 단일가 체결을 확실히 가져갈 만큼 낮되, 호가 입력
 *   유효범위(하한가)를 벗어나지 않는 보수적 폭. (실제 단일가 체결가는 이 지정가가 아니라 단일가 균형가격이므로
 *   슬리피지 손실이 그대로 실현되는 게 아니다 — LIMIT 은 "이 가격까지는 받아들인다"는 체결 보장 장치일 뿐.)
 *
 * 산정 불가(referencePrice ≤ 0, 또는 floor 결과 ≤ 0)면 null → caller 가 시장가 fallback 또는 보류 결정.
 *
 * ## 순수성 (LOCK)
 * Spring·JPA·Clock·IO 의존 0. (referencePrice) → BigDecimal? 결정론.
 *
 * AI 호출 0 — 룰 기반 자본 흐름 순수 유틸 (ADR 0023 §2-4)
 */
internal object AuctionLiquidationPrice {
    /** 단일가 체결 보장용 SELL LIMIT 할인폭(%). KRX 일일 제한폭(±30%) 내 보수적 값. */
    val DISCOUNT_PCT: BigDecimal = BigDecimal("9")

    /**
     * marketable SELL LIMIT 가격 = `referencePrice × (1 - DISCOUNT_PCT/100)` 호가단위 내림.
     * referencePrice ≤ 0 또는 floor 결과 ≤ 0 이면 null(caller fallback).
     */
    fun marketableSellLimit(referencePrice: BigDecimal): BigDecimal? {
        if (referencePrice <= BigDecimal.ZERO) return null
        val discounted =
            referencePrice.multiply(
                BigDecimal.ONE.subtract(DISCOUNT_PCT.divide(BigDecimal("100"), 10, RoundingMode.HALF_UP)),
            )
        if (discounted <= BigDecimal.ZERO) return null
        val floored = KrxTickSize.floorToTick(discounted)
        return floored.takeIf { it > BigDecimal.ZERO }
    }
}
