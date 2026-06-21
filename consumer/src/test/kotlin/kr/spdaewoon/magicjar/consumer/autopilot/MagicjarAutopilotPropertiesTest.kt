package kr.spdaewoon.magicjar.consumer.autopilot

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [MagicjarAutopilotProperties.buyEvalOwners] 단위 테스트 (magicjar-autopilot Phase 1 wiring · spec 2026-06-22 §2).
 *
 * BUY 구동 평가 owner 집합이 enabled 게이트에 정확히 연동되는지 — autopilot 평가 진입의 단일 게이트 산정 검증.
 */
class MagicjarAutopilotPropertiesTest {
    @Test
    fun `enabled=false 면 buyEvalOwners 는 USER 만 (AUTOPILOT 제외·byte 동일)`() {
        val owners = MagicjarAutopilotProperties(enabled = false).buyEvalOwners()
        assertThat(owners).containsExactly("USER")
    }

    @Test
    fun `enabled=true 면 buyEvalOwners 는 USER+AUTOPILOT`() {
        val owners = MagicjarAutopilotProperties(enabled = true).buyEvalOwners()
        assertThat(owners).containsExactly("USER", "AUTOPILOT")
    }

    @Test
    fun `owner 문자열은 StrategyOwner enum name 과 정확히 일치한다 (DB owner VARCHAR 정합)`() {
        // 오타 시 DB owner 값과 안 맞아 평가 0 이 되는 silent 결함 가드.
        assertThat(MagicjarAutopilotProperties(enabled = true).buyEvalOwners())
            .containsExactly(
                kr.spdaewoon.magicjar.domain.strategy.StrategyOwner.USER.name,
                kr.spdaewoon.magicjar.domain.strategy.StrategyOwner.AUTOPILOT.name,
            )
    }
}
