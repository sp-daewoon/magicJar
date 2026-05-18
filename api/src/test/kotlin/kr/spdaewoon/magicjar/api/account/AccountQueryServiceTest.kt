package kr.spdaewoon.magicjar.api.account

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.api.service.account.AccountQueryService
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.AccountHolding
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * AccountQueryService 단위 테스트 — T-ACCT Phase 2 (ADR 0081 + spec §13).
 *
 * 시나리오:
 * 1. cache miss → KIS 호출 + cache write TTL 30s
 * 2. cache hit → KIS 호출 0
 * 3. summary 응답 수익률 계산 (totalProfitLoss / totalPurchaseAmount × 100)
 * 4. positions 응답 매핑 (output1 향상판)
 * 5. cache 역직렬화 실패 → cache 삭제 + KIS 재조회 (graceful)
 *
 * mock 합성 0 LOCK (feedback_no_mock_data): 본 테스트는 fixture (테스트 fixture는 면제).
 * AI 호출 0 LOCK: LLM import 0.
 */
class AccountQueryServiceTest {
    private val kisPort: KisBalanceQueryPort = mock()
    private val redis: StringRedisTemplate = mock()
    private val valueOps: ValueOperations<String, String> = mock()
    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }

    private val sut = AccountQueryService(kisPort, redis, objectMapper)

    private val userId = "user_test_001"
    private val accountType = AccountType.REAL
    private val cacheKey = "account:balance:$userId"
    private val now: Instant = Instant.parse("2026-05-18T05:30:00Z")

    private fun fixtureSnapshot(): KisAccountBalance =
        KisAccountBalance(
            userId = userId,
            accountType = accountType,
            balance =
                AccountBalance(
                    cashBalance = BigDecimal("10000000.00"),
                    cashD1 = BigDecimal("10000000.00"),
                    cashD2 = BigDecimal("10000000.00"),
                    totalEvaluation = BigDecimal("12000000.00"),
                    netAssetValue = BigDecimal("12000000.00"),
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
                    AccountHolding(
                        code = "000660",
                        name = "SK하이닉스",
                        quantity = 5,
                        avgPrice = BigDecimal("240000.00"),
                        currentPrice = BigDecimal("258000.00"),
                        evaluationAmount = BigDecimal("1290000.00"),
                        profitLoss = BigDecimal("90000.00"),
                        profitLossRate = BigDecimal("7.50"),
                    ),
                ),
            fetchedAt = now,
        )

    @Test
    fun `cache miss — KIS 호출 1회 + cache SETEX TTL 30s`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get(eq(cacheKey))).thenReturn(null)
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenReturn(fixtureSnapshot())

        val response = sut.getBalance(userId, accountType)

        assertThat(response.cashBalance).isEqualByComparingTo("10000000.00")
        assertThat(response.netAssetValue).isEqualByComparingTo("12000000.00")
        assertThat(response.totalProfitLoss).isEqualByComparingTo("100000.00")
        assertThat(response.fetchedAt).isEqualTo("2026-05-18T05:30:00Z")

        // cache write TTL 30s 검증
        val keyCap = argumentCaptor<String>()
        val ttlCap = argumentCaptor<Duration>()
        verify(valueOps).set(keyCap.capture(), any<String>(), ttlCap.capture())
        assertThat(keyCap.firstValue).isEqualTo(cacheKey)
        assertThat(ttlCap.firstValue).isEqualTo(Duration.ofSeconds(30))

        verify(kisPort).fetchFullBalance(userId, accountType)
    }

    @Test
    fun `cache hit — KIS 호출 0 + cache JSON 역직렬화`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        val json = objectMapper.writeValueAsString(fixtureSnapshot())
        whenever(valueOps.get(eq(cacheKey))).thenReturn(json)

        val response = sut.getBalance(userId, accountType)

        assertThat(response.cashBalance).isEqualByComparingTo("10000000.00")
        verify(kisPort, never()).fetchFullBalance(any(), any())
        verify(valueOps, never()).set(any<String>(), any<String>(), any<Duration>())
    }

    @Test
    fun `summary — 수익률 = totalProfitLoss div totalPurchaseAmount times 100, 보유 종목 수 정합`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get(eq(cacheKey))).thenReturn(null)
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenReturn(fixtureSnapshot())

        val summary = sut.getSummary(userId, accountType)

        assertThat(summary.totalEvaluation).isEqualByComparingTo("12000000.00")
        assertThat(summary.cashD2).isEqualByComparingTo("10000000.00")
        assertThat(summary.totalProfitLoss).isEqualByComparingTo("100000.00")
        // 100000 / 1900000 × 100 = 5.26
        assertThat(summary.profitLossRate).isEqualByComparingTo("5.26")
        assertThat(summary.holdingsCount).isEqualTo(2)
    }

    @Test
    fun `positions — output1 향상판 매핑 (8 컬럼 정합)`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get(eq(cacheKey))).thenReturn(null)
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenReturn(fixtureSnapshot())

        val positions = sut.getPositions(userId, accountType)

        assertThat(positions).hasSize(2)
        val samsung = positions[0]
        assertThat(samsung.code).isEqualTo("005930")
        assertThat(samsung.name).isEqualTo("삼성전자")
        assertThat(samsung.quantity).isEqualTo(10)
        assertThat(samsung.avgPrice).isEqualByComparingTo("70000.00")
        assertThat(samsung.currentPrice).isEqualByComparingTo("71000.00")
        assertThat(samsung.evaluationAmount).isEqualByComparingTo("710000.00")
        assertThat(samsung.profitLoss).isEqualByComparingTo("10000.00")
        assertThat(samsung.profitLossRate).isEqualByComparingTo("1.43")
    }

    @Test
    fun `cache 역직렬화 실패 — cache 삭제 후 KIS 재조회 (graceful)`() {
        whenever(redis.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get(eq(cacheKey))).thenReturn("not-a-valid-json{")
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenReturn(fixtureSnapshot())

        val response = sut.getBalance(userId, accountType)

        assertThat(response.cashBalance).isEqualByComparingTo("10000000.00")
        verify(redis).delete(eq(cacheKey))
        verify(kisPort).fetchFullBalance(userId, accountType)
    }

    @Test
    fun `summary — totalPurchaseAmount 0 시 수익률 0 fallback (divide by zero 회피)`() {
        val snapshot =
            fixtureSnapshot().copy(
                balance =
                    fixtureSnapshot().balance.copy(
                        totalPurchaseAmount = BigDecimal.ZERO,
                        totalProfitLoss = BigDecimal.ZERO,
                    ),
                holdings = emptyList(),
            )
        whenever(redis.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.get(eq(cacheKey))).thenReturn(null)
        whenever(kisPort.fetchFullBalance(userId, accountType)).thenReturn(snapshot)

        val summary = sut.getSummary(userId, accountType)

        assertThat(summary.profitLossRate).isEqualByComparingTo("0")
        assertThat(summary.holdingsCount).isEqualTo(0)
    }

    @Test
    fun `cache hit JSON round-trip — 11 필드 직렬화 정합`() {
        // JSON serialize → deserialize 정합 검증 (cache 호환성)
        val original = fixtureSnapshot()
        val json = objectMapper.writeValueAsString(original)
        val restored: KisAccountBalance = objectMapper.readValue(json)

        assertThat(restored.balance.cashBalance).isEqualByComparingTo(original.balance.cashBalance)
        assertThat(restored.balance.cashD1).isEqualByComparingTo(original.balance.cashD1)
        assertThat(restored.balance.cashD2).isEqualByComparingTo(original.balance.cashD2)
        assertThat(restored.balance.totalEvaluation).isEqualByComparingTo(original.balance.totalEvaluation)
        assertThat(restored.balance.netAssetValue).isEqualByComparingTo(original.balance.netAssetValue)
        assertThat(restored.balance.totalPurchaseAmount).isEqualByComparingTo(original.balance.totalPurchaseAmount)
        assertThat(restored.balance.totalHoldingEvaluation).isEqualByComparingTo(original.balance.totalHoldingEvaluation)
        assertThat(restored.balance.totalProfitLoss).isEqualByComparingTo(original.balance.totalProfitLoss)
        assertThat(restored.balance.assetIncreaseDecrease)
            .isEqualByComparingTo(original.balance.assetIncreaseDecrease)
        assertThat(restored.balance.assetIncreaseDecreaseRate)
            .isEqualByComparingTo(original.balance.assetIncreaseDecreaseRate)
        assertThat(restored.holdings).hasSize(2)
    }
}
