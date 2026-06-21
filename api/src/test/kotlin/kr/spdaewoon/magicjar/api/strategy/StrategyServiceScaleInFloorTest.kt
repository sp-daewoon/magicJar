package kr.spdaewoon.magicjar.api.strategy

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.api.cache.RedisStrategyCache
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

/**
 * ADR 0128 amend 갭① — StrategyService 저장 시 catastrophic floor 명시화.
 *
 * - stopLossFloorPct == null → yml floor-default-pct(주입값 15)로 채워 persist (저장 후 항상 명시 floor).
 * - 사용자 명시 floor 는 그대로 존중(채우지 않음).
 * - 채운 floor 가 분할 최저 진입가보다 얕으면 validateFloorBelowDeepestEntry 로 400.
 */
class StrategyServiceScaleInFloorTest {
    private val strategyRepositoryPort = mockk<StrategyRepositoryPort>(relaxed = true)
    private val strategyJpaRepository = mockk<StrategyJpaRepository>(relaxed = true)
    private val backtestRunJpaRepository = mockk<BacktestRunJpaRepository>(relaxed = true)
    private val redisStrategyCache = mockk<RedisStrategyCache>(relaxed = true)

    private fun service(floorDefaultPct: BigDecimal = BigDecimal("15")) =
        StrategyService(
            strategyRepositoryPort = strategyRepositoryPort,
            strategyJpaRepository = strategyJpaRepository,
            backtestRunJpaRepository = backtestRunJpaRepository,
            redisStrategyCache = redisStrategyCache,
            astDepthLimit = 32,
            nodeCountLimit = 10_000,
            indicatorCallLimit = 50,
            floorDefaultPct = floorDefaultPct,
        )

    private fun entryAst() = Condition(left = IndicatorRef("close"), operator = Operator.GT, right = LiteralValue(BigDecimal.ZERO))

    private fun create(
        scaleIn: ScaleInPlan?,
        floorDefaultPct: BigDecimal = BigDecimal("15"),
    ): Strategy {
        val saved = slot<Strategy>()
        every { strategyRepositoryPort.save(capture(saved)) } answers { saved.captured }
        service(floorDefaultPct).create(
            userId = 1L,
            name = "scalein_floor",
            nameKo = "분할 floor",
            entryAst = entryAst(),
            exitAst = null,
            universe = Universe("KOSPI"),
            tf = "D1",
            entryText = "close > 0",
            exitText = null,
            codes = listOf("005930"),
            intervals = listOf("1d"),
            stopLossPct = BigDecimal("5"),
            takeProfitPct = BigDecimal("10"),
            scaleIn = scaleIn,
            requireCodes = true,
        )
        return saved.captured
    }

    @Test
    fun `floor null 전략 저장 시 yml default(15)로 채워 persist 한다`() {
        val saved = create(ScaleInPlan(totalSlices = 2, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT))
        val floor = saved.definition.scaleIn?.stopLossFloorPct
        assertThat(floor).isNotNull()
        assertThat(floor).isEqualByComparingTo("15")
        verify(exactly = 1) { strategyRepositoryPort.save(any()) }
    }

    @Test
    fun `yml 튜닝값(12)이 저장 floor 에 반영된다`() {
        val saved =
            create(
                ScaleInPlan(totalSlices = 2, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT),
                floorDefaultPct = BigDecimal("12"),
            )
        assertThat(saved.definition.scaleIn?.stopLossFloorPct).isEqualByComparingTo("12")
    }

    @Test
    fun `사용자 명시 floor 는 default 로 덮어쓰지 않고 그대로 존중한다`() {
        val saved =
            create(
                ScaleInPlan(
                    totalSlices = 2,
                    reentryTrigger = ReentryTrigger.SIGNAL_REPEAT,
                    stopLossFloorPct = BigDecimal("25"),
                ),
            )
        assertThat(saved.definition.scaleIn?.stopLossFloorPct).isEqualByComparingTo("25")
    }

    @Test
    fun `채운 floor 가 분할 최저 진입가보다 얕으면 400 으로 거부한다`() {
        // PRICE_DROP_PCT -10% × 4분할 → 최저 진입가 누적 하락 ~27% > floor 15% (얕음) → 위반.
        assertThatThrownBy {
            create(
                ScaleInPlan(
                    totalSlices = 4,
                    reentryTrigger = ReentryTrigger.PRICE_DROP_PCT,
                    priceDropPct = BigDecimal("-10"),
                    // floor 미지정 → default 15 로 채워진 뒤 검증 → 얕아서 위반.
                ),
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("floor")
    }
}
