package kr.spdaewoon.magicjar.domain.market.session

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * [KrxAuctionWindow] 순수 함수 테스트 — 2026-06-29 국순당 -9% 사고 D1.
 *
 * 단일가(동시호가) 구간 판정 경계: 장 개시 [08:30,09:00), 장 마감 [15:20,15:30). KST 기준.
 */
class KrxAuctionWindowTest {
    /** KST hh:mm → UTC Instant (KST = UTC+9). 2026-06-26(금)을 기준일로 고정. */
    private fun kst(
        hour: Int,
        minute: Int,
    ): Instant {
        val utcHour = hour - 9
        return if (utcHour < 0) {
            Instant.parse("2026-06-25" + "T%02d:%02d:00Z".format(utcHour + 24, minute))
        } else {
            Instant.parse("2026-06-26" + "T%02d:%02d:00Z".format(utcHour, minute))
        }
    }

    @Test
    fun `장 마감 동시호가 1520~1530 은 단일가 구간`() {
        KrxAuctionWindow.isSinglePriceAuction(kst(15, 20)) shouldBe true
        KrxAuctionWindow.isSinglePriceAuction(kst(15, 24)) shouldBe true
        KrxAuctionWindow.isSinglePriceAuction(kst(15, 29)) shouldBe true
    }

    @Test
    fun `1530 정각(종가 체결)부터는 단일가 구간 아님 (상한 exclusive)`() {
        KrxAuctionWindow.isSinglePriceAuction(kst(15, 30)) shouldBe false
    }

    @Test
    fun `장 개시 동시호가 0830~0900 은 단일가 구간`() {
        KrxAuctionWindow.isSinglePriceAuction(kst(8, 30)) shouldBe true
        KrxAuctionWindow.isSinglePriceAuction(kst(8, 59)) shouldBe true
    }

    @Test
    fun `0900 정각(연속매매 시작)부터는 단일가 구간 아님`() {
        KrxAuctionWindow.isSinglePriceAuction(kst(9, 0)) shouldBe false
    }

    @Test
    fun `연속매매 시간대(0900~1520)는 단일가 구간 아님 — 시장가 허용`() {
        KrxAuctionWindow.isSinglePriceAuction(kst(10, 0)) shouldBe false
        KrxAuctionWindow.isSinglePriceAuction(kst(13, 30)) shouldBe false
        KrxAuctionWindow.isSinglePriceAuction(kst(15, 19)) shouldBe false
    }

    @Test
    fun `장전·장후 시간대는 단일가 구간 아님 (시간대만 판정 — 영업일 무관)`() {
        KrxAuctionWindow.isSinglePriceAuction(kst(8, 29)) shouldBe false
        KrxAuctionWindow.isSinglePriceAuction(kst(16, 0)) shouldBe false
    }
}
