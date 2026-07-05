package kr.spdaewoon.magicjar.domain.strategy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * V98 전략 아카이브 — ARCHIVED enum 신규 값 계약 고정 테스트.
 *
 * - ARCHIVED는 평가 path 완전 제외(isEvaluable=false)·신규 BUY 불가(allowsBuy=false)여야 한다.
 * - name↔valueOf 직렬화 왕복이 안정적이어야 한다(DB VARCHAR ↔ enum · safeStatus 폴백 정합).
 * - 실시간 lookup(findActiveByCodeAndInterval)이 `status IN ('ACTIVE','BUY_BLOCKED')` 로 ARCHIVED를
 *   자연 제외함을 isEvaluable 집합으로 고정한다(native 쿼리 IN-list 와 도메인 판정의 단일 진실원 일치).
 */
class StrategyStatusArchivedTest {
    @Test
    fun `ARCHIVED는 평가 대상이 아니다`() {
        assertFalse(StrategyStatus.ARCHIVED.isEvaluable())
        assertFalse(StrategyStatus.ARCHIVED.allowsBuy())
    }

    @Test
    fun `평가 대상 상태는 ACTIVE와 BUY_BLOCKED 뿐이며 ARCHIVED는 제외된다`() {
        val evaluable = StrategyStatus.entries.filter { it.isEvaluable() }.toSet()
        assertEquals(setOf(StrategyStatus.ACTIVE, StrategyStatus.BUY_BLOCKED), evaluable)
        assertFalse(evaluable.contains(StrategyStatus.ARCHIVED))
    }

    @Test
    fun `ARCHIVED name valueOf 직렬화 왕복이 안정적이다`() {
        assertEquals("ARCHIVED", StrategyStatus.ARCHIVED.name)
        assertEquals(StrategyStatus.ARCHIVED, StrategyStatus.valueOf("ARCHIVED"))
    }

    @Test
    fun `신규 값 추가 후에도 기존 6개 상태가 모두 존재한다`() {
        assertEquals(
            setOf("ACTIVE", "BUY_BLOCKED", "PAUSED", "DISABLED", "ARCHIVED", "DELETED"),
            StrategyStatus.entries.map { it.name }.toSet(),
        )
        assertTrue(StrategyStatus.entries.size == 6)
    }
}
