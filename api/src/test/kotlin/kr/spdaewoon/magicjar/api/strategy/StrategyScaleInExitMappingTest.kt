package kr.spdaewoon.magicjar.api.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyEntityMapper
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * scale-in/partial-exit JSONB(V65) ↔ 도메인 변환 round-trip 검증 (분할매수 R3-a · ADR 0111).
 *
 * - StrategyEntityMapper.toDomain 이 scale_in_config·partial_exits JSONB String → 도메인 모델로 역직렬화
 * - 도메인 모델 직렬화(ScaleInPlan·PartialExit 평탄 데이터 클래스) → 같은 JSON 스키마(KDoc 예시 정합)
 * - 하위호환: 두 컬럼 null이면 scaleIn/partialExits null(현행 단일 진입 + 전량 익절)
 * - invalid JSON·검증 위반 row는 null 흡수(조회 전체 down 방지 — safe* 패턴)
 */
class StrategyScaleInExitMappingTest {
    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }

    private fun entity(
        scaleInConfig: String? = null,
        partialExits: String? = null,
    ) = StrategyJpaEntity(
        id = 1L,
        userId = 1L,
        name = "s",
        nameKo = null,
        dslEntry = "ma(5) crosses_above ma(20)",
        dslExit = null,
        scaleInConfig = scaleInConfig,
        partialExits = partialExits,
    )

    @Test
    fun `scale_in_config JSONB를 ScaleInPlan으로 역직렬화한다`() {
        val json =
            """{"totalSlices":3,"sliceWeights":[50,30,20],""" +
                """"reentryTrigger":"PRICE_DROP_PCT","priceDropPct":-3.0,"minSliceIntervalSec":120}"""
        val strategy = StrategyEntityMapper.toDomain(entity(scaleInConfig = json), objectMapper)

        val scaleIn = strategy.definition.scaleIn
        assertThat(scaleIn).isNotNull
        assertThat(scaleIn!!.totalSlices).isEqualTo(3)
        assertThat(scaleIn.sliceWeights).containsExactly(BigDecimal("50"), BigDecimal("30"), BigDecimal("20"))
        assertThat(scaleIn.reentryTrigger).isEqualTo(ReentryTrigger.PRICE_DROP_PCT)
        assertThat(scaleIn.priceDropPct).isEqualByComparingTo("-3.0")
        assertThat(scaleIn.minSliceIntervalSec).isEqualTo(120)
    }

    @Test
    fun `균등 분할(weights 생략) scale_in_config도 역직렬화한다`() {
        val json = """{"totalSlices":3,"reentryTrigger":"SIGNAL_REPEAT","minSliceIntervalSec":60}"""
        val scaleIn = StrategyEntityMapper.toDomain(entity(scaleInConfig = json), objectMapper).definition.scaleIn

        assertThat(scaleIn).isNotNull
        assertThat(scaleIn!!.totalSlices).isEqualTo(3)
        assertThat(scaleIn.sliceWeights).isNull()
        assertThat(scaleIn.reentryTrigger).isEqualTo(ReentryTrigger.SIGNAL_REPEAT)
        // 균등 분배 해석: 100/3 = 33.33 × 2 + 마지막 흡수
        assertThat(scaleIn.resolvedWeights().sumOf { it }).isEqualByComparingTo("100")
    }

    @Test
    fun `partial_exits JSONB를 PartialExit 리스트로 역직렬화한다`() {
        val json = """[{"triggerProfitPct":3.0,"exitWeightPct":50},{"triggerProfitPct":7.0,"exitWeightPct":30}]"""
        val exits = StrategyEntityMapper.toDomain(entity(partialExits = json), objectMapper).definition.partialExits

        assertThat(exits).hasSize(2)
        assertThat(exits!![0].triggerProfitPct).isEqualByComparingTo("3.0")
        assertThat(exits[0].exitWeightPct).isEqualByComparingTo("50")
        assertThat(exits[1].triggerProfitPct).isEqualByComparingTo("7.0")
    }

    @Test
    fun `두 컬럼 null이면 현행 단일 진입 + 전량 익절 (하위호환 LOCK)`() {
        val strategy = StrategyEntityMapper.toDomain(entity(), objectMapper)
        assertThat(strategy.definition.scaleIn).isNull()
        assertThat(strategy.definition.partialExits).isNull()
    }

    @Test
    fun `invalid scale_in_config(검증 위반)는 null 흡수해 조회를 down 시키지 않는다`() {
        // totalSlices=99 → 도메인 init cap(1..10) 위반 → null 흡수
        val json = """{"totalSlices":99,"reentryTrigger":"SIGNAL_REPEAT"}"""
        val scaleIn = StrategyEntityMapper.toDomain(entity(scaleInConfig = json), objectMapper).definition.scaleIn
        assertThat(scaleIn).isNull()
    }

    @Test
    fun `도메인 직렬화 결과가 KDoc JSON 스키마와 정합한다 (영속 wiring 계약)`() {
        val plan =
            ScaleInPlan(
                totalSlices = 3,
                sliceWeights = listOf(BigDecimal("50"), BigDecimal("30"), BigDecimal("20")),
                reentryTrigger = ReentryTrigger.PRICE_DROP_PCT,
                priceDropPct = BigDecimal("-3.0"),
                minSliceIntervalSec = 120,
            )
        val json = objectMapper.writeValueAsString(plan)
        // 직렬화 → 역직렬화 round-trip 동일성 (어댑터 save→load 계약).
        val restored = StrategyEntityMapper.toDomain(entity(scaleInConfig = json), objectMapper).definition.scaleIn
        assertThat(restored).isEqualTo(plan)

        val exits = listOf(PartialExit(BigDecimal("3.0"), BigDecimal("50")), PartialExit(BigDecimal("7.0"), BigDecimal("30")))
        val exitsJson = objectMapper.writeValueAsString(exits)
        val restoredExits = StrategyEntityMapper.toDomain(entity(partialExits = exitsJson), objectMapper).definition.partialExits
        assertThat(restoredExits).isEqualTo(exits)
    }
}
