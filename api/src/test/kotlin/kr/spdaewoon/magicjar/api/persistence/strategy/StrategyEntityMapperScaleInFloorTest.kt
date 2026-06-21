package kr.spdaewoon.magicjar.api.persistence.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * scale_in_config JSONB 라운드트립 — catastrophic floor 필드(ADR 0128 amend 갭①·갭②).
 *
 * 쓰기(StrategyJpaAdapter.toEntity → objectMapper.writeValueAsString)는 ScaleInPlan 전 필드를 직렬화하지만,
 * 읽기([StrategyEntityMapper] deserializeScaleIn)는 raw map 수동 파싱이라 stopLossFloorPct·floorAutoLiquidate 를
 * 명시 복원하지 않으면 조회 시 소실된다(자본 경로 버그). 본 테스트가 왕복 무손실을 증명한다.
 */
class StrategyEntityMapperScaleInFloorTest {
    private val objectMapper = ObjectMapper()

    private fun entityWithScaleIn(plan: ScaleInPlan) =
        StrategyJpaEntity(
            id = 1L,
            userId = 7L,
            owner = "USER",
            name = "scale-in-floor",
            nameKo = "분할 floor",
            dslEntry = "close > ma(20)",
            dslExit = null,
            // 쓰기 경로와 동일한 직렬화(전 필드).
            scaleInConfig = objectMapper.writeValueAsString(plan),
        )

    @Test
    fun `명시 floor + autoLiquidate=true 가 라운드트립에서 보존된다`() {
        val plan =
            ScaleInPlan(
                totalSlices = 3,
                reentryTrigger = ReentryTrigger.PRICE_DROP_PCT,
                priceDropPct = BigDecimal("-3.0"),
                stopLossFloorPct = BigDecimal("20"),
                floorAutoLiquidate = true,
            )
        val domain = StrategyEntityMapper.toDomain(entityWithScaleIn(plan), objectMapper)
        val scaleIn = domain.definition.scaleIn
        assertNotNull(scaleIn)
        assertEquals(0, BigDecimal("20").compareTo(scaleIn!!.stopLossFloorPct))
        assertTrue(scaleIn.floorAutoLiquidate)
    }

    @Test
    fun `floor null + autoLiquidate 미설정은 null·false 로 복원된다 (3-상태 보존)`() {
        val plan =
            ScaleInPlan(
                totalSlices = 2,
                reentryTrigger = ReentryTrigger.SIGNAL_REPEAT,
            )
        val scaleIn = StrategyEntityMapper.toDomain(entityWithScaleIn(plan), objectMapper).definition.scaleIn
        assertNotNull(scaleIn)
        assertNull(scaleIn!!.stopLossFloorPct)
        assertFalse(scaleIn.floorAutoLiquidate)
    }

    @Test
    fun `floor=0(비활성) 명시값이 0 으로 보존된다 (null 과 구분)`() {
        val plan =
            ScaleInPlan(
                totalSlices = 2,
                reentryTrigger = ReentryTrigger.SIGNAL_REPEAT,
                stopLossFloorPct = BigDecimal.ZERO,
            )
        val scaleIn = StrategyEntityMapper.toDomain(entityWithScaleIn(plan), objectMapper).definition.scaleIn
        assertNotNull(scaleIn)
        assertNotNull(scaleIn!!.stopLossFloorPct)
        assertEquals(0, BigDecimal.ZERO.compareTo(scaleIn.stopLossFloorPct))
    }
}
