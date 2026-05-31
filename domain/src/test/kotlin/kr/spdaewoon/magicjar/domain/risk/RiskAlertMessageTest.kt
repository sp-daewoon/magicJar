package kr.spdaewoon.magicjar.domain.risk

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class RiskAlertMessageTest {
    @Test
    fun `TRADE_FREQUENCY_DAY - 일별 거래 한도를 한국어 수식으로 설명한다`() {
        val alert =
            RiskAlertMessage.from(
                rule = RiskRule.TRADE_FREQUENCY_DAY,
                reason = "Strategy daily trade count 50 >= limit 50",
                context = mapOf("recentTradesDay" to 50, "limit" to 50),
                code = "005930",
            )

        alert.title shouldBe "D. 거래 빈도 한도 도달 - 일별 최대 거래"
        alert.message shouldContain "오늘 이 전략"
        alert.formula shouldBe "오늘 허용 주문 수 50회 >= 일별 최대 거래 50회/일"
        alert.setting shouldBe "D. 거래 빈도 한도 - 일별 최대 거래"
    }

    @Test
    fun `POSITION_LIMIT - 절대 한도와 비율 한도 중 작은 값 공식을 포함한다`() {
        val alert =
            RiskAlertMessage.from(
                rule = RiskRule.POSITION_LIMIT,
                reason = "Unit price 80000 exceeds remaining position budget 70000",
                context =
                    mapOf(
                        "unitCost" to "80000",
                        "budget" to "1000000",
                        "remainingBudget" to "70000",
                        "existingExposure" to "930000",
                        "absLimit" to "1000000",
                        "percentBudget" to "2000000",
                        "currentCapital" to "20000000",
                        "maxPositionPercent" to "10",
                    ),
                code = "005930",
            )

        alert.title shouldContain "A. 포지션 한도"
        alert.formula!! shouldContain "min(절대 한도 1,000,000원, 총 자산 20,000,000원 x 비율 한도 10% = 2,000,000원)"
        alert.formula!! shouldContain "신호 단가 80,000원 > 남은 종목 한도 70,000원"
    }

    @Test
    fun `MAX_CONCURRENT_POSITIONS - 보유 종목 수 단위를 종목으로 설명한다`() {
        val alert =
            RiskAlertMessage.from(
                rule = RiskRule.MAX_CONCURRENT_POSITIONS,
                reason = "Concurrent positions 5 >= limit 5",
                context = emptyMap(),
            )

        alert.title shouldBe "C. 동시 보유 종목 한도 도달"
        alert.formula shouldBe "현재 보유 종목 수 5종목 >= 최대 보유 종목 수 5종목"
    }
}
