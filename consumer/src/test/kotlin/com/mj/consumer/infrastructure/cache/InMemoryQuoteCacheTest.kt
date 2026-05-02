package com.mj.consumer.infrastructure.cache

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * InMemoryQuoteCacheAdapter 단위 테스트. spec §5.4, ADR 0033.
 *
 * QuoteCache 포트 계약 검증:
 * 1. put → get round-trip
 * 2. 미존재 key → null
 * 3. stale 판정 — 10초 이전 tick
 * 4. fresh 판정 — 직전 tick
 * 5. volume nullable
 */
class InMemoryQuoteCacheTest {
    private lateinit var cache: InMemoryQuoteCacheAdapter

    @BeforeEach
    fun setUp() {
        cache = InMemoryQuoteCacheAdapter()
        cache.clear()
    }

    @Test
    fun `put-get round-trip`() {
        val now = Instant.now()
        cache.putLatest("005930", BigDecimal("75000"), 1000L, now, "live")

        val snap = cache.getLatest("005930")
        snap shouldNotBe null
        snap!!.code shouldBe "005930"
        snap.price shouldBe BigDecimal("75000")
        snap.volume shouldBe 1000L
        snap.source shouldBe "live"
    }

    @Test
    fun `미존재 key - null 반환`() {
        cache.getLatest("999999") shouldBe null
    }

    @Test
    fun `stale - 10초 이전 tick`() {
        val oldInstant = Instant.now().minusSeconds(11)
        cache.putLatest("005930", BigDecimal("75000"), null, oldInstant, "live")
        val snap = cache.getLatest("005930")
        snap shouldNotBe null
        snap!!.stale.shouldBeTrue()
    }

    @Test
    fun `fresh - 직전 tick`() {
        val now = Instant.now()
        cache.putLatest("005930", BigDecimal("75000"), null, now, "live")
        val snap = cache.getLatest("005930")
        snap shouldNotBe null
        snap!!.stale.shouldBeFalse()
    }

    @Test
    fun `volume nullable - null volume 저장·조회`() {
        cache.putLatest("000660", BigDecimal("120000"), null, Instant.now(), "mock")
        val snap = cache.getLatest("000660")
        snap shouldNotBe null
        snap!!.volume shouldBe null
    }

    @Test
    fun `getStale delegates to getLatest`() {
        val now = Instant.now()
        cache.putLatest("035720", BigDecimal("55000"), 500L, now, "live")
        val fromStale = cache.getStale("035720")
        val fromLatest = cache.getLatest("035720")
        fromStale shouldBe fromLatest
    }

    @Test
    fun `multiple codes stored independently`() {
        val now = Instant.now()
        cache.putLatest("005930", BigDecimal("75000"), null, now, "live")
        cache.putLatest("000660", BigDecimal("120000"), null, now, "live")

        cache.getLatest("005930")!!.price shouldBe BigDecimal("75000")
        cache.getLatest("000660")!!.price shouldBe BigDecimal("120000")
    }

    @Test
    fun `overwrite latest price`() {
        val now = Instant.now()
        cache.putLatest("005930", BigDecimal("75000"), null, now, "live")
        cache.putLatest("005930", BigDecimal("76000"), null, now.plusSeconds(1), "live")
        cache.getLatest("005930")!!.price shouldBe BigDecimal("76000")
    }
}
