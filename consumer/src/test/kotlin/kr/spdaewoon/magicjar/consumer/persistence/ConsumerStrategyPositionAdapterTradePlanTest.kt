package kr.spdaewoon.magicjar.consumer.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.strategy.TradePlan
import kr.spdaewoon.magicjar.domain.strategy.TradePlanContingency
import kr.spdaewoon.magicjar.domain.strategy.TradePlanEntry
import kr.spdaewoon.magicjar.domain.strategy.TradePlanHold
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStatus
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStop
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStopType
import kr.spdaewoon.magicjar.domain.strategy.TradePlanTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [ConsumerStrategyPositionAdapter] trade_plan 엔티티 매핑 — ADR 0130 L2 R1 (drift LOCK 검증).
 *
 * domain [TradePlan] ↔ JSONB String([StrategyPositionJpaEntity.tradePlan]) 매핑이 write→read 보존되는지 검증한다.
 * (ADR 0128 scale_in_config 매핑 누락 → 라이브 미작동 재발 방지 — 매핑 누락 0 LOCK.)
 */
class ConsumerStrategyPositionAdapterTradePlanTest {
    private val repository = mockk<StrategyPositionJpaRepository>()
    private val objectMapper = ObjectMapper()
    private val adapter = ConsumerStrategyPositionAdapter(repository, objectMapper)

    private fun position(plan: TradePlan?) =
        StrategyPosition(
            userId = "1234",
            strategyId = 105,
            code = "005930",
            quantity = 10,
            avgPrice = BigDecimal("12340"),
            tradePlan = plan,
            openedAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )

    private fun fullPlan() =
        TradePlan(
            entry =
                TradePlanEntry(
                    strategyId = 105,
                    thesis = "vwap reclaim",
                    entryPrice = BigDecimal("12340"),
                    bar = "1m",
                    openedAt = "2026-06-24T01:12:00Z",
                ),
            target = TradePlanTarget(tpPct = BigDecimal("3.0"), tpPrice = BigDecimal("12710")),
            stop = TradePlanStop(type = TradePlanStopType.FIXED, slPct = BigDecimal("2.0"), slPrice = BigDecimal("12093")),
            hold = TradePlanHold(minHoldBars = 3, maxHoldBars = 30),
            contingency = TradePlanContingency(viAction = "EXIT_MARKET", eod = "LIQUIDATE"),
            status = TradePlanStatus.OPEN,
        )

    @Test
    fun `trade_plan write to read 매핑 보존 — toEntity 직렬화 후 toDomain 역직렬화 동치`() {
        every { repository.findByUserIdAndStrategyIdAndCode(any(), any(), any()) } returns null
        val saved = slot<StrategyPositionJpaEntity>()
        // save 가 받은 엔티티(toEntity 결과)를 그대로 반환 → upsert 의 toDomain 이 같은 trade_plan String 을 역직렬화.
        every { repository.save(capture(saved)) } answers { saved.captured }

        val plan = fullPlan()
        val result = adapter.upsert(position(plan))

        // 엔티티에 trade_plan JSONB String 이 채워졌는가(매핑 누락 0).
        assertThat(saved.captured.tradePlan).isNotNull()
        // write→read 동치(round-trip 보존).
        assertThat(result.tradePlan).isEqualTo(plan)
    }

    @Test
    fun `tradePlan null 이면 컬럼 NULL — 미기록(USER·게이트 off 현행 동작)`() {
        every { repository.findByUserIdAndStrategyIdAndCode(any(), any(), any()) } returns null
        val saved = slot<StrategyPositionJpaEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        val result = adapter.upsert(position(null))

        assertThat(saved.captured.tradePlan).isNull()
        assertThat(result.tradePlan).isNull()
    }
}
