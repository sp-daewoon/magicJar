package com.mj.domain.market.corp_action

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * AdjustedPriceCalculator 단위 테스트 (ADR 0038).
 *
 * 검증 범위:
 * 1. 액면분할 50:1 보정
 * 2. 무상증자 보정
 * 3. 현금 배당 보정 계수
 * 4. 복수 Corp Action 누적 계수
 * 5. 룩어헤드 차단 — t < ex_date 는 보정 계수 적용, t >= ex_date 는 계수 1.0
 * 6. 배치 처리 정확성
 */
@DisplayName("AdjustedPriceCalculator")
class AdjustedPriceCalculatorTest {
    private val calculator = AdjustedPriceCalculator()

    // -------------------------------------------------------------------------
    // 1. 액면분할 (Stock Split)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("액면분할")
    inner class SplitTests {
        @Test
        @DisplayName("50:1 분할 — ex_date 이전 주가는 1/50으로 보정된다")
        fun `50-to-1 split adjusts pre-split price by 1-50`() {
            val splitDate = LocalDate.of(2024, 4, 26) // 삼성전자 액면분할
            val split =
                CorpAction.Split(
                    instrumentCode = "005930",
                    exDate = splitDate,
                    ratio = BigDecimal("50"),
                )
            val preDate = LocalDate.of(2024, 4, 25)
            val rawPrice = BigDecimal("2500000")

            val adjusted = calculator.calculateAdjusted(rawPrice, preDate, listOf(split))

            // 2,500,000 / 50 = 50,000
            adjusted.toDouble() shouldBe (50000.0 plusOrMinus 1.0)
        }

        @Test
        @DisplayName("ex_date 당일 주가는 이미 분할 반영 — 보정 계수 1.0")
        fun `price on ex_date is already post-split - no adjustment`() {
            val split =
                CorpAction.Split(
                    instrumentCode = "005930",
                    exDate = LocalDate.of(2024, 4, 26),
                    ratio = BigDecimal("50"),
                )
            val onDate = LocalDate.of(2024, 4, 26) // ex_date 당일

            val factor = calculator.calculateCumulativeFactor(onDate, listOf(split))

            // ex_date 당일(priceDate >= exDate)은 보정 없음
            factor shouldBe BigDecimal.ONE
        }

        @Test
        @DisplayName("ex_date 이후 주가는 보정하지 않는다")
        fun `price after ex_date is not adjusted`() {
            val split =
                CorpAction.Split(
                    instrumentCode = "005930",
                    exDate = LocalDate.of(2024, 4, 26),
                    ratio = BigDecimal("50"),
                )
            val afterDate = LocalDate.of(2024, 5, 1) // ex_date 이후

            val factor = calculator.calculateCumulativeFactor(afterDate, listOf(split))

            factor shouldBe BigDecimal.ONE
        }

        @Test
        @DisplayName("2:1 분할 — 과거 주가 절반으로 보정")
        fun `2-to-1 split halves pre-split price`() {
            val split =
                CorpAction.Split(
                    instrumentCode = "TEST01",
                    exDate = LocalDate.of(2023, 6, 1),
                    ratio = BigDecimal("2"),
                )
            val rawPrice = BigDecimal("100000")
            val preDate = LocalDate.of(2023, 5, 31)

            val adjusted = calculator.calculateAdjusted(rawPrice, preDate, listOf(split))

            adjusted.toDouble() shouldBe (50000.0 plusOrMinus 1.0)
        }
    }

    // -------------------------------------------------------------------------
    // 2. 무상증자 (Bonus Issue)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("무상증자")
    inner class BonusIssueTests {
        @Test
        @DisplayName("10% 무상증자 — 과거 주가는 1/1.1 로 보정된다")
        fun `10-percent-bonus-issue adjusts by 1-over-1-1`() {
            val bonusIssue =
                CorpAction.BonusIssue(
                    instrumentCode = "000660",
                    exDate = LocalDate.of(2023, 3, 15),
                    ratio = BigDecimal("0.1"),
                )
            val rawPrice = BigDecimal("110000")
            val preDate = LocalDate.of(2023, 3, 14)

            val adjusted = calculator.calculateAdjusted(rawPrice, preDate, listOf(bonusIssue))

            // 110,000 / 1.1 = 100,000
            adjusted.toDouble() shouldBe (100000.0 plusOrMinus 1.0)
        }
    }

