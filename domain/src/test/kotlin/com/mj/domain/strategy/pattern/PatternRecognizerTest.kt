package com.mj.domain.strategy.pattern

import com.mj.domain.strategy.pattern.PatternTestFixtures.candle
import com.mj.domain.strategy.pattern.catalog.PatternCatalog
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PatternRecognizerTest {
    @Nested
    inner class Doji {
        @Test
        fun `완벽 도지 — 시가=종가, 양쪽 꼬리 동일`() {
            val candles = listOf(candle(0, 1000, 1010, 990, 1000))
            val match = PatternRecognizer.match(PatternCatalog.DOJI, candles, 0)
            match shouldNotBe null
            match!!.direction shouldBe PatternDirection.NEUTRAL
            match.strength shouldBe 1.0
        }

        @Test
        fun `near miss — 몸통이 range의 5퍼센트 강도 0,5 부근`() {
            // range=20, body=1 (5%) → strength 약 0.5
            val candles = listOf(candle(0, 1000, 1010, 990, 1001))
            val match = PatternRecognizer.match(PatternCatalog.DOJI, candles, 0)
            match shouldNotBe null
            match!!.strength shouldBeGreaterThan 0.4
            match.strength shouldBeLessThanOrEqual 0.6
        }

        @Test
        fun `정반대 — 큰 양봉은 도지 아님`() {
            val candles = listOf(candle(0, 1000, 1050, 990, 1045))
            PatternRecognizer.match(PatternCatalog.DOJI, candles, 0) shouldBe null
        }
    }

    @Nested
    inner class Hammer {
        @Test
        fun `전형 해머 — 작은 몸통 + 긴 아랫꼬리, 하락 추세`() {
            // 몸통 5(=close-open), range=30, lower=20, upper=5 → hammer 형태
            val hammerCandle = candle(5, 1000, 1005, 980, 1003)
            val candles = PatternTestFixtures.downtrendThen(hammerCandle)
            val match = PatternRecognizer.match(PatternCatalog.HAMMER, candles, 5)
            match shouldNotBe null
            match!!.direction shouldBe PatternDirection.BULL
            match.strength shouldBe 1.0
        }

        @Test
        fun `해머 형태 단독 — 추세 없으면 강도 낮음`() {
            val hammerCandle = candle(5, 1000, 1005, 980, 1003)
            val candles = PatternTestFixtures.flatThen(hammerCandle)
            val match = PatternRecognizer.match(PatternCatalog.HAMMER, candles, 5)
            match shouldNotBe null
            match!!.strength shouldBe 0.6
        }

        @Test
        fun `정반대 — 윗꼬리 긴 봉은 해머 아님`() {
            val notHammer = candle(5, 1000, 1030, 998, 1003)
            val candles = PatternTestFixtures.downtrendThen(notHammer)
            PatternRecognizer.match(PatternCatalog.HAMMER, candles, 5) shouldBe null
        }
    }

    @Nested
    inner class HangingMan {
        @Test
        fun `상승 추세 끝 해머 형태 — 매달린 사람 매칭`() {
            val hammerCandle = candle(5, 1100, 1105, 1080, 1103)
            val candles = PatternTestFixtures.uptrendThen(hammerCandle)
            val match = PatternRecognizer.match(PatternCatalog.HANGING_MAN, candles, 5)
            match shouldNotBe null
            match!!.direction shouldBe PatternDirection.BEAR
        }

        @Test
        fun `하락 추세 끝 해머 형태 — 매달린 사람 아님 (해머 영역)`() {
            val hammerCandle = candle(5, 1000, 1005, 980, 1003)
            val candles = PatternTestFixtures.downtrendThen(hammerCandle)
            PatternRecognizer.match(PatternCatalog.HANGING_MAN, candles, 5) shouldBe null
        }
    }

    @Nested
    inner class InvertedHammer {
        @Test
        fun `전형 역해머 — 작은 몸통 + 긴 윗꼬리, 하락 추세`() {
            val invertedCandle = candle(5, 1000, 1030, 998, 1003)
            val candles = PatternTestFixtures.downtrendThen(invertedCandle)
            val match = PatternRecognizer.match(PatternCatalog.INVERTED_HAMMER, candles, 5)
            match shouldNotBe null
            match!!.direction shouldBe PatternDirection.BULL
            match.strength shouldBe 1.0
        }
    }

    @Nested
    inner class ShootingStar {
        @Test
        fun `상승 추세 끝 역해머 형태 — 슈팅스타`() {
            val candleEnd = candle(5, 1100, 1130, 1098, 1103)
            val candles = PatternTestFixtures.uptrendThen(candleEnd)
            val match = PatternRecognizer.match(PatternCatalog.SHOOTING_STAR, candles, 5)
            match shouldNotBe null
            match!!.direction shouldBe PatternDirection.BEAR
        }
    }

    @Nested
    inner class Engulfing {
        @Test
        fun `강세 잉걸핑 — 음봉 + 더 큰 양봉이 음봉 몸통 감쌈`() {
            val candles =
                listOf(
                    candle(0, 1010, 1015, 990, 995), // 음봉
                    candle(1, 990, 1025, 985, 1020), // 양봉, 990≤995, 1020≥1010 → 감쌈
                )
            val match = PatternRecognizer.match(PatternCatalog.ENGULFING, candles, 1)
            match shouldNotBe null
            match!!.direction shouldBe PatternDirection.BULL
        }

        @Test
        fun `약세 잉걸핑 — 양봉 + 더 큰 음봉이 양봉 몸통 감쌈`() {
            val candles =
                listOf(
                    candle(0, 1000, 1015, 998, 1010), // 양봉
                    candle(1, 1020, 1025, 980, 990), // 음봉, 990≤1000, 1020≥1010 → 감쌈
                )
            val match = PatternRecognizer.match(PatternCatalog.ENGULFING, candles, 1)
            match shouldNotBe null
            match!!.direction shouldBe PatternDirection.BEAR
        }

        @Test
        fun `같은 색이면 잉걸핑 아님`() {
            val candles =
                listOf(
                    candle(0, 1000, 1010, 998, 1005),
                    candle(1, 998, 1020, 996, 1015),
                )
            PatternRecognizer.match(PatternCatalog.ENGULFING, candles, 1) shouldBe null
        }
    }

    @Nested
    inner class MorningStar {
        @Test
        fun `전형 샛별 — 큰 음봉 + 갭다운 작은 봉 + 양봉 절반 회복`() {
            val candles =
                listOf(
                    candle(0, 1100, 1105, 980, 990), // 큰 음봉, mid=1045
                    candle(1, 970, 980, 960, 975), // 작은 음봉/양봉, 갭다운 (몸통 high=975 < 990)
                    candle(2, 980, 1060, 975, 1050), // 양봉, close 1050 > mid 1045
                )
            val match = PatternRecognizer.match(PatternCatalog.MORNING_STAR, candles, 2)
            match shouldNotBe null
            match!!.direction shouldBe PatternDirection.BULL
        }

        @Test
        fun `세번째 양봉이 절반 못 회복하면 매칭 아님`() {
            val candles =
                listOf(
                    candle(0, 1100, 1105, 980, 990),
                    candle(1, 970, 980, 960, 975),
                    candle(2, 980, 1020, 975, 1010), // close 1010 ≤ mid 1045
                )
            PatternRecognizer.match(PatternCatalog.MORNING_STAR, candles, 2) shouldBe null
        }
    }

    @Nested
    inner class EveningStar {
        @Test
        fun `전형 저녁별 — 큰 양봉 + 갭업 작은 봉 + 음봉 절반 잠식`() {
            val candles =
                listOf(
                    candle(0, 990, 1110, 985, 1100), // 큰 양봉, mid=1045
                    candle(1, 1115, 1130, 1110, 1120), // 갭업 작은 양봉/음봉
                    candle(2, 1110, 1115, 990, 1000), // 음봉, close 1000 < mid 1045
                )
            val match = PatternRecognizer.match(PatternCatalog.EVENING_STAR, candles, 2)
            match shouldNotBe null
            match!!.direction shouldBe PatternDirection.BEAR
        }
    }

    @Nested
    inner class ThreeWhiteSoldiers {
        @Test
        fun `연속 3 양봉, 시가 직전 몸통 안, 종가 상승`() {
            val candles =
                listOf(
                    candle(0, 1000, 1020, 995, 1015), // 양봉 1000→1015
                    candle(1, 1010, 1035, 1005, 1030), // 시가 1010 ∈ [1000,1015], 종가 1030>1015
                    candle(2, 1025, 1050, 1020, 1045), // 시가 1025 ∈ [1010,1030], 종가 1045>1030
                )
            val match = PatternRecognizer.match(PatternCatalog.THREE_WHITE_SOLDIERS, candles, 2)
            match shouldNotBe null
            match!!.direction shouldBe PatternDirection.BULL
            match.strength shouldBe 1.0
        }

        @Test
        fun `중간 봉이 음봉이면 매칭 아님`() {
            val candles =
                listOf(
                    candle(0, 1000, 1020, 995, 1015),
                    candle(1, 1015, 1020, 1000, 1005), // 음봉
                    candle(2, 1010, 1030, 1005, 1025),
                )
            PatternRecognizer.match(PatternCatalog.THREE_WHITE_SOLDIERS, candles, 2) shouldBe null
        }
    }

    @Nested
    inner class ThreeBlackCrows {
        @Test
        fun `연속 3 음봉, 시가 직전 몸통 안, 종가 하락`() {
            val candles =
                listOf(
                    candle(0, 1050, 1055, 1015, 1020), // 음봉 1050→1020
                    candle(1, 1040, 1045, 1000, 1005), // 시가 1040 ∈ [1020,1050], 종가 1005<1020
                    candle(2, 1025, 1030, 985, 990), // 시가 1025 ∈ [1005,1040], 종가 990<1005
                )
            val match = PatternRecognizer.match(PatternCatalog.THREE_BLACK_CROWS, candles, 2)
            match shouldNotBe null
            match!!.direction shouldBe PatternDirection.BEAR
        }
    }

    @Nested
    inner class RecognizeAggregate {
        @Test
        fun `recognize — 빈 리스트는 빈 결과`() {
            PatternRecognizer.recognize(emptyList()) shouldBe emptyList()
        }

        @Test
        fun `recognize — 도지 1봉이면 1건 매칭`() {
            val candles = listOf(candle(0, 1000, 1010, 990, 1000))
            val matches = PatternRecognizer.recognize(candles)
            matches.size shouldBe 1
            matches.first().pattern shouldBe PatternCatalog.DOJI
        }

        @Test
        fun `recognize — lookback 만큼만 검사`() {
            // 100봉 중 마지막 5봉만 도지로 채우고 lookback=3
            val flat = (0..94).map { candle(it, 1000, 1005, 995, 1003) }
            val dojis = (95..99).map { candle(it, 1000, 1010, 990, 1000) }
            val all = flat + dojis
            val matches = PatternRecognizer.recognize(all, lookback = 3)
            matches.shouldNotBeEmpty()
            // 모든 매칭의 endIndex는 [97, 99] 범위
            matches.all { it.endIndex in 97..99 } shouldBe true
        }

        @Test
        fun `recognize — 미구현 패턴은 빈 리스트 반환 (회귀 가드)`() {
            val candles = (0..10).map { candle(it, 1000, 1010, 990, 1005) }
            val match = PatternRecognizer.match(PatternCatalog.HARAMI, candles, 5)
            // R1에서 Harami 미구현, 기존 테스트가 깨지지 않음을 보장
            match shouldBe null
        }
    }
}
