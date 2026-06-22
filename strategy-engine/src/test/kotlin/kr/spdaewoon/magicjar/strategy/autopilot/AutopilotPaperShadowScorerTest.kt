package kr.spdaewoon.magicjar.strategy.autopilot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * [AutopilotPaperShadowScorer] 페이퍼 섀도 스코어링 순수 함수 단위 테스트 (개선 G · 결정론).
 *
 * 시나리오 기준: entry=10,000 / TP=2% (= 10,200) / SL=1.5% (= 9,850).
 * 판정 룰: 진입 다음 캔들부터 SL 우선(intrabar 모호 시 손실측). 미도달이면 Open.
 */
class AutopilotPaperShadowScorerTest {
    private val entry = BigDecimal("10000")
    private val tpPct = BigDecimal("2")
    private val slPct = BigDecimal("1.5")

    private fun bar(
        high: String,
        low: String,
        tsSec: Long,
    ) = AutopilotPaperShadowScorer.Bar(
        high = BigDecimal(high),
        low = BigDecimal(low),
        ts = Instant.ofEpochSecond(tsSec),
    )

    @Test
    fun `TP 먼저 도달 — Win (exitPrice=목표가)`() {
        val bars =
            listOf(
                bar(high = "10100", low = "9950", tsSec = 1), // 미도달
                bar(high = "10250", low = "10050", tsSec = 2), // high >= 10,200 → TP
            )
        val outcome = AutopilotPaperShadowScorer.score(entry, tpPct, slPct, bars)
        val win = assertInstanceOf(ShadowOutcome.Win::class.java, outcome)
        assertEquals(0, win.exitPrice.compareTo(BigDecimal("10200")))
        assertEquals(Instant.ofEpochSecond(2), win.hitBarTs)
    }

    @Test
    fun `SL 먼저 도달 — Loss (exitPrice=손절가)`() {
        val bars =
            listOf(
                bar(high = "10100", low = "9900", tsSec = 1), // 미도달
                bar(high = "10150", low = "9800", tsSec = 2), // low <= 9,850 → SL
            )
        val outcome = AutopilotPaperShadowScorer.score(entry, tpPct, slPct, bars)
        val loss = assertInstanceOf(ShadowOutcome.Loss::class.java, outcome)
        assertEquals(0, loss.exitPrice.compareTo(BigDecimal("9850")))
    }

    @Test
    fun `intrabar 모호(같은 캔들 TP·SL 모두 도달) — SL 우선 Loss (과대평가 방지)`() {
        // high 10,300(>=TP) 이면서 low 9,800(<=SL) — 어느 쪽이 먼저인지 캔들만으론 모름 → 손실측 채택.
        val bars = listOf(bar(high = "10300", low = "9800", tsSec = 1))
        val outcome = AutopilotPaperShadowScorer.score(entry, tpPct, slPct, bars)
        assertInstanceOf(ShadowOutcome.Loss::class.java, outcome)
    }

    @Test
    fun `TP·SL 미도달 — Open (다음 주기 재평가)`() {
        val bars =
            listOf(
                bar(high = "10100", low = "9900", tsSec = 1),
                bar(high = "10150", low = "9950", tsSec = 2),
            )
        val outcome = AutopilotPaperShadowScorer.score(entry, tpPct, slPct, bars)
        assertEquals(ShadowOutcome.Open, outcome)
    }

    @Test
    fun `빈 캔들 시퀀스 — Open`() {
        assertEquals(ShadowOutcome.Open, AutopilotPaperShadowScorer.score(entry, tpPct, slPct, emptyList()))
    }

    @Test
    fun `진입가·TP·SL 0 이하 — Open (산정 불가 · 낙관 금지)`() {
        val bars = listOf(bar(high = "99999", low = "1", tsSec = 1))
        assertEquals(ShadowOutcome.Open, AutopilotPaperShadowScorer.score(BigDecimal.ZERO, tpPct, slPct, bars))
        assertEquals(ShadowOutcome.Open, AutopilotPaperShadowScorer.score(entry, BigDecimal.ZERO, slPct, bars))
        assertEquals(ShadowOutcome.Open, AutopilotPaperShadowScorer.score(entry, tpPct, BigDecimal.ZERO, bars))
    }

    @Test
    fun `realizedPnl — net 은 왕복비용 차감(보수적)`() {
        // 수량 100, entry 10,000, exit 10,200(Win). gross = 200 × 100 = 20,000.
        // cost = 10,000 × 100 × 0.3% = 3,000. net = 17,000.
        val pnl = AutopilotPaperShadowScorer.realizedPnl(entry, BigDecimal("10200"), 100)
        assertEquals(0, pnl.compareTo(BigDecimal("17000")), "net=$pnl")
    }

    @Test
    fun `realizedPnl — Loss 는 음수(net 손실)`() {
        // exit 9,850(SL). gross = -150 × 100 = -15,000. cost = 3,000. net = -18,000.
        val pnl = AutopilotPaperShadowScorer.realizedPnl(entry, BigDecimal("9850"), 100)
        assertTrue(pnl < BigDecimal.ZERO, "net=$pnl 은 손실이어야")
        assertEquals(0, pnl.compareTo(BigDecimal("-18000")), "net=$pnl")
    }

    @Test
    fun `realizedPnl — 수량 0 또는 진입가 0 은 0`() {
        assertEquals(0, AutopilotPaperShadowScorer.realizedPnl(entry, BigDecimal("10200"), 0).compareTo(BigDecimal.ZERO))
        assertEquals(0, AutopilotPaperShadowScorer.realizedPnl(BigDecimal.ZERO, BigDecimal("10200"), 100).compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `결정론 — 동일 입력 동일 출력`() {
        val bars = listOf(bar(high = "10250", low = "9950", tsSec = 1))
        val a = AutopilotPaperShadowScorer.score(entry, tpPct, slPct, bars)
        val b = AutopilotPaperShadowScorer.score(entry, tpPct, slPct, bars)
        assertEquals(a, b)
    }
}
