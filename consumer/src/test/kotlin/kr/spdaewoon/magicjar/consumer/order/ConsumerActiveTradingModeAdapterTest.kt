package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.persistence.shared.system.SystemConfigJpaEntity
import kr.spdaewoon.magicjar.persistence.shared.system.SystemConfigJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

/**
 * ConsumerActiveTradingModeAdapter 단위 테스트 (감사 2026-09-19 §5 A).
 *
 * 안전 불변식 검증:
 *  - REAL/MOCK 정상 파싱
 *  - 대소문자/공백 정규화
 *  - row 없음 → MOCK fail-closed
 *  - 미정의 값 → MOCK fail-closed
 *  - repository 미합류 → MOCK fail-closed
 *  - 조회 예외 → MOCK fail-closed
 */
class ConsumerActiveTradingModeAdapterTest {
    private fun providerOf(repo: SystemConfigJpaRepository?): ObjectProvider<SystemConfigJpaRepository> =
        mockk<ObjectProvider<SystemConfigJpaRepository>>().also {
            every { it.ifAvailable } returns repo
        }

    private fun entityWithMode(mode: String): SystemConfigJpaEntity = SystemConfigJpaEntity(id = 1).also { it.activeTradingMode = mode }

    // TTL=0 으로 매 호출 DB 재조회 (캐시 간섭 배제).
    private fun adapter(repo: SystemConfigJpaRepository?) = ConsumerActiveTradingModeAdapter(providerOf(repo), cacheTtlMs = 0L)

    @Test
    fun `active_trading_mode=REAL 이면 REAL`() {
        val repo = mockk<SystemConfigJpaRepository>()
        every { repo.findSingleton() } returns entityWithMode("REAL")

        assertThat(adapter(repo).activeMode()).isEqualTo(TradingMode.REAL)
    }

    @Test
    fun `active_trading_mode=MOCK 이면 MOCK`() {
        val repo = mockk<SystemConfigJpaRepository>()
        every { repo.findSingleton() } returns entityWithMode("MOCK")

        assertThat(adapter(repo).activeMode()).isEqualTo(TradingMode.MOCK)
    }

    @Test
    fun `대소문자와 공백을 정규화한다`() {
        val repo = mockk<SystemConfigJpaRepository>()
        every { repo.findSingleton() } returns entityWithMode(" real ")

        assertThat(adapter(repo).activeMode()).isEqualTo(TradingMode.REAL)
    }

    @Test
    fun `row 없음(null) → MOCK fail-closed`() {
        val repo = mockk<SystemConfigJpaRepository>()
        every { repo.findSingleton() } returns null

        assertThat(adapter(repo).activeMode()).isEqualTo(TradingMode.MOCK)
    }

    @Test
    fun `미정의 값 → MOCK fail-closed`() {
        val repo = mockk<SystemConfigJpaRepository>()
        every { repo.findSingleton() } returns entityWithMode("FOO")

        assertThat(adapter(repo).activeMode()).isEqualTo(TradingMode.MOCK)
    }

    @Test
    fun `repository 미합류 → MOCK fail-closed`() {
        assertThat(adapter(null).activeMode()).isEqualTo(TradingMode.MOCK)
    }

    @Test
    fun `조회 예외 → MOCK fail-closed`() {
        val repo = mockk<SystemConfigJpaRepository>()
        every { repo.findSingleton() } throws RuntimeException("db down")

        assertThat(adapter(repo).activeMode()).isEqualTo(TradingMode.MOCK)
    }
}
