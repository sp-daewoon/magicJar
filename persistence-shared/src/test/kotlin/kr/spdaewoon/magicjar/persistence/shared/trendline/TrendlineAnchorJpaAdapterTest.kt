package kr.spdaewoon.magicjar.persistence.shared.trendline

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.toKotlinInstant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

/**
 * [TrendlineAnchorJpaAdapter] — anchor 매핑 + TTL 캐시 + 무효화 (ADR 0140 §3-2, spec R2 테스트).
 */
class TrendlineAnchorJpaAdapterTest {
    private val ts1 = Instant.parse("2026-01-02T00:00:00Z")
    private val ts2 = Instant.parse("2026-02-02T00:00:00Z")

    private fun row(id: Long) =
        TrendlineAnchorRow(
            id = id,
            anchor1Ts = ts1,
            anchor1Price = BigDecimal("100.0000"),
            anchor2Ts = ts2,
            anchor2Price = BigDecimal("120.0000"),
            extendRight = true,
            extendLeft = false,
        )

    @Test
    fun `anchor 매핑 — java Instant → kotlinx + extend 플래그 정합`() {
        val repo = mockk<TrendlineAnchorJpaRepository>()
        every { repo.findById(1L) } returns Optional.of(row(1L))
        val adapter = TrendlineAnchorJpaAdapter(repo, cacheTtlMs = 30_000)

        val anchors = adapter.anchorsOf(1L)!!

        assertEquals(ts1.toKotlinInstant(), anchors.anchor1Ts)
        assertEquals(ts2.toKotlinInstant(), anchors.anchor2Ts)
        assertEquals(BigDecimal("100.0000"), anchors.anchor1Price)
        assertEquals(BigDecimal("120.0000"), anchors.anchor2Price)
        assertTrue(anchors.extendRight)
        assertTrue(!anchors.extendLeft)
    }

    @Test
    fun `캐시 hit — TTL 내 2회 조회는 DB 1회`() {
        val repo = mockk<TrendlineAnchorJpaRepository>()
        every { repo.findById(1L) } returns Optional.of(row(1L))
        val adapter = TrendlineAnchorJpaAdapter(repo, cacheTtlMs = 30_000)

        adapter.anchorsOf(1L)
        adapter.anchorsOf(1L)

        verify(exactly = 1) { repo.findById(1L) }
    }

    @Test
    fun `무효화 — invalidate 후 재조회는 DB 재히트`() {
        val repo = mockk<TrendlineAnchorJpaRepository>()
        every { repo.findById(1L) } returns Optional.of(row(1L))
        val adapter = TrendlineAnchorJpaAdapter(repo, cacheTtlMs = 30_000)

        adapter.anchorsOf(1L)
        adapter.invalidate(1L)
        adapter.anchorsOf(1L)

        verify(exactly = 2) { repo.findById(1L) }
    }

    @Test
    fun `TTL 0 — 매 조회 만료돼 항상 DB 재히트`() {
        val repo = mockk<TrendlineAnchorJpaRepository>()
        every { repo.findById(1L) } returns Optional.of(row(1L))
        val adapter = TrendlineAnchorJpaAdapter(repo, cacheTtlMs = 0)

        adapter.anchorsOf(1L)
        adapter.anchorsOf(1L)

        verify(exactly = 2) { repo.findById(1L) }
    }

    @Test
    fun `부재 — 없는 선은 null이며 캐시하지 않아 재조회가 DB 재히트`() {
        val repo = mockk<TrendlineAnchorJpaRepository>()
        every { repo.findById(99L) } returns Optional.empty()
        val adapter = TrendlineAnchorJpaAdapter(repo, cacheTtlMs = 30_000)

        assertNull(adapter.anchorsOf(99L))
        assertNull(adapter.anchorsOf(99L))

        // 부재는 미캐시 → 선 재생성 즉시 반영(§11-2 정합).
        verify(exactly = 2) { repo.findById(99L) }
    }

    @Test
    fun `invalidateAll — 전체 비움`() {
        val repo = mockk<TrendlineAnchorJpaRepository>()
        every { repo.findById(any()) } answers { Optional.of(row(firstArg())) }
        val adapter = TrendlineAnchorJpaAdapter(repo, cacheTtlMs = 30_000)

        adapter.anchorsOf(1L)
        adapter.anchorsOf(2L)
        adapter.invalidateAll()
        adapter.anchorsOf(1L)
        adapter.anchorsOf(2L)

        verify(exactly = 2) { repo.findById(1L) }
        verify(exactly = 2) { repo.findById(2L) }
    }
}
