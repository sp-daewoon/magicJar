package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.ActiveTradingModePort
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

/**
 * ActiveAccountTypeResolver 단위 테스트 (감사 2026-09-19 §8 GAP-2).
 *
 * min(env 상한, DB 활성모드) — TradingModeGate와 동일 논리:
 *  - env 상한 MOCK → 무조건 MOCK (DB 무관, 상한 절대)
 *  - env 상한 REAL + DB=REAL → REAL
 *  - env 상한 REAL + DB=MOCK → MOCK (재기동 없는 전환 존중)
 *  - 포트 미합류 → MOCK fail-closed
 */
class ActiveAccountTypeResolverTest {
    private fun modeProvider(mode: TradingMode?): ObjectProvider<ActiveTradingModePort> {
        val provider = mockk<ObjectProvider<ActiveTradingModePort>>()
        if (mode == null) {
            every { provider.getIfAvailable() } returns null
        } else {
            val port = mockk<ActiveTradingModePort>()
            every { port.activeMode() } returns mode
            every { provider.getIfAvailable() } returns port
        }
        return provider
    }

    @Test
    fun `env 상한 MOCK이면 DB=REAL이어도 MOCK (상한 절대)`() {
        val resolver = ActiveAccountTypeResolver(modeProvider(TradingMode.REAL), magicjarMode = "MOCK")
        assertThat(resolver.activeAccountType()).isEqualTo(AccountType.MOCK)
    }

    @Test
    fun `env 상한 REAL + DB 활성모드 REAL → REAL`() {
        val resolver = ActiveAccountTypeResolver(modeProvider(TradingMode.REAL), magicjarMode = "REAL")
        assertThat(resolver.activeAccountType()).isEqualTo(AccountType.REAL)
    }

    @Test
    fun `env 상한 REAL + DB 활성모드 MOCK → MOCK (재기동 없는 전환 존중)`() {
        val resolver = ActiveAccountTypeResolver(modeProvider(TradingMode.MOCK), magicjarMode = "REAL")
        assertThat(resolver.activeAccountType()).isEqualTo(AccountType.MOCK)
    }

    @Test
    fun `env 상한 REAL + 포트 미합류 → MOCK fail-closed`() {
        val resolver = ActiveAccountTypeResolver(modeProvider(null), magicjarMode = "REAL")
        assertThat(resolver.activeAccountType()).isEqualTo(AccountType.MOCK)
    }

    @Test
    fun `env 상한 대소문자·공백 정규화`() {
        val resolver = ActiveAccountTypeResolver(modeProvider(TradingMode.REAL), magicjarMode = " real ")
        assertThat(resolver.activeAccountType()).isEqualTo(AccountType.REAL)
    }
}
