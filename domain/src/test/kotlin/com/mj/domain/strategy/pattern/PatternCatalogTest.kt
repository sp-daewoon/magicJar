package com.mj.domain.strategy.pattern

import com.mj.domain.strategy.pattern.catalog.PatternCatalog
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class PatternCatalogTest {
    @Test
    fun `카탈로그 크기 정확히 57`() {
        PatternCatalog.ALL.size shouldBe 57
    }

    @Test
    fun `code 중복 없음`() {
        val codes = PatternCatalog.ALL.map { it.code }
        codes.distinct().size shouldBe codes.size
    }

    @Test
    fun `code는 snake_case 소문자`() {
        PatternCatalog.ALL.forEach { p ->
            p.code.all { it.isLowerCase() || it.isDigit() || it == '_' } shouldBe true
        }
    }

    @Test
    fun `findByCode 정상 동작`() {
        PatternCatalog.findByCode("morning_star") shouldBe PatternCatalog.MORNING_STAR
        PatternCatalog.findByCode("not_a_pattern") shouldBe null
    }

    @Test
    fun `byCategory 분류 일관성`() {
        val bullishReversal = PatternCatalog.byCategory(PatternCategory.BULLISH_REVERSAL)
        bullishReversal shouldContain PatternCatalog.HAMMER
        bullishReversal shouldContain PatternCatalog.MORNING_STAR
        bullishReversal shouldNotBe emptyList<CandlePattern>()

        val bearishReversal = PatternCatalog.byCategory(PatternCategory.BEARISH_REVERSAL)
        bearishReversal shouldContain PatternCatalog.SHOOTING_STAR
        bearishReversal shouldContain PatternCatalog.EVENING_STAR
    }

    @Test
    fun `byCandleCount 그룹핑`() {
        val singles = PatternCatalog.byCandleCount(1)
        singles.size shouldBe 14
        val doubles = PatternCatalog.byCandleCount(2)
        doubles.size shouldBe 16
        // triple+은 3,4,5 캔들이 섞여 있음
        val triples = PatternCatalog.byCandleCount(3)
        val fourCandle = PatternCatalog.byCandleCount(4)
        val fiveCandle = PatternCatalog.byCandleCount(5)
        (triples.size + fourCandle.size + fiveCandle.size) shouldBe 27
    }

    @Test
    fun `Lean 미지원 9개는 카탈로그에 없음`() {
        val excluded =
            listOf(
                "opening_marubozu",
                "tweezer_top",
                "tweezer_bottom",
                "meeting_lines",
                "matching_high",
                "deliberation",
                "rising_three_methods",
                "falling_three_methods",
                "side_by_side_white_lines",
            )
        excluded.forEach { code ->
            PatternCatalog.findByCode(code) shouldBe null
        }
    }
}
