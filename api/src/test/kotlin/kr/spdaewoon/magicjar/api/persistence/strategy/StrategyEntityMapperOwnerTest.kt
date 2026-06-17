package kr.spdaewoon.magicjar.api.persistence.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.strategy.StrategyOwner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Strategy owner 매핑 왕복 검증 — V88 (spec 2026-06-18 §5.1).
 *
 * - entity.owner(String) → domain.owner(enum) 변환(safeOwner) 정확.
 * - SYSTEM 시드 행은 userId null로 매핑됨(nullable 전환 무손실).
 * - invalid owner 값은 USER로 흡수(safe* 패턴 — 평가 누락 risk 회피).
 */
class StrategyEntityMapperOwnerTest {
    private val objectMapper = ObjectMapper()

    private fun entity(
        owner: String,
        userId: Long?,
    ) = StrategyJpaEntity(
        id = 1L,
        userId = userId,
        owner = owner,
        name = "fvg-bullish-reclaim",
        nameKo = "FVG 매수 후보",
        dslEntry = "close > ma(20)",
        dslExit = null,
    )

    @Test
    fun `SYSTEM owner 행은 owner=SYSTEM 과 userId null 로 매핑된다`() {
        val domain = StrategyEntityMapper.toDomain(entity("SYSTEM", null), objectMapper)
        assertEquals(StrategyOwner.SYSTEM, domain.owner)
        assertNull(domain.userId)
    }

    @Test
    fun `USER owner 행은 owner=USER 와 userId 보존된다`() {
        val domain = StrategyEntityMapper.toDomain(entity("USER", 7L), objectMapper)
        assertEquals(StrategyOwner.USER, domain.owner)
        assertEquals(7L, domain.userId)
    }

    @Test
    fun `invalid·null owner 는 USER 로 흡수된다 (평가 누락 risk 회피)`() {
        assertEquals(StrategyOwner.USER, StrategyEntityMapper.safeOwner("UNKNOWN"))
        assertEquals(StrategyOwner.USER, StrategyEntityMapper.safeOwner(null))
        assertEquals(StrategyOwner.SYSTEM, StrategyEntityMapper.safeOwner("SYSTEM"))
    }
}
