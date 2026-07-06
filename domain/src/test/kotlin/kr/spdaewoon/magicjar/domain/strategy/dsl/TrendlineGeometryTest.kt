package kr.spdaewoon.magicjar.domain.strategy.dsl

import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [TrendlineGeometry] 선형보간 순수함수 단위 테스트 (ADR 0140 §5).
 *
 * 커버리지: 중간 보간 · 기울기 양/음/수평(0) · 경계(anchor 정확 일치) · extend 4조합 · anchor 역순 · 퇴화(수직).
 */
class TrendlineGeometryTest {
    private fun ts(epochSec: Long): Instant = Instant.fromEpochSeconds(epochSec)

    private fun bd(v: String): BigDecimal = BigDecimal(v)

    /** t0=1000s 가격 100, t1=2000s 가격 200 (기울기 양, 1000s당 +100). */
    private fun risingLine(
        extendLeft: Boolean = false,
        extendRight: Boolean = false,
    ) = TrendlineAnchors(
        anchor1Ts = ts(1000),
        anchor1Price = bd("100"),
        anchor2Ts = ts(2000),
        anchor2Price = bd("200"),
        extendLeft = extendLeft,
        extendRight = extendRight,
    )

    @Test
    fun `중간 보간 — 정확히 절반 시점은 두 가격의 중간`() {
        val price = TrendlineGeometry.priceAt(risingLine(), ts(1500))
        assertEquals(0, bd("150").compareTo(price), "1500s 시점 = 150 기대, 실제=$price")
    }

    @Test
    fun `경계 — anchor1 시점 정확 일치는 anchor1 가격`() {
        val price = TrendlineGeometry.priceAt(risingLine(), ts(1000))
        assertEquals(0, bd("100").compareTo(price))
    }

    @Test
    fun `경계 — anchor2 시점 정확 일치는 anchor2 가격`() {
        val price = TrendlineGeometry.priceAt(risingLine(), ts(2000))
        assertEquals(0, bd("200").compareTo(price))
    }

    @Test
    fun `기울기 음 — 하락선 보간`() {
        val falling =
            TrendlineAnchors(
                anchor1Ts = ts(1000),
                anchor1Price = bd("200"),
                anchor2Ts = ts(2000),
                anchor2Price = bd("100"),
            )
        val price = TrendlineGeometry.priceAt(falling, ts(1500))
        assertEquals(0, bd("150").compareTo(price))
    }

    @Test
    fun `수평선 — 기울기 0이면 모든 구간에서 상수`() {
        val flat =
            TrendlineAnchors(
                anchor1Ts = ts(1000),
                anchor1Price = bd("150"),
                anchor2Ts = ts(2000),
                anchor2Price = bd("150"),
            )
        assertEquals(0, bd("150").compareTo(TrendlineGeometry.priceAt(flat, ts(1200))))
        assertEquals(0, bd("150").compareTo(TrendlineGeometry.priceAt(flat, ts(1900))))
    }

    // ── 범위 판정 (extend 4조합) ────────────────────────────────────────

    @Test
    fun `범위 밖 오른쪽 — extendRight=false면 null`() {
        assertNull(TrendlineGeometry.priceAt(risingLine(extendRight = false), ts(2500)))
    }

    @Test
    fun `범위 밖 왼쪽 — extendLeft=false면 null`() {
        assertNull(TrendlineGeometry.priceAt(risingLine(extendLeft = false), ts(500)))
    }

    @Test
    fun `extendRight=true — anchor2 이후 기울기 연장 외삽`() {
        // 2500s = anchor2(2000s,200)에서 +500s → +50 = 250
        val price = TrendlineGeometry.priceAt(risingLine(extendRight = true), ts(2500))
        assertEquals(0, bd("250").compareTo(price))
    }

    @Test
    fun `extendLeft=true — anchor1 이전 기울기 연장 외삽`() {
        // 500s = anchor1(1000s,100)에서 -500s → -50 = 50
        val price = TrendlineGeometry.priceAt(risingLine(extendLeft = true), ts(500))
        assertEquals(0, bd("50").compareTo(price))
    }

    @Test
    fun `extend 조합 — left=true right=false는 왼쪽만 연장 오른쪽은 null`() {
        val line = risingLine(extendLeft = true, extendRight = false)
        assertEquals(0, bd("50").compareTo(TrendlineGeometry.priceAt(line, ts(500))))
        assertNull(TrendlineGeometry.priceAt(line, ts(2500)))
    }

    @Test
    fun `extend 조합 — left=false right=true는 오른쪽만 연장 왼쪽은 null`() {
        val line = risingLine(extendLeft = false, extendRight = true)
        assertNull(TrendlineGeometry.priceAt(line, ts(500)))
        assertEquals(0, bd("250").compareTo(TrendlineGeometry.priceAt(line, ts(2500))))
    }

    // ── anchor 역순 정규화 ──────────────────────────────────────────────

    @Test
    fun `anchor 시각 역순 — 시간 기준 정규화 후 동일 보간`() {
        // anchor1이 늦은 시각(2000s,200), anchor2가 이른 시각(1000s,100) — 저장 순서 역전
        val reversed =
            TrendlineAnchors(
                anchor1Ts = ts(2000),
                anchor1Price = bd("200"),
                anchor2Ts = ts(1000),
                anchor2Price = bd("100"),
            )
        assertEquals(0, bd("150").compareTo(TrendlineGeometry.priceAt(reversed, ts(1500))))
    }

    @Test
    fun `anchor 역순 + extendLeft — 왼쪽(과거) 경계는 extendLeft 플래그에 결속`() {
        // 역순 저장이어도 extendLeft는 과거 방향(500s)에 적용
        val reversed =
            TrendlineAnchors(
                anchor1Ts = ts(2000),
                anchor1Price = bd("200"),
                anchor2Ts = ts(1000),
                anchor2Price = bd("100"),
                extendLeft = true,
                extendRight = false,
            )
        assertEquals(0, bd("50").compareTo(TrendlineGeometry.priceAt(reversed, ts(500))))
        assertNull(TrendlineGeometry.priceAt(reversed, ts(2500)))
    }

    // ── 퇴화(수직선) ────────────────────────────────────────────────────

    @Test
    fun `퇴화 — 두 anchor 시각 동일(수직선)은 null`() {
        val vertical =
            TrendlineAnchors(
                anchor1Ts = ts(1000),
                anchor1Price = bd("100"),
                anchor2Ts = ts(1000),
                anchor2Price = bd("200"),
                extendLeft = true,
                extendRight = true,
            )
        assertNull(TrendlineGeometry.priceAt(vertical, ts(1000)))
        assertNull(TrendlineGeometry.priceAt(vertical, ts(1500)))
    }

    @Test
    fun `결과 scale=8 정규화`() {
        // 1/3 시점 보간이 딱 떨어지지 않는 케이스 — scale 8 유지 확인
        val line =
            TrendlineAnchors(
                anchor1Ts = ts(0),
                anchor1Price = bd("0"),
                anchor2Ts = ts(3),
                anchor2Price = bd("1"),
            )
        val price = TrendlineGeometry.priceAt(line, ts(1))!!
        assertEquals(8, price.scale())
        // 1/3 ≈ 0.33333333
        assertTrue(price.compareTo(bd("0.33333333")) == 0, "실제=$price")
    }
}
