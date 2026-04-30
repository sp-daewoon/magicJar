package com.mj.domain.ai

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SentimentScoreTest {
    @Test
    fun `normalize clamps confidence to 0_0 ~ 1_0`() {
        SentimentScore.normalize(-0.5) shouldBe BigDecimal("0.00")
        SentimentScore.normalize(1.5) shouldBe BigDecimal("1.00")
        SentimentScore.normalize(0.756) shouldBe BigDecimal("0.76")
        SentimentScore.normalize(0.0) shouldBe BigDecimal("0.00")
        SentimentScore.normalize(1.0) shouldBe BigDecimal("1.00")
    }

    @Test
    fun `of constructs the correct subtype with normalized confidence`() {
        val s = SentimentScore.of(SentimentScore.Label.POSITIVE, 0.8, "  호실적 발표  ")
        (s is SentimentScore.Positive) shouldBe true
        s.label shouldBe SentimentScore.Label.POSITIVE
        s.confidence shouldBe BigDecimal("0.80")
        s.rationale shouldBe "호실적 발표"

        val n = SentimentScore.of(SentimentScore.Label.NEGATIVE, 0.42, "실적 쇼크")
        (n is SentimentScore.Negative) shouldBe true
        n.confidence shouldBe BigDecimal("0.42")

        val u = SentimentScore.of(SentimentScore.Label.NEUTRAL, 0.1, "정보 부족")
        (u is SentimentScore.Neutral) shouldBe true
    }

    @Test
    fun `parseLabel maps known aliases and falls back to NEUTRAL`() {
        SentimentScore.parseLabel("POSITIVE") shouldBe SentimentScore.Label.POSITIVE
        SentimentScore.parseLabel("positive") shouldBe SentimentScore.Label.POSITIVE
        SentimentScore.parseLabel("POS") shouldBe SentimentScore.Label.POSITIVE
        SentimentScore.parseLabel("+") shouldBe SentimentScore.Label.POSITIVE
        SentimentScore.parseLabel("NEGATIVE") shouldBe SentimentScore.Label.NEGATIVE
        SentimentScore.parseLabel("NEG") shouldBe SentimentScore.Label.NEGATIVE
        SentimentScore.parseLabel("-") shouldBe SentimentScore.Label.NEGATIVE
        SentimentScore.parseLabel("NEUTRAL") shouldBe SentimentScore.Label.NEUTRAL
        SentimentScore.parseLabel(null) shouldBe SentimentScore.Label.NEUTRAL
        SentimentScore.parseLabel("ambivalent") shouldBe SentimentScore.Label.NEUTRAL
        SentimentScore.parseLabel("") shouldBe SentimentScore.Label.NEUTRAL
    }

    @Test
    fun `equality holds for value semantics`() {
        val a = SentimentScore.of(SentimentScore.Label.POSITIVE, 0.7, "동일 근거")
        val b = SentimentScore.of(SentimentScore.Label.POSITIVE, 0.7, "동일 근거")
        a shouldBe b
    }
}
