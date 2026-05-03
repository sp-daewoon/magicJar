package com.mj.consumer.application.signal

import com.mj.domain.market.event.TickEvent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * CandleBuffer 단위 테스트 — 분봉 누적·OOO·eviction.
 */
class CandleBufferTest {
    private val buf = CandleBuffer(maxBars = 100)

    @Test
    fun `같은 분 tick 누적 — 봉 1개 (진행 중)`() {
        val t0 = Instant.parse("2026-05-03T09:00:00Z")
        buf.ingest(tick("005930", t0, "100", 10))
        buf.ingest(tick("005930", t0.plusSeconds(10), "105", 5))
        buf.ingest(tick("005930", t0.plusSeconds(40), "98", 3))

        val s = buf.snapshot("005930")
        s.size shouldBe 1
        val c = s[0]
        c.open shouldBe BigDecimal("100")
        c.high shouldBe BigDecimal("105")
        c.low shouldBe BigDecimal("98")
        c.close shouldBe BigDecimal("98")
        c.volume shouldBe 18L
        c.tradeCount shouldBe 3L
    }

    @Test
    fun `새 분 tick — 직전 봉 closed + 새 봉 시작`() {
        val t0 = Instant.parse("2026-05-03T09:00:00Z")
        val t1 = t0.plusSeconds(60)
        buf.ingest(tick("005930", t0, "100", 10))
        buf.ingest(tick("005930", t0.plusSeconds(20), "105", 5))
        buf.ingest(tick("005930", t1, "110", 8))

        val s = buf.snapshot("005930")
        s.size shouldBe 2
        s[0].close shouldBe BigDecimal("105") // 직전 봉 마감가
        s[1].open shouldBe BigDecimal("110")
        s[1].close shouldBe BigDecimal("110")
    }

    @Test
    fun `종목별 분리`() {
        val t = Instant.parse("2026-05-03T09:00:00Z")
        buf.ingest(tick("005930", t, "100", 1))
        buf.ingest(tick("000660", t, "200", 1))

        buf.snapshot("005930").size shouldBe 1
        buf.snapshot("000660").size shouldBe 1
        buf.snapshot("005930")[0].close shouldBe BigDecimal("100")
        buf.snapshot("000660")[0].close shouldBe BigDecimal("200")
    }

    @Test
    fun `out-of-order tick 무시`() {
        val t0 = Instant.parse("2026-05-03T09:00:00Z")
        val t1 = t0.plusSeconds(60)
        buf.ingest(tick("005930", t1, "100", 1)) // 09:01
        buf.ingest(tick("005930", t0, "999", 1)) // 09:00 (옛날) — 무시

        val s = buf.snapshot("005930")
        s.size shouldBe 1
        s[0].close shouldBe BigDecimal("100") // 999 무시
    }

    @Test
    fun `maxBars 초과 시 FIFO eviction`() {
        val small = CandleBuffer(maxBars = 3)
        val base = Instant.parse("2026-05-03T09:00:00Z")
        // 5분 흐름 → 4봉 closed + 1봉 진행. maxBars=3 이므로 closed 2개만 유지.
        for (m in 0..4) {
            small.ingest(tick("005930", base.plusSeconds(60L * m), "${100 + m}", 1))
        }
        // 09:04 진행 중 → snapshot에 closed 3 + 진행 1 = 4 (maxBars는 closed에만 적용)
        // closed 4 → eviction 후 3 보존 + 진행 봉 1 = 4 snapshot
        val s = small.snapshot("005930")
        s.size shouldBe 4 // 3 closed + 1 in-progress
        s.last().close shouldBe BigDecimal("104")
    }

    private fun tick(
        code: String,
        at: Instant,
        price: String,
        volume: Long,
    ): TickEvent =
        TickEvent(
            code = code,
            at = at,
            price = BigDecimal(price),
            volume = volume,
            source = "TEST",
        )
}
