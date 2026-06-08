package kr.spdaewoon.magicjar.consumer.risk

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.AccountHolding
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant

/**
 * KisCapitalSnapshotProviderAdapter 단위 테스트 — 주문경로 자본캐시 제거 (ADR 0116 R1-3).
 *
 * ## 시나리오 매트릭스 (캐시 우회 → KIS 직접 fresh 조회 + fail-safe 보존)
 * 1. KIS fresh 조회 성공 → `netAssetValue` 반환 (캐시 read/write 0 — 매 평가 fresh)
 * 2. KIS 조회 bean 부재 → BigDecimal.ZERO (fail-safe, POSITION_LIMIT 룰 BUY 차단 → 자본 보호)
 * 3. KIS 조회 실패(rate limit·upstream) → BigDecimal.ZERO (graceful)
 * 4. netAssetValue 0 → 명시적 0 반환 (자본 소진 사용자)
 * 5. ★ 캐시 우회 검증 — Redis 의존 0 (생성자에 StringRedisTemplate 미주입) + port.fetchFullBalance만 호출
 *
 * mock 합성 0 LOCK (feedback_no_mock_data): 본 테스트는 fixture (테스트 fixture 면제).
 * AI 호출 0 LOCK (룰 5 + ADR 0023 §2-4): LLM provider import 0.
 */
class KisCapitalSnapshotProviderAdapterTest {
    private val balanceQueryPort = mockk<KisBalanceQueryPort>()

    @Suppress("UNCHECKED_CAST")
    private val portProvider = mockk<ObjectProvider<KisBalanceQueryPort>>()

    private val userId = "user_test_001"
    private val now: Instant = Instant.parse("2026-05-18T05:30:00Z")

    // magicjarMode default MOCK → accountType=MOCK
    private val sut =
        KisCapitalSnapshotProviderAdapter(
            balanceQueryPortProvider = portProvider,
            magicjarMode = "MOCK",
        )

    private fun fixtureSnapshot(netAssetValue: BigDecimal = BigDecimal("12000000.00")): KisAccountBalance =
        KisAccountBalance(
            userId = userId,
            accountType = AccountType.MOCK,
            balance =
                AccountBalance(
                    cashBalance = BigDecimal("10000000.00"),
                    cashD1 = BigDecimal("10000000.00"),
                    cashD2 = BigDecimal("10000000.00"),
                    totalEvaluation = BigDecimal("12000000.00"),
                    netAssetValue = netAssetValue,
                    totalPurchaseAmount = BigDecimal("1900000.00"),
                    totalHoldingEvaluation = BigDecimal("2000000.00"),
                    totalProfitLoss = BigDecimal("100000.00"),
                    assetIncreaseDecrease = BigDecimal("50000.00"),
                    assetIncreaseDecreaseRate = BigDecimal("0.42"),
                    fetchedAt = now,
                ),
            holdings =
                listOf(
                    AccountHolding(
                        code = "005930",
                        name = "삼성전자",
                        quantity = 10,
                        avgPrice = BigDecimal("70000.00"),
                        currentPrice = BigDecimal("71000.00"),
                        evaluationAmount = BigDecimal("710000.00"),
                        profitLoss = BigDecimal("10000.00"),
                        profitLossRate = BigDecimal("1.43"),
                    ),
                ),
            fetchedAt = now,
        )

    @Test
    fun `KIS fresh 조회 성공 — netAssetValue 반환 (캐시 우회)`() {
        every { portProvider.getIfAvailable() } returns balanceQueryPort
        every { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) } returns
            fixtureSnapshot(BigDecimal("12000000.00"))

        val capital = sut.getCapital(userId)

        assertThat(capital).isEqualByComparingTo("12000000.00")
        // ★ 캐시 우회 — KIS 직접 조회 1회 (매 주문 평가 fresh, stale 잔고 오버주문 방지)
        verify(exactly = 1) { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) }
    }

    @Test
    fun `KIS 조회 bean 부재 — fail-safe BigDecimal_ZERO 반환 (POSITION_LIMIT 룰 BUY 차단)`() {
        every { portProvider.getIfAvailable() } returns null

        val capital = sut.getCapital(userId)

        // currentCapital=0 → budget=0 → 모든 BUY BLOCKED → 자본 보호
        assertThat(capital).isEqualByComparingTo("0")
    }

    @Test
    fun `KIS 직접 조회 실패 — fail-safe BigDecimal_ZERO 반환 (graceful)`() {
        every { portProvider.getIfAvailable() } returns balanceQueryPort
        every { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) } throws
            RuntimeException("KIS rate limit EGW00201")

        val capital = sut.getCapital(userId)

        assertThat(capital).isEqualByComparingTo("0")
    }

    @Test
    fun `KIS fresh 조회 0 netAssetValue — 명시적 0 반환 (자본 0 사용자)`() {
        every { portProvider.getIfAvailable() } returns balanceQueryPort
        every { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) } returns
            fixtureSnapshot(BigDecimal.ZERO)

        val capital = sut.getCapital(userId)

        assertThat(capital).isEqualByComparingTo("0")
    }
}