    // -------------------------------------------------------------------------
    // 3. 현금 배당 (Cash Dividend)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("현금 배당 보정 계수")
    inner class DividendTests {
        @Test
        @DisplayName("주당 배당금 500원, 기준주가 50000원 → 보정 계수 49500/50000")
        fun `dividend factor is adjusted-price over base-price`() {
            val factor =
                calculator.dividendFactor(
                    cashDividend = BigDecimal("500"),
                    basePrice = BigDecimal("50000"),
                )

            val expected = 49500.0 / 50000.0
            factor.toDouble() shouldBe (expected plusOrMinus 0.00001)
        }

        @Test
        @DisplayName("기준주가 0이면 보정 계수 1.0 반환")
        fun `zero base price returns factor 1`() {
            val factor =
                calculator.dividendFactor(
                    cashDividend = BigDecimal("500"),
                    basePrice = BigDecimal.ZERO,
                )

            factor shouldBe BigDecimal.ONE
        }
    }

    // -------------------------------------------------------------------------
    // 4. 복수 Corp Action 누적 계수
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("복수 Corp Action 누적")
    inner class MultiCorpActionTests {
        @Test
        @DisplayName("분할 50:1 + 무상증자 10% → 누적 계수 (1/50) * (1/1.1)")
        fun `multiple corp actions accumulate factors correctly`() {
            val splitDate = LocalDate.of(2024, 4, 26)
            val bonusDate = LocalDate.of(2023, 3, 15)

            val split =
                CorpAction.Split(
                    instrumentCode = "005930",
                    exDate = splitDate,
                    ratio = BigDecimal("50"),
                )
            val bonus =
                CorpAction.BonusIssue(
                    instrumentCode = "005930",
                    exDate = bonusDate,
                    ratio = BigDecimal("0.1"),
                )

            // 2022-01-01 주가 기준 — 두 Corp Action 모두 미래
            val priceDate = LocalDate.of(2022, 1, 1)

            val factor = calculator.calculateCumulativeFactor(priceDate, listOf(split, bonus))

            // factor = (1/50) * (1/1.1) = 0.01818...
            val expectedFactor = (1.0 / 50.0) * (1.0 / 1.1)
            factor.toDouble() shouldBe (expectedFactor plusOrMinus 0.0001)
        }

        @Test
        @DisplayName("Corp Action 없으면 계수 1.0")
        fun `no corp actions returns factor 1`() {
            val factor =
                calculator.calculateCumulativeFactor(
                    LocalDate.of(2023, 1, 1),
                    emptyList(),
                )
            factor shouldBe BigDecimal.ONE
        }
    }

