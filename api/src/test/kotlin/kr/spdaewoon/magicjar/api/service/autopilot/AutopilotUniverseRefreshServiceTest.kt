package kr.spdaewoon.magicjar.api.service.autopilot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.api.persistence.screener.DiscoveryScorecardJpaEntity
import kr.spdaewoon.magicjar.api.persistence.screener.DiscoveryScorecardJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [AutopilotUniverseRefreshService] 단위 테스트 — ADR 0131.
 *
 * 검증: dedup(code별 max score)·정렬·TOP N · owner 격리(UPDATE 호출 codes) · fail-safe 3종 · 보유 lot 미청산(codes만).
 */
class AutopilotUniverseRefreshServiceTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val today = LocalDate.of(2026, 6, 24)

    private fun row(
        code: String,
        score: Double,
        preset: String = "MOMENTUM_BREAKOUT",
        rank: Int = 1,
    ) = DiscoveryScorecardJpaEntity(
        snapshotDate = today,
        presetKey = preset,
        rank = rank,
        instrumentCode = code,
        instrumentName = code,
        score = BigDecimal.valueOf(score),
    )

    private fun service(
        scorecard: DiscoveryScorecardJpaRepository,
        strategy: StrategyJpaRepository,
        position: StrategyPositionReadJpaRepository,
        enabled: Boolean = true,
        size: Int = 16,
        eventLogger: SystemEventLogger = mockk(relaxed = true),
    ): AutopilotUniverseRefreshService {
        val provider = mockk<ObjectProvider<SystemEventLogger>>()
        every { provider.getIfAvailable() } returns eventLogger
        return AutopilotUniverseRefreshService(
            discoveryScorecardJpaRepository = scorecard,
            strategyJpaRepository = strategy,
            strategyPositionReadJpaRepository = position,
            objectMapper = objectMapper,
            systemEventLoggerProvider = provider,
            enabled = enabled,
            sizeRaw = size,
        )
    }

    @Test
    fun `게이트 off 면 DISABLED no-op — codes UPDATE 0`() {
        val scorecard = mockk<DiscoveryScorecardJpaRepository>(relaxed = true)
        val strategy = mockk<StrategyJpaRepository>(relaxed = true)
        val position = mockk<StrategyPositionReadJpaRepository>(relaxed = true)

        val result = service(scorecard, strategy, position, enabled = false).refresh(today)

        assertThat(result.status).isEqualTo(AutopilotUniverseRefreshService.RefreshStatus.DISABLED)
        verify(exactly = 0) { strategy.updateAutopilotUniverse(any()) }
        verify(exactly = 0) { scorecard.findRowsInRange(any(), any(), any()) }
    }

    @Test
    fun `snapshot 0건 → NO_SNAPSHOT skip — codes UPDATE 0(fail-safe)`() {
        val scorecard = mockk<DiscoveryScorecardJpaRepository>()
        every { scorecard.findRowsInRange(today, today, null) } returns emptyList()
        val strategy = mockk<StrategyJpaRepository>(relaxed = true)
        val position = mockk<StrategyPositionReadJpaRepository>(relaxed = true)

        val result = service(scorecard, strategy, position).refresh(today)

        assertThat(result.status).isEqualTo(AutopilotUniverseRefreshService.RefreshStatus.NO_SNAPSHOT)
        verify(exactly = 0) { strategy.updateAutopilotUniverse(any()) }
    }

    @Test
    fun `dedup(code별 max score) + 정렬(score DESC, code ASC) + TOP N`() {
        // 005930 가 두 프리셋에 등장(80·92) → max 92. 정렬: 000660(95) > 005930(92) > 035720(90).
        val rows =
            listOf(
                row("005930", 80.0, preset = "MOMENTUM_BREAKOUT"),
                row("000660", 95.0, preset = "VOLUME_BREAKOUT"),
                row("005930", 92.0, preset = "GAP_AND_GO"),
                row("035720", 90.0, preset = "RANGE_BREAKOUT"),
            )
        val scorecard = mockk<DiscoveryScorecardJpaRepository>()
        every { scorecard.findRowsInRange(today, today, null) } returns rows
        val strategy = mockk<StrategyJpaRepository>()
        every { strategy.findAutopilotUniverseCodesRaw() } returns emptyList()
        val codesSlot = slot<String>()
        every { strategy.updateAutopilotUniverse(capture(codesSlot)) } returns 2
        val position = mockk<StrategyPositionReadJpaRepository>()
        every { position.findAutopilotHeldCodes() } returns emptyList()

        val result = service(scorecard, strategy, position, size = 2).refresh(today)

        assertThat(result.status).isEqualTo(AutopilotUniverseRefreshService.RefreshStatus.UPDATED)
        assertThat(result.universeSize).isEqualTo(2)
        assertThat(result.strategiesUpdated).isEqualTo(2)
        // TOP 2: 000660(95) > 005930(92) — 035720(90) 탈락. dedup 으로 005930 1회만.
        val written = objectMapper.readValue(codesSlot.captured, Array<String>::class.java).toList()
        assertThat(written).containsExactly("000660", "005930")
    }

    @Test
    fun `동점 score 면 instrument_code ASC tie-break(결정성)`() {
        val rows = listOf(row("005930", 90.0), row("000660", 90.0), row("035720", 90.0))
        val scorecard = mockk<DiscoveryScorecardJpaRepository>()
        every { scorecard.findRowsInRange(today, today, null) } returns rows
        val strategy = mockk<StrategyJpaRepository>()
        every { strategy.findAutopilotUniverseCodesRaw() } returns emptyList()
        val codesSlot = slot<String>()
        every { strategy.updateAutopilotUniverse(capture(codesSlot)) } returns 1
        val position = mockk<StrategyPositionReadJpaRepository>()
        every { position.findAutopilotHeldCodes() } returns emptyList()

        service(scorecard, strategy, position).refresh(today)

        val written = objectMapper.readValue(codesSlot.captured, Array<String>::class.java).toList()
        assertThat(written).containsExactly("000660", "005930", "035720")
    }

    @Test
    fun `owner=AUTOPILOT 만 UPDATE — USER_SYSTEM 미변경(updateAutopilotUniverse 단일 경로)`() {
        // 격리는 native UPDATE WHERE owner='AUTOPILOT' 가 보장 — 서비스가 그 경로만 호출하고 다른 write 0임을 단언.
        val rows = listOf(row("005930", 90.0))
        val scorecard = mockk<DiscoveryScorecardJpaRepository>()
        every { scorecard.findRowsInRange(today, today, null) } returns rows
        val strategy = mockk<StrategyJpaRepository>(relaxed = true)
        every { strategy.findAutopilotUniverseCodesRaw() } returns emptyList()
        every { strategy.updateAutopilotUniverse(any()) } returns 6
        val position = mockk<StrategyPositionReadJpaRepository>()
        every { position.findAutopilotHeldCodes() } returns emptyList()

        service(scorecard, strategy, position).refresh(today)

        // codes write 는 autopilot 격리 쿼리 1회뿐 — save/saveAll 등 비격리 write 0(zero-regression).
        verify(exactly = 1) { strategy.updateAutopilotUniverse(any()) }
        verify(exactly = 0) { strategy.save(any()) }
        verify(exactly = 0) { strategy.saveAll(any<Iterable<kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity>>()) }
    }

    @Test
    fun `보유 lot 미청산 — codes만 변경 + held-lots 집계(hold-until-exit)`() {
        // 직전 universe = [005930, 000660]. 신규 TOP N = [035720] → 005930·000660 dropped.
        // 005930 보유분 존재 → held-lots-remaining 에 잡히되 청산 호출(SELL) 0.
        val rows = listOf(row("035720", 99.0))
        val scorecard = mockk<DiscoveryScorecardJpaRepository>()
        every { scorecard.findRowsInRange(today, today, null) } returns rows
        val strategy = mockk<StrategyJpaRepository>()
        every { strategy.findAutopilotUniverseCodesRaw() } returns listOf("""["005930","000660"]""")
        val codesSlot = slot<String>()
        every { strategy.updateAutopilotUniverse(capture(codesSlot)) } returns 1
        val position = mockk<StrategyPositionReadJpaRepository>()
        every { position.findAutopilotHeldCodes() } returns listOf("005930")

        val result = service(scorecard, strategy, position).refresh(today)

        assertThat(result.status).isEqualTo(AutopilotUniverseRefreshService.RefreshStatus.UPDATED)
        // codes 는 신규 universe 만 — dropped 종목이 codes 에서 빠짐(추가 매수만 중단).
        val written = objectMapper.readValue(codesSlot.captured, Array<String>::class.java).toList()
        assertThat(written).containsExactly("035720")
        assertThat(result.droppedCodes).containsExactlyInAnyOrder("005930", "000660")
        // 005930 만 보유분 잔존 → held-lots. 000660 은 보유 0 이라 미포함. 강제 청산은 codes 변경에 동반되지 않음.
        assertThat(result.heldLotsRemaining).containsExactly("005930")
        // 서비스는 청산(liquidator) 의존이 아예 없다 — 강제 청산 경로 부재가 hold-until-exit 의 구조적 보장.
    }

    @Test
    fun `size 상한 40 coerce — 41 입력해도 40 으로 take`() {
        val rows = (1..50).map { row(code = "%06d".format(it), score = (100 - it).toDouble()) }
        val scorecard = mockk<DiscoveryScorecardJpaRepository>()
        every { scorecard.findRowsInRange(today, today, null) } returns rows
        val strategy = mockk<StrategyJpaRepository>()
        every { strategy.findAutopilotUniverseCodesRaw() } returns emptyList()
        val codesSlot = slot<String>()
        every { strategy.updateAutopilotUniverse(capture(codesSlot)) } returns 1
        val position = mockk<StrategyPositionReadJpaRepository>()
        every { position.findAutopilotHeldCodes() } returns emptyList()

        val result = service(scorecard, strategy, position, size = 41).refresh(today)

        assertThat(result.universeSize).isEqualTo(40)
        val written = objectMapper.readValue(codesSlot.captured, Array<String>::class.java).toList()
        assertThat(written).hasSize(40)
    }

    // ── ADR 0133 §3-B3 검증 게이트(#5) append ───────────────────────────────

    private fun serviceWithGate(
        gate: AutopilotActivationGateService?,
        strategy: StrategyJpaRepository = mockk(relaxed = true),
    ): AutopilotUniverseRefreshService {
        val provider = mockk<ObjectProvider<SystemEventLogger>>()
        every { provider.getIfAvailable() } returns mockk<SystemEventLogger>(relaxed = true)
        return AutopilotUniverseRefreshService(
            discoveryScorecardJpaRepository = mockk(relaxed = true),
            strategyJpaRepository = strategy,
            strategyPositionReadJpaRepository = mockk(relaxed = true),
            objectMapper = objectMapper,
            systemEventLoggerProvider = provider,
            enabled = true,
            sizeRaw = 16,
            activationGateService = gate,
        )
    }

    @Test
    fun `validateActivations — 게이트 미배선(null) 이면 DISABLED no-op`() {
        val strategy = mockk<StrategyJpaRepository>(relaxed = true)
        val result = serviceWithGate(gate = null, strategy = strategy).validateActivations(today)

        assertThat(result.status).isEqualTo(AutopilotUniverseRefreshService.GateStatus.DISABLED)
        verify(exactly = 0) { strategy.findAutopilotEvaluableStrategies() }
    }

    @Test
    fun `validateActivations — 게이트 OFF(enabled false) 면 DISABLED no-op`() {
        val gate = mockk<AutopilotActivationGateService>()
        every { gate.isEnabled() } returns false
        val strategy = mockk<StrategyJpaRepository>(relaxed = true)

        val result = serviceWithGate(gate = gate, strategy = strategy).validateActivations(today)

        assertThat(result.status).isEqualTo(AutopilotUniverseRefreshService.GateStatus.DISABLED)
        verify(exactly = 0) { strategy.findAutopilotEvaluableStrategies() }
    }

    @Test
    fun `validateActivations — 전략 0건 이면 NO_STRATEGIES`() {
        val gate = mockk<AutopilotActivationGateService>()
        every { gate.isEnabled() } returns true
        val strategy = mockk<StrategyJpaRepository>()
        every { strategy.findAutopilotEvaluableStrategies() } returns emptyList()

        val result = serviceWithGate(gate = gate, strategy = strategy).validateActivations(today)

        assertThat(result.status).isEqualTo(AutopilotUniverseRefreshService.GateStatus.NO_STRATEGIES)
    }

    @Test
    fun `validateActivations — PASS_FAIL 집계 + 전략별 evaluate 호출`() {
        val gate = mockk<AutopilotActivationGateService>()
        every { gate.isEnabled() } returns true
        val s1 = autopilotEntity(1L)
        val s2 = autopilotEntity(2L)
        val strategy = mockk<StrategyJpaRepository>()
        every { strategy.findAutopilotEvaluableStrategies() } returns listOf(s1, s2)
        every { gate.evaluateStrategy(s1, today) } returns
            kr.spdaewoon.magicjar.domain.backtest.BacktestPassResult(
                kr.spdaewoon.magicjar.domain.backtest.AutopilotActivationVerdict.PASS,
                emptyList(),
                null,
            )
        every { gate.evaluateStrategy(s2, today) } returns
            kr.spdaewoon.magicjar.domain.backtest.BacktestPassResult
                .error()

        val result = serviceWithGate(gate = gate, strategy = strategy).validateActivations(today)

        assertThat(result.status).isEqualTo(AutopilotUniverseRefreshService.GateStatus.EVALUATED)
        assertThat(result.evaluated).isEqualTo(2)
        assertThat(result.passed).isEqualTo(1)
        assertThat(result.failed).isEqualTo(1)
        verify(exactly = 1) { gate.evaluateStrategy(s1, today) }
        verify(exactly = 1) { gate.evaluateStrategy(s2, today) }
    }

    @Test
    fun `validateActivations — 한 전략 평가 예외가 다른 전략 검증을 막지 않음(fail-soft)`() {
        val gate = mockk<AutopilotActivationGateService>()
        every { gate.isEnabled() } returns true
        val s1 = autopilotEntity(1L)
        val s2 = autopilotEntity(2L)
        val strategy = mockk<StrategyJpaRepository>()
        every { strategy.findAutopilotEvaluableStrategies() } returns listOf(s1, s2)
        every { gate.evaluateStrategy(s1, today) } throws RuntimeException("boom")
        every { gate.evaluateStrategy(s2, today) } returns
            kr.spdaewoon.magicjar.domain.backtest.BacktestPassResult(
                kr.spdaewoon.magicjar.domain.backtest.AutopilotActivationVerdict.PASS,
                emptyList(),
                null,
            )

        val result = serviceWithGate(gate = gate, strategy = strategy).validateActivations(today)

        assertThat(result.evaluated).isEqualTo(2)
        assertThat(result.passed).isEqualTo(1) // s2 정상 평가됨.
        assertThat(result.failed).isEqualTo(1) // s1 예외 → 집계상 FAIL.
        verify(exactly = 1) { gate.evaluateStrategy(s2, today) }
    }

    private fun autopilotEntity(id: Long) =
        kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity(
            id = id,
            userId = 7L,
            owner = "AUTOPILOT",
            name = "autopilot-$id",
            nameKo = null,
            dslEntry = "RSI(14) < 30",
            dslExit = null,
            codes = """["005930"]""",
            status = "ACTIVE",
        )
}
