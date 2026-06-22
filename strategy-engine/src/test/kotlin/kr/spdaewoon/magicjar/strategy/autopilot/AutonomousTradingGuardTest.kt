package kr.spdaewoon.magicjar.strategy.autopilot

import kr.spdaewoon.magicjar.domain.autopilot.AutopilotState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [AutonomousTradingGuard] 회로차단기 경계 단위 테스트 (순수 함수 — 결정론·저비용).
 *
 * 평가 순서 LOCK(spec §2-0-A): kill active > 순자산≤0 > 누적손실(kill) > 일일손실(reject) >
 * 동시보유(reject) > 풀상한(clamp/reject) > 건당캡(clamp → Allow).
 *
 * 한도 default(AutopilotState): pool=순자산 5% / perTrade=풀 20% / maxPositions=3 / daily=풀 30% / cum=풀 50%.
 * 테스트는 계산 명료성을 위해 accountNetWorth=1,000,000 기준.
 * - poolCeiling = 1,000,000 × 5% = 50,000
 * - perTradeCap = min(풀×20%, 풀÷maxPositions) = min(10,000, 16,666.67) = **10,000** (default 는 perTradePct 가 이김)
 *   ★ 2026-06-22 분산형 건당 캡: `min(풀×perTradePct/100, 풀÷maxPositions)`. default(perTrade 20% < 1/3) 에선
 *   perTradePct 가 이겨 기존 동작 byte 동일(zero-regression). perTradePct 를 100%로 넓혀야 fair-share floor 가
 *   드러난다(§5-bis 분산 시나리오 — 사용자 라이브 pool 100%·perTrade 100%·max 3/20 재현).
 * - dailyLossLimit = 풀 50,000 × 30% = **15,000** (순자산 아님 — spec §2-0-A '매우 타이트', 2026-06-22 fix)
 * - cumLossLimit = 풀 50,000 × 50% = **25,000**
 *
 * 주: 본 Guard는 일일/누적 손실 한도를 **풀 base × pct**로 계산한다(spec §2-0-A 한도표 권위 — 순자산 기준이면
 * 누적 kill이 순자산 절반에서야 터져 '매우 타이트'에 위배).
 */
class AutonomousTradingGuardTest {
    private val netWorth = BigDecimal("1000000")

    private fun state(
        killActive: Boolean = false,
        poolPct: String = "5",
        perTradePct: String = "20",
        maxPositions: Int = 3,
        dailyLossPct: String = "30",
        cumLossPct: String = "50",
        cumRealizedLoss: String = "0",
        dailyRealizedLoss: String = "0",
    ) = AutopilotState(
        killActive = killActive,
        poolPct = BigDecimal(poolPct),
        perTradePct = BigDecimal(perTradePct),
        maxPositions = maxPositions,
        dailyLossPct = BigDecimal(dailyLossPct),
        cumLossPct = BigDecimal(cumLossPct),
        cumRealizedLoss = BigDecimal(cumRealizedLoss),
        dailyRealizedLoss = BigDecimal(dailyRealizedLoss),
    )

    private fun input(
        accountNetWorth: BigDecimal = netWorth,
        loopExposure: String = "0",
        proposedEntryAmount: String = "8000",
        currentLoopPositions: Int = 0,
        dailyRealizedLoss: String = "0",
        cumRealizedLoss: String = "0",
        state: AutopilotState = state(),
    ) = AutopilotGuardInput(
        accountNetWorth = accountNetWorth,
        loopExposure = BigDecimal(loopExposure),
        proposedEntryAmount = BigDecimal(proposedEntryAmount),
        currentLoopPositions = currentLoopPositions,
        dailyRealizedLoss = BigDecimal(dailyRealizedLoss),
        cumRealizedLoss = BigDecimal(cumRealizedLoss),
        state = state,
    )

    // === 정상 통과 — 모든 한도 미만 → Allow(정확한 approvedAmount) ===

    @Test
    fun `정상 진입 — 제안 8000 (캡 10000 풀잔여 50000 미만) → Allow(8000)`() {
        val verdict = AutonomousTradingGuard.evaluate(input(proposedEntryAmount = "8000"))
        val allow = assertInstanceOf(AutopilotGuardVerdict.Allow::class.java, verdict)
        assertEquals(0, BigDecimal("8000").compareTo(allow.approvedAmount), "approvedAmount=${allow.approvedAmount}")
    }

    // === 0. kill active ===

