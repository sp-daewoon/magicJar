package com.mj.domain.strategy.indicator

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SmaTest {
    @Test
    fun `SMA undefined for periods shorter than window`() {
        val values = listOf("1", "2", "3").map { BigDecimal(it) }
        val out = Sma.of(values, 5)
        out shouldBe listOf(null, null, null)
    }

    @Test
    fun `SMA computes rolling mean`() {
        val values = listOf("1", "2", "3", "4", "5").map { BigDecimal(it) }
        val out = Sma.of(values, 3)
        out[0] shouldBe null
        out[1] shouldBe null
        out[2]!!.toDouble() shouldBe 2.0
        out[3]!!.toDouble() shouldBe 3.0
        out[4]!!.toDouble() shouldBe 4.0
    }
}
