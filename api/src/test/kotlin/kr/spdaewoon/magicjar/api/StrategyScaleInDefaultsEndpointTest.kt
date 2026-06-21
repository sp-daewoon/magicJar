package kr.spdaewoon.magicjar.api

import kr.spdaewoon.magicjar.api.controller.StrategiesController
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.api.service.market.LivePriceCacheReader
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.math.BigDecimal

/**
 * GET /api/strategies/scale-in-defaults — 분할매수 운영 default 조회 (ADR 0128 amend 갭①).
 *
 * LOCKED CONTRACT: frontend 빌더가 floor 필드 prefill 에 이 경로·필드명(floorDefaultPct)을 쓴다.
 * yml `magicjar.strategy.scale-in.floor-default-pct` 값을 number 로 그대로 반환한다.
 */
class StrategyScaleInDefaultsEndpointTest {
    private fun controller(floorDefaultPct: BigDecimal) =
        StrategiesController(
            strategyService = mock<StrategyService>(),
            kisYamlDslParser = mock<KisYamlDslParser>(),
            instrumentJpaRepository = mock<InstrumentJpaRepository>(),
            strategyPositionReadJpaRepository = mock<StrategyPositionReadJpaRepository>(),
            livePriceCacheReader = mock<LivePriceCacheReader>(),
            strategyStartService = mock<kr.spdaewoon.magicjar.api.service.strategy.StrategyStartService>(),
            favoritePort = mock<kr.spdaewoon.magicjar.domain.strategy.port.StrategyFavoritePort>(),
            floorDefaultPct = floorDefaultPct,
        )

    @Test
    fun `yml default(15)를 floorDefaultPct 로 반환한다`() {
        val res = controller(BigDecimal("15")).getScaleInDefaults()
        assertThat(res.floorDefaultPct).isEqualByComparingTo("15")
    }

    @Test
    fun `yml 튜닝값(예 12)이 그대로 반영된다`() {
        val res = controller(BigDecimal("12")).getScaleInDefaults()
        assertThat(res.floorDefaultPct).isEqualByComparingTo("12")
    }
}
