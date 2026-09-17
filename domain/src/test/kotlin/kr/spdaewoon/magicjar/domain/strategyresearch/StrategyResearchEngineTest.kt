package kr.spdaewoon.magicjar.domain.strategyresearch

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * StrategyResearchEngine 도메인 로직 테스트.
 *
 * research-candidates-db 전환 후 진입 시그널은 [CandidateEntryEvaluator] 포트로 위임된다.
 * 도메인 단독 테스트에서는 결정론적 stub evaluator를 주입해 엔진의 시뮬레이션·통계·exit profile 스윕을
 * DSL 평가기와 분리해 검증한다(DSL 실제 평가 정합은 api 모듈 통합 테스트가 담당).
 */
class StrategyResearchEngineTest {
    /**
     * VOLUME_WASHOUT_REBOUND 후보가 index 26(직전 투매 음봉 직후 양봉)에서 진입하도록 하는 stub.
     * 기존 Kotlin 람다의 핵심 의미(투매 후 양봉 회복)를 fixture 한정으로 재현한다.
     */
    private val washoutStub =
        CandidateEntryEvaluator { entryDsl, candles, index ->
            entryDsl.contains("close > open") &&
                index == 26 &&
                candles[index].close > candles[index].open
        }

    @Test
    fun `washout candidate uses next candle open and reports a take profit trade`() {
        val engine = StrategyResearchEngine(entryEvaluator = washoutStub)
        val report =
            engine.research(
                request = request(targetProfitPct = 1.0, stopLossPct = 0.8, maxHoldBars = 4),
                candles = washoutCandles(),
            )

        val candidate = report.candidates.first { it.key == "VOLUME_WASHOUT_REBOUND" }
        candidate.trades.shouldNotBeEmpty()
        val trade = candidate.trades.first()
        trade.signalTime shouldBe t("2026-04-28T00:26:00Z")
        trade.entryTime shouldBe t("2026-04-28T00:27:00Z")
        trade.exitReason shouldBe StrategyResearchExitReason.TAKE_PROFIT
        trade.returnPct.shouldBeGreaterThan(0.0)
        candidate.stats.totalReturnPct.shouldBeGreaterThan(0.0)
        candidate.riskRules.joinToString(" ") shouldContain "다음 candle 시가"
    }

    @Test
    fun `realistic trading cost is subtracted from trade return (no optimistic bias)`() {
        // 거래비용 0(gross) vs 실전 비용 default(net) returnPct 차이가 왕복 비용률과 일치함을 검증.
        val engine = StrategyResearchEngine(entryEvaluator = washoutStub)

        val zeroCost =
            request(targetProfitPct = 1.0, stopLossPct = 0.8, maxHoldBars = 4)
                .copy(commissionPctPerSide = 0.0, sellTaxPct = 0.0, slippagePctPerSide = 0.0)
        val realCost = request(targetProfitPct = 1.0, stopLossPct = 0.8, maxHoldBars = 4)

        val grossTrade =
            engine
                .research(zeroCost, washoutCandles())
                .candidates
                .first { it.key == "VOLUME_WASHOUT_REBOUND" }
                .trades
                .first()
        val netTrade =
            engine
                .research(realCost, washoutCandles())
                .candidates
                .first { it.key == "VOLUME_WASHOUT_REBOUND" }
                .trades
                .first()

        // net은 gross보다 왕복 비용률(commission 0.02046×2 + tax 0.18 + slippage 0.05×2 ≈ 0.32%p)만큼 낮다.
        val expectedCostPct = 0.02046 * 2 + 0.18 + 0.05 * 2
        val diff = grossTrade.returnPct - netTrade.returnPct
        // 각 returnPct가 소수 2자리 반올림되므로 반올림 오차(±0.01) 허용 범위로 검증.
        diff.shouldBeGreaterThan(expectedCostPct - 0.02)
        diff.shouldBeLessThan(expectedCostPct + 0.02)
        netTrade.returnPct.shouldBeLessThan(grossTrade.returnPct)
    }