    @Test
    fun `killActive=true → Reject(kill active) — 다른 한도 모두 통과 가능해도 차단`() {
        val verdict = AutonomousTradingGuard.evaluate(input(state = state(killActive = true)))
        val reject = assertInstanceOf(AutopilotGuardVerdict.Reject::class.java, verdict)
        assertEquals("kill active", reject.reason)
    }

    // === 0-b. 순자산 ≤0 방어 ===

    @Test
    fun `순자산 0 → Reject(자본 산정 불가)`() {
        val verdict = AutonomousTradingGuard.evaluate(input(accountNetWorth = BigDecimal.ZERO))
        val reject = assertInstanceOf(AutopilotGuardVerdict.Reject::class.java, verdict)
        assertTrue(reject.reason.contains("자본 산정 불가"), reject.reason)
    }

    @Test
    fun `순자산 음수 → Reject(자본 산정 불가)`() {
        val verdict = AutonomousTradingGuard.evaluate(input(accountNetWorth = BigDecimal("-1")))
        assertInstanceOf(AutopilotGuardVerdict.Reject::class.java, verdict)
    }

    // === 1. 누적 손실 → KillTriggered ===

    @Test
    fun `누적손실 도달 (cum=25000 = 풀 50pct) → KillTriggered`() {
        val verdict = AutonomousTradingGuard.evaluate(input(cumRealizedLoss = "25000"))
        assertInstanceOf(AutopilotGuardVerdict.KillTriggered::class.java, verdict)
    }

    @Test
    fun `누적손실 미만 (cum=24999) → 통과 진행 (Allow)`() {
        val verdict = AutonomousTradingGuard.evaluate(input(cumRealizedLoss = "24999"))
        assertInstanceOf(AutopilotGuardVerdict.Allow::class.java, verdict)
    }

    // === 2. 일일 손실 → Reject ===

    @Test
    fun `일일손실 도달 (daily=15000 = 풀 30pct) → Reject(당일 halt)`() {
        val verdict = AutonomousTradingGuard.evaluate(input(dailyRealizedLoss = "15000"))
        val reject = assertInstanceOf(AutopilotGuardVerdict.Reject::class.java, verdict)
        assertTrue(reject.reason.contains("당일 halt"), reject.reason)
    }

    @Test
    fun `일일손실 미만 (daily=14999) → 통과 진행 (Allow)`() {
        val verdict = AutonomousTradingGuard.evaluate(input(dailyRealizedLoss = "14999"))
        assertInstanceOf(AutopilotGuardVerdict.Allow::class.java, verdict)
    }

    // === 3. 동시 보유 종목수 → Reject ===

    @Test
    fun `동시보유 max 도달 (positions=3 = max) → Reject`() {
        val verdict = AutonomousTradingGuard.evaluate(input(currentLoopPositions = 3))
        val reject = assertInstanceOf(AutopilotGuardVerdict.Reject::class.java, verdict)
        assertTrue(reject.reason.contains("동시 보유"), reject.reason)
    }

    @Test
    fun `동시보유 미만 (positions=2) → 통과 진행 (Allow)`() {
        val verdict = AutonomousTradingGuard.evaluate(input(currentLoopPositions = 2))
        assertInstanceOf(AutopilotGuardVerdict.Allow::class.java, verdict)
    }

    // === 4. 풀 상한 ===

    @Test
    fun `풀 상한 잔여 양수 — exposure=45000 풀상한 50000 잔여 5000, 제안 8000 → Allow(5000 clamp)`() {
        val verdict = AutonomousTradingGuard.evaluate(input(loopExposure = "45000", proposedEntryAmount = "8000"))
        val allow = assertInstanceOf(AutopilotGuardVerdict.Allow::class.java, verdict)
        assertEquals(0, BigDecimal("5000").compareTo(allow.approvedAmount), "approvedAmount=${allow.approvedAmount}")
    }

    @Test
    fun `풀 상한 잔여 ≤ 0 — exposure=50000 = 풀상한 → Reject(풀 소진)`() {
        val verdict = AutonomousTradingGuard.evaluate(input(loopExposure = "50000"))
        val reject = assertInstanceOf(AutopilotGuardVerdict.Reject::class.java, verdict)
        assertTrue(reject.reason.contains("풀 상한 소진"), reject.reason)
    }

    @Test
    fun `풀 상한 초과 — exposure=60000 풀상한 50000 초과 → Reject(풀 소진)`() {
        val verdict = AutonomousTradingGuard.evaluate(input(loopExposure = "60000"))
        assertInstanceOf(AutopilotGuardVerdict.Reject::class.java, verdict)
    }

