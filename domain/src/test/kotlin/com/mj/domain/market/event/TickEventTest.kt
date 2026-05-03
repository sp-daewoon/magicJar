package com.mj.domain.market.event

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * TickEvent 하위호환 테스트. T2-T12, ADR 0033.
 *
 * stale / asOf default 값이 기존 코드와 호환되는지 검증.
 */
class TickEventTest {
    @Test
    fun `기본 생성자 - stale=false, asOf=null 기본값`() {
        val tick =
            TickEvent(
                code = "005930",
                at = Instant.now(),
                price = BigDecimal("75000"),
                volume = 1_000_000L,
            )

        tick.stale shouldBe false
        tick.asOf shouldBe null
        tick.source shouldBe "KIS_WS"
    }

    @Test
    fun `source 명시 생성 - 기본값 유지`() {
        val tick =
            TickEvent(
                code = "005930",
                at = Instant.now(),
                price = BigDecimal("75000"),
                volume = 1_000_000L,
                source = "MOCK",
            )

        tick.stale shouldBe false
        tick.asOf shouldBe null
    }

    @Test
    fun `stale=true asOf 명시 생성`() {
        val asOf = Instant.parse("2026-05-02T09:30:00Z")
        val tick =
            TickEvent(
                code = "005930",
                at = Instant.now(),
                price = BigDecimal("74000"),
                volume = 0L,
                source = "CACHED",
                stale = true,
                asOf = asOf,
            )

        tick.stale shouldBe true
        tick.asOf shouldBe asOf
    }

    @Test
    fun `copy로 stale 마킹 - 원본 불변`() {
        val original =
            TickEvent(
                code = "005930",
                at = Instant.now(),
                price = BigDecimal("75000"),
                volume = 1_000_000L,
            )

        val stale = original.copy(stale = true, asOf = original.at)

        original.stale shouldBe false
        original.asOf shouldBe null
        stale.stale shouldBe true
        stale.asOf shouldBe original.at
        stale.price shouldBe original.price
    }
}