    @Test
    fun `regular session filter suppresses entry signals outside 09-00 to 15-30 KST`() {
        // 진입 신호가 항상 index 26에서 나도록 하는 stub. 봉 ts를 시간외(KST 08시대)로 두면 진입이 억제된다.
        val alwaysAt26 = CandidateEntryEvaluator { _, _, index -> index == 26 }

        // 정규장 밖(UTC 22:30 = KST 07:30 시작) 봉 세트 — index 26은 KST 07:56 → 정규장 밖.
        val preMarketCandles = washoutCandlesStartingAt(t("2026-04-27T22:30:00Z"))
        val filtered =
            StrategyResearchEngine(entryEvaluator = alwaysAt26, regularSessionOnly = true)
                .research(request(maxHoldBars = 4), preMarketCandles)
                .candidates
                .first { it.key == "VOLUME_WASHOUT_REBOUND" }
        // 정규장 밖이라 진입 0.
        filtered.trades shouldBe emptyList()

        // 필터 off면 동일 봉에서 진입 발생(대조군) — 필터가 원인임을 입증.
        val unfiltered =
            StrategyResearchEngine(entryEvaluator = alwaysAt26, regularSessionOnly = false)
                .research(request(maxHoldBars = 4), preMarketCandles)
                .candidates
                .first { it.key == "VOLUME_WASHOUT_REBOUND" }
        unfiltered.trades.shouldNotBeEmpty()
    }

    @Test
    fun `insufficient candles returns warnings without candidates`() {
        val engine = StrategyResearchEngine(entryEvaluator = washoutStub)

        val report = engine.research(request(), washoutCandles().take(10))

        report.candidates shouldBe emptyList()
        report.warnings.joinToString(" ") shouldContain "최소"
    }

    @Test
    fun `research evaluates the full builtin candidate pool from the provider`() {
        val engine = StrategyResearchEngine(entryEvaluator = washoutStub)

        val report = engine.research(request(), washoutCandles(80))

        report.candidates.size shouldBe 18
        report.candidates.map { it.key }.toSet() shouldBe
            BuiltinResearchCandidates.DEFINITIONS.map { it.key }.toSet()
    }

    @Test
    fun `entryDsl is exposed as the authoritative draft on every candidate`() {
        val engine = StrategyResearchEngine(entryEvaluator = washoutStub)

        val report = engine.research(request(), washoutCandles(80))

        val byKey = BuiltinResearchCandidates.DEFINITIONS.associateBy { it.key }
        report.candidates.forEach { candidate ->
            candidate.entryDslDraft shouldBe byKey.getValue(candidate.key).entryDsl
        }
    }

    private fun request(
        targetProfitPct: Double = 1.5,
        stopLossPct: Double = 0.8,
        maxHoldBars: Int = 12,
    ): StrategyResearchRequest =
        StrategyResearchRequest(
            userId = "74683223",
            code = "038110",
            interval = "1m",
            from = t("2026-04-28T00:00:00Z"),
            to = t("2026-04-28T00:44:00Z"),
            targetProfitPct = targetProfitPct,
            stopLossPct = stopLossPct,
            maxHoldBars = maxHoldBars,
        )

    private fun washoutCandles(size: Int = 45): List<ResearchCandle> = washoutCandlesStartingAt(t("2026-04-28T00:00:00Z"), size)

    private fun washoutCandlesStartingAt(
        baseTs: Instant,
        size: Int = 45,
    ): List<ResearchCandle> {
        val candles = mutableListOf<ResearchCandle>()
        repeat(size) { index ->
            val ts = Instant.fromEpochSeconds(baseTs.epochSeconds + index * 60L)
            val open: String
            val high: String
            val low: String
            val close: String
            val volume: Long
            when (index) {
                25 -> {
                    open = "100.0"
                    high = "100.2"
                    low = "97.8"
                    close = "98.0"
                    volume = 3000
                }
                26 -> {
                    open = "98.0"
                    high = "99.3"
                    low = "97.9"
                    close = "99.0"
                    volume = 1800
                }
                27 -> {
                    open = "99.0"
                    high = "100.5"
                    low = "98.8"
                    close = "100.1"
                    volume = 1600
                }
                else -> {
                    open = "100.0"
                    high = "100.4"
                    low = "99.6"
                    close = "100.0"
                    volume = 1000
                }
            }
            candles += candle(ts, open, high, low, close, volume)
        }
        return candles
    }

    private fun candle(
        ts: Instant,
        open: String,
        high: String,
        low: String,
        close: String,
        volume: Long,
    ): ResearchCandle =
        ResearchCandle(
            code = "038110",
            interval = "1m",
            ts = ts,
            open = bd(open),
            high = bd(high),
            low = bd(low),
            close = bd(close),
            volume = volume,
        )

    private fun t(value: String): Instant = Instant.parse(value)

    private fun bd(value: String): BigDecimal = BigDecimal(value)
}