    // === 5. 건당 캡 clamp ===

    @Test
    fun `건당 캡 clamp — 제안 20000 캡 10000 초과 → Allow(10000)`() {
        val verdict = AutonomousTradingGuard.evaluate(input(proposedEntryAmount = "20000"))
        val allow = assertInstanceOf(AutopilotGuardVerdict.Allow::class.java, verdict)
        assertEquals(0, BigDecimal("10000").compareTo(allow.approvedAmount), "approvedAmount=${allow.approvedAmount}")
    }

    @Test
    fun `건당 캡 = min(제안, 풀잔여, 캡) — 풀잔여가 가장 작으면 풀잔여로 clamp`() {
        // exposure=47000 → 풀잔여 3000 < 캡 10000 < 제안 20000 → Allow(3000)
        val verdict = AutonomousTradingGuard.evaluate(input(loopExposure = "47000", proposedEntryAmount = "20000"))
        val allow = assertInstanceOf(AutopilotGuardVerdict.Allow::class.java, verdict)
        assertEquals(0, BigDecimal("3000").compareTo(allow.approvedAmount), "approvedAmount=${allow.approvedAmount}")
    }

    @Test
    fun `승인 금액 ≤ 0 — 제안 0 → Reject(승인 가능 금액 ≤0)`() {
        val verdict = AutonomousTradingGuard.evaluate(input(proposedEntryAmount = "0"))
        val reject = assertInstanceOf(AutopilotGuardVerdict.Reject::class.java, verdict)
        assertTrue(reject.reason.contains("승인 가능 금액"), reject.reason)
    }

    // === 5-bis. 분산형 건당 캡 — per-slot 균등 분율 floor (2026-06-22 몰빵→분산 교정) ===
    //   건당캡 = min(풀 × perTradePct/100, 풀 ÷ maxPositions). perTradePct 를 100%로 넓혀도 풀/maxPositions floor 가
    //   한 종목 최대 노출을 bound 해 몰빵을 차단한다. 사용자 라이브 설정(pool 100%·perTrade 100%·max 20·3) 재현.

    @Test
    fun `몰빵 차단 — perTrade 100pct여도 풀÷maxPositions(3) fair-share 로 clamp (한 종목 풀 전부 X)`() {
        // pool=100% → poolCeiling=netWorth=1,000,000. perTrade=100% → pctCap=1,000,000.
        // max=3 → fairShare=1,000,000/3=333,333.3333. min=333,333.33. 제안 1,000,000(전량) → fairShare 로 clamp.
        val verdict =
            AutonomousTradingGuard.evaluate(
                input(
                    proposedEntryAmount = "1000000",
                    state = state(poolPct = "100", perTradePct = "100", maxPositions = 3),
                ),
            )
        val allow = assertInstanceOf(AutopilotGuardVerdict.Allow::class.java, verdict)
        // 풀 전부(1,000,000)가 아니라 풀/3 만 승인 — 몰빵 차단 실증. (≈333,333 — 풀 67% 노출 사라짐)
        assertTrue(
            allow.approvedAmount < BigDecimal("400000"),
            "한 종목에 풀 전부 들어가면 안 됨 — approvedAmount=${allow.approvedAmount}",
        )
        assertEquals(
            0,
            BigDecimal("333333.3333").compareTo(allow.approvedAmount),
            "fair-share(풀/3)로 clamp 돼야 함 — approvedAmount=${allow.approvedAmount}",
        )
    }

    @Test
    fun `몰빵 차단 — 사용자 라이브 설정(pool 100pct·perTrade 100pct·max 20) → 풀÷20 = 순자산 5pct 로 분산`() {
        // pool=100%·perTrade=100%·max=20 → fairShare=1,000,000/20=50,000(순자산 5%). 제안 전량 → 50,000 으로 clamp.
        val verdict =
            AutonomousTradingGuard.evaluate(
                input(
                    proposedEntryAmount = "1000000",
                    state = state(poolPct = "100", perTradePct = "100", maxPositions = 20),
                ),
            )
        val allow = assertInstanceOf(AutopilotGuardVerdict.Allow::class.java, verdict)
        assertEquals(
            0,
            BigDecimal("50000").compareTo(allow.approvedAmount),
            "풀/20=50,000(순자산 5%)으로 분산돼야 함 — approvedAmount=${allow.approvedAmount}",
        )
    }

