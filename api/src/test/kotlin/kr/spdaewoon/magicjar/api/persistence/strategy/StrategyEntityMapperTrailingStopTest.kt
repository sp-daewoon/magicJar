package kr.spdaewoon.magicjar.api.persistence.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * ADR 0135 — trailing stop 영속 매핑 왕복 검증 (api StrategyEntityMapper).
 *
 * - entity.trailingStopPct → definition.trailingStop 변환 정확(매핑 누락 drift 가드 — scale_in_config·allow_overnight 교훈).
 * - null 행은 trailingStop null 로 유지(zero-regression).
 */
class StrategyEntityMapperTrailingStopTest {
    private val objectMapper = ObjectMapper()

    private fun entity(trailingStopPct: BigDecimal?) =
        StrategyJpaEntity(
            id = 1L,
            userId = 7L,
            name = "scalp_oversold_bounce",
            nameKo = "단타 트레일링",
            dslEntry = "close > ma(20)",
            dslExit = null,
            trailingStopPct = trailingStopPct,
        )

    @Test
    fun `trailing_stop_pct 컬럼은 definition trailingStop 으로 매핑된다`() {
        val domain = StrategyEntityMapper.toDomain(entity(BigDecimal("1.2")), objectMapper)
        assertThat(domain.definition.trailingStop).isEqualByComparingTo(BigDecimal("1.2"))
    }

    @Test
    fun `trailing_stop_pct null 행은 trailingStop null 로 유지된다 (zero-regression)`() {
        val domain = StrategyEntityMapper.toDomain(entity(null), objectMapper)
        assertThat(domain.definition.trailingStop).isNull()
    }
}