    // -------------------------------------------------------------------------
    // 5. 룩어헤드 차단 검증 (ADR 0038 핵심)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("룩어헤드 차단 (ADR 0038 핵심)")
    inner class LookAheadBiasTests {
        /**
         * 백테스트 룩어헤드 차단 핵심 검증:
         *
         * 시점 t 기준:
         * - t < ex_date → 미래 Corp Action이므로 retroactive 보정 계수 적용
         * - t >= ex_date → 이미 시장 반영 → 계수 1.0
         *
         * AdjustedPriceCalculator는 priceDate < exDate 인 Corp Action들의 계수만 누적한다.
         */
        @Test
        @DisplayName("t < ex_date — 미래 Corp Action 기준으로 역산 보정 계수 적용")
        fun `price before ex_date gets retroactive adjustment factor`() {
            val split =
                CorpAction.Split(
                    instrumentCode = "005930",
                    exDate = LocalDate.of(2024, 4, 26),
                    ratio = BigDecimal("50"),
                )

            // 분할 전날 → Corp Action이 미래이므로 보정 계수 적용 (retroactive)
            val preDateFactor =
                calculator.calculateCumulativeFactor(
                    LocalDate.of(2024, 4, 25), // t < ex_date
                    listOf(split),
                )
            preDateFactor.toDouble() shouldBe (0.02 plusOrMinus 0.0001) // 1/50
        }

        @Test
        @DisplayName("t >= ex_date — 이미 반영된 시세, 보정 계수 1.0")
        fun `price on or after ex_date uses raw price factor is 1`() {
            val split =
                CorpAction.Split(
                    instrumentCode = "005930",
                    exDate = LocalDate.of(2024, 4, 26),
                    ratio = BigDecimal("50"),
                )

            // ex_date 당일 → 이미 시장 반영 → 보정 없음
            val onDateFactor =
                calculator.calculateCumulativeFactor(
                    LocalDate.of(2024, 4, 26), // t == ex_date
                    listOf(split),
                )
            onDateFactor shouldBe BigDecimal.ONE

            // ex_date 이후 → 보정 없음
            val afterFactor =
                calculator.calculateCumulativeFactor(
                    LocalDate.of(2024, 4, 27), // t > ex_date
                    listOf(split),
                )
            afterFactor shouldBe BigDecimal.ONE
        }

        @Test
        @DisplayName("백테스트 진입 시점 t에서 과거 Corp Action은 무시, 미래만 보정")
        fun `backtest at time t applies only future-relative corp actions`() {
            val earlyBonus =
                CorpAction.BonusIssue(
                    instrumentCode = "TEST01",
                    exDate = LocalDate.of(2022, 6, 1),
                    ratio = BigDecimal("0.1"),
                )
            val laterSplit =
                CorpAction.Split(
                    instrumentCode = "TEST01",
                    exDate = LocalDate.of(2023, 6, 1),
                    ratio = BigDecimal("2"),
                )

            // 2022-01-01 기준: earlyBonus(2022-06-01), laterSplit(2023-06-01) 모두 미래
            val t1Factor =
                calculator.calculateCumulativeFactor(
                    LocalDate.of(2022, 1, 1),
                    listOf(earlyBonus, laterSplit),
                )
            // (1/1.1) * (1/2) = 0.4545...
            t1Factor.toDouble() shouldBe ((1.0 / 1.1 / 2.0) plusOrMinus 0.001)

            // 2022-07-01 기준: earlyBonus(2022-06-01)은 이미 반영, laterSplit(2023-06-01)만 미래
            val t2Factor =
                calculator.calculateCumulativeFactor(
                    LocalDate.of(2022, 7, 1),
                    listOf(earlyBonus, laterSplit),
                )
            // 1/2 = 0.5 (earlyBonus는 과거이므로 제외)
            t2Factor.toDouble() shouldBe (0.5 plusOrMinus 0.001)

            // 2023-07-01 기준: 모두 반영 → 계수 1.0
            val t3Factor =
                calculator.calculateCumulativeFactor(
                    LocalDate.of(2023, 7, 1),
                    listOf(earlyBonus, laterSplit),
                )
            t3Factor shouldBe BigDecimal.ONE
        }
    }

    // -------------------------------------------------------------------------
    // 6. 배치 처리
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("배치 처리")
    inner class BatchTests {
        @Test
        @DisplayName("calculateBatch — 각 날짜별 adjusted 가격 올바르게 계산")
        fun `calculateBatch returns adjusted results for each date`() {
            val split =
                CorpAction.Split(
                    instrumentCode = "005930",
                    exDate = LocalDate.of(2024, 4, 26),
                    ratio = BigDecimal("50"),
                )

            val pairs =
                listOf(
                    LocalDate.of(2024, 4, 25) to BigDecimal("2500000"), // pre-split → adjusted
                    LocalDate.of(2024, 4, 26) to BigDecimal("50000"), // ex-date → raw
                    LocalDate.of(2024, 4, 27) to BigDecimal("51000"), // post-split → raw
                )

            val results = calculator.calculateBatch(pairs, listOf(split))

            results.size shouldBe 3
            // pre-split: 2,500,000 / 50 = 50,000
            results[0].adjustedClose.toDouble() shouldBe (50000.0 plusOrMinus 1.0)
            results[0].adjustmentFactor.toDouble() shouldBe (0.02 plusOrMinus 0.0001)
            // ex-date: no adjustment (compareTo 0 = 값 동등)
            (results[1].adjustedClose.compareTo(results[1].rawClose)) shouldBe 0
            // post-split: no adjustment
            (results[2].adjustedClose.compareTo(results[2].rawClose)) shouldBe 0
        }
    }
}
