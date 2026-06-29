package kr.spdaewoon.magicjar.consumer.order

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [AuctionLiquidationPrice] 순수 함수 테스트 — 2026-06-29 국순당 -9% 사고 D1.
 *
 * 단일가 구간 marketable SELL LIMIT = referencePrice × 0.91 호가단위 내림. 체결 보장 방향(현재가 이하) 보존.
 */
class AuctionLiquidationPriceTest {
    @Test
    fun `참조가 48000 — 9퍼센트 할인 후 호가단위(50) 내림 = 43650`() {
        // 48,000 × 0.91 = 43,680 → 20,000~49,999 구간 호가단위 50 내림 = 43,650.
        val limit = AuctionLiquidationPrice.marketableSellLimit(BigDecimal("48000"))
        assertThat(limit).isEqualByComparingTo(BigDecimal("43650"))
    }

    @Test
    fun `산정 LIMIT 은 항상 참조가보다 낮다 (롱온리 체결 보장 방향)`() {
        listOf("1500", "4990", "12345", "55000", "240000", "777000").forEach { raw ->
            val ref = BigDecimal(raw)
            val limit = AuctionLiquidationPrice.marketableSellLimit(ref)
            assertThat(limit).isNotNull()
            assertThat(limit!!).isLessThan(ref)
            assertThat(limit).isGreaterThan(BigDecimal.ZERO)
        }
    }

    @Test
    fun `참조가 0 이하 — null (caller MARKET fallback)`() {
        assertThat(AuctionLiquidationPrice.marketableSellLimit(BigDecimal.ZERO)).isNull()
        assertThat(AuctionLiquidationPrice.marketableSellLimit(BigDecimal("-100"))).isNull()
    }

    @Test
    fun `저가 종목도 호가단위(1원) 내림으로 양수 LIMIT 보장`() {
        // 1,000 × 0.91 = 910 → 호가단위 1 내림 = 910.
        assertThat(AuctionLiquidationPrice.marketableSellLimit(BigDecimal("1000"))).isEqualByComparingTo(BigDecimal("910"))
    }
}