    @Test
    fun `3슬롯 균등 분산 실증 — 동일 풀에서 슬롯이 찰수록 잔여로 자연 분산 (몰빵 0)`() {
        // pool=100%·perTrade=100%·max=3. 슬롯 1: exposure 0 → 풀/3 승인.
        val s = state(poolPct = "100", perTradePct = "100", maxPositions = 3)
        val slot1 = AutonomousTradingGuard.evaluate(input(loopExposure = "0", proposedEntryAmount = "1000000", state = s))
        val a1 = assertInstanceOf(AutopilotGuardVerdict.Allow::class.java, slot1)
        assertEquals(0, BigDecimal("333333.3333").compareTo(a1.approvedAmount), "슬롯1=풀/3")

        // 슬롯 2: 슬롯1이 333,333 점유 → 잔여 666,666. fairShare(333,333) < 잔여 → 다시 풀/3 승인(균등).
        val slot2 = AutonomousTradingGuard.evaluate(input(loopExposure = "333333", proposedEntryAmount = "1000000", state = s))
        val a2 = assertInstanceOf(AutopilotGuardVerdict.Allow::class.java, slot2)
        assertEquals(0, BigDecimal("333333.3333").compareTo(a2.approvedAmount), "슬롯2=풀/3(균등)")

        // 어떤 단일 슬롯도 풀의 1/3 초과 점유 못 함 — 몰빵 0(분산 LOCK).
        assertTrue(a1.approvedAmount <= BigDecimal("333334") && a2.approvedAmount <= BigDecimal("333334"))
    }

    @Test
    fun `좁은 perTradePct 는 기존 동작 보존 — perTrade 20pct(풀÷3=33pct보다 좁음) 이면 perTradePct 가 이김`() {
        // pool=5%(default)·perTrade=20%·max=3. poolCeiling=50,000. pctCap=50,000×20%=10,000.
        // fairShare=50,000/3=16,666.67. min=10,000 → perTradePct 가 이김(기존 동작 byte 동일 — zero-regression).
        val verdict = AutonomousTradingGuard.evaluate(input(proposedEntryAmount = "20000"))
        val allow = assertInstanceOf(AutopilotGuardVerdict.Allow::class.java, verdict)
        assertEquals(
            0,
            BigDecimal("10000").compareTo(allow.approvedAmount),
            "좁은 perTradePct(20%)면 그 값이 이김 — approvedAmount=${allow.approvedAmount}",
        )
    }

    @Test
    fun `maxPositions ≤ 0 방어 — max=0 이면 1 로 floor(0 나눗셈 회피), 풀상한이 cap`() {
        // pool=100%·perTrade=100%·max=0 → coerceAtLeast(1) → fairShare=풀/1=풀 전부. 단 풀상한·풀잔여가 여전히 cap.
        val verdict =
            AutonomousTradingGuard.evaluate(
                input(
                    proposedEntryAmount = "2000000",
                    state = state(poolPct = "100", perTradePct = "100", maxPositions = 0),
                ),
            )
        // max=0 은 동시보유 게이트(positions 0 >= 0)에서 먼저 Reject 된다 — 0 나눗셈 ArithmeticException 0 실증이 핵심.
        assertInstanceOf(AutopilotGuardVerdict.Reject::class.java, verdict)
    }

    // === 우선순위 — 보수적 정지가 이긴다 ===

    @Test
    fun `우선순위 — 누적손실 + 풀 초과 동시 → KillTriggered 우선 (가장 치명적)`() {
        val verdict =
            AutonomousTradingGuard.evaluate(
                input(loopExposure = "60000", cumRealizedLoss = "25000"),
            )
        assertInstanceOf(AutopilotGuardVerdict.KillTriggered::class.java, verdict)
    }

    @Test
    fun `우선순위 — kill active + 누적손실 동시 → Reject(kill active) 먼저 (게이트 0순위)`() {
        val verdict =
            AutonomousTradingGuard.evaluate(
                input(cumRealizedLoss = "25000", state = state(killActive = true)),
            )
        val reject = assertInstanceOf(AutopilotGuardVerdict.Reject::class.java, verdict)
        assertEquals("kill active", reject.reason)
    }

    @Test
    fun `우선순위 — 일일손실 + 동시보유 동시 → 일일손실 Reject 먼저 (당일 halt)`() {
        val verdict =
            AutonomousTradingGuard.evaluate(
                input(currentLoopPositions = 3, dailyRealizedLoss = "15000"),
            )
        val reject = assertInstanceOf(AutopilotGuardVerdict.Reject::class.java, verdict)
        assertTrue(reject.reason.contains("당일 halt"), reject.reason)
    }
}
