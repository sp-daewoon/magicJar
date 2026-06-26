package kr.spdaewoon.magicjar.api.strategy

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kr.spdaewoon.magicjar.api.cache.RedisStrategyCache
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import kr.spdaewoon.magicjar.strategy.TradePlanFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

/**
 * ADR 0135 — trailing stop 영속 경로(StrategyService) 테스트.
 *
 * 평가·박제 로직은 변경 0이므로(TradePlanEvaluator·TradePlanFactory 기존 그대로) 본 테스트는
 * **영속 경로가 trailing 을 도메인까지 운반**하는지 + **박제(TradePlanFactory)가 TradePlanTrailing 으로
 * 박제**하는지 + **null 일 때 zero-regression(미박제)** 인지를 검증한다.
 */
class StrategyServiceTrailingStopTest {
    private val strategyRepositoryPort = mockk<StrategyRepositoryPort>(relaxed = true)
    private val strategyJpaRepository = mockk<StrategyJpaRepository>(relaxed = true)
    private val backtestRunJpaRepository = mockk<BacktestRunJpaRepository>(relaxed = true)
    private val redisStrategyCache = mockk<RedisStrategyCache>(relaxed = true)

    private fun service() =
        StrategyService(
            strategyRepositoryPort = strategyRepositoryPort,
            strategyJpaRepository = strategyJpaRepository,
            backtestRunJpaRepository = backtestRunJpaRepository,
            redisStrategyCache = redisStrategyCache,
            astDepthLimit = 32,
            nodeCountLimit = 10_000,
            indicatorCallLimit = 50,
            floorDefaultPct = BigDecimal("15"),
        )

    private fun entryAst() = Condition(left = IndicatorRef("close"), operator = Operator.GT, right = LiteralValue(BigDecimal.ZERO))

    private fun create(trailingStopPct: BigDecimal?): Strategy {
        val saved = slot<Strategy>()
        every { strategyRepositoryPort.save(capture(saved)) } answers { saved.captured }
        service().create(
            userId = 1L,
            name = "scalp_oversold_bounce",
            nameKo = "단타 트레일링",
            entryAst = entryAst(),
            exitAst = null,
            universe = Universe("KOSPI"),
            tf = "1m",
            entryText = "close > 0",
            exitText = null,
            codes = listOf("005930"),
            intervals = listOf("1m"),
            stopLossPct = BigDecimal("2"),
            takeProfitPct = BigDecimal("3"),
            trailingStopPct = trailingStopPct,
            requireCodes = true,
        )
        return saved.captured
    }

    @Test
    fun `trailing 저장 시 도메인 trailingStop 으로 운반되고 TradePlanFactory 가 TradePlanTrailing 박제한다`() {
        val saved = create(trailingStopPct = BigDecimal("1.2"))

        // 도메인 운반 확인
        assertThat(saved.definition.trailingStop).isEqualByComparingTo("1.2")

        // 박제 확인 — TradePlanFactory 가 trailing 을 TradePlanTrailing(enabled=true, trailPct) 로 박제
        val plan =
            TradePlanFactory.build(
                strategyId = 1L,
                definition = saved.definition,
                entryPrice = BigDecimal("10000"),
            )
        assertThat(plan.trailing).isNotNull
        assertThat(plan.trailing!!.enabled).isTrue()
        assertThat(plan.trailing!!.trailPct).isEqualByComparingTo("1.2")
    }

    @Test
    fun `trailing null 전략은 박제되지 않는다(zero-regression)`() {
        val saved = create(trailingStopPct = null)

        assertThat(saved.definition.trailingStop).isNull()

        val plan =
            TradePlanFactory.build(
                strategyId = 1L,
                definition = saved.definition,
                entryPrice = BigDecimal("10000"),
            )
        assertThat(plan.trailing).isNull()
    }

    @Test
    fun `trailing 0 이하는 400으로 거부한다`() {
        assertThatThrownBy { create(trailingStopPct = BigDecimal.ZERO) }
            .isInstanceOf(ResponseStatusException::class.java)
        assertThatThrownBy { create(trailingStopPct = BigDecimal("-1")) }
            .isInstanceOf(ResponseStatusException::class.java)
    }
}
