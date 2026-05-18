package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.AccountHolding
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Instant

/**
 * KisCapitalSnapshotProviderAdapter 단위 테스트 — T-ACCT Phase 3 (ADR 0081 §2-5 + ADR 0067 §7-1).
 *
 * ## 시나리오 매트릭스 (5건 — fail-safe 정책 + cache key 정합 검증)
 * 1. cache HIT → `netAssetValue` 반환
 * 2. cache MISS → BigDecimal.ZERO (fail-safe, POSITION_LIMIT 룰 BUY 차단 → 자본 보호)
 * 3. Redis exception → BigDecimal.ZERO (graceful)
 * 4. JSON 역직렬화 실패 → BigDecimal.ZERO (graceful)
 * 5. cache key prefix 정합 — api `AccountQueryService.CACHE_KEY_PREFIX` 와 동일 (`account:balance`)
 * 6. cache HIT 0 netAssetValue → 명시적 0 반환 (자본 소진 사용자)
 *
 * mock 합성 0 LOCK (feedback_no_mock_data): 본 테스트는 fixture (테스트 fixture 면제).
 * AI 호출 0 LOCK (룰 5 + ADR 0023 §2-4): LLM provider import 0.
 */
class KisCapitalSnapshotProviderAdapterTest {
    private val redis = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

    private val sut = KisCapitalSnapshotProviderAdapter(redis, objectMapper)

    private val userId = "user_test_001"
    private val cacheKey = "account:balance:$userId"
    private val now: Instant = Instant.parse("2026-05-18T05:30:00Z")

    private fun fixtureSnapshot(netAssetValue: BigDecimal = BigDecimal("12000000.00")): KisAccountBalance =
        KisAccountBalance(
            userId = userId,
            accountType = AccountType.REAL,
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
    fun `cache HIT — netAssetValue 반환`() {
        every { redis.opsForValue() } returns valueOps
        val json = objectMapper.writeValueAsString(fixtureSnapshot(BigDecimal("12000000.00")))
        every { valueOps.get(cacheKey) } returns json

        val capital = sut.getCapital(userId)

        assertThat(capital).isEqualByComparingTo("12000000.00")
    }

    @Test
    fun `cache MISS — fail-safe BigDecimal_ZERO 반환 (POSITION_LIMIT 룰 BUY 차단)`() {
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(cacheKey) } returns null

        val capital = sut.getCapital(userId)

        // RiskGatewayEvaluator L146-179: currentCapital=0 → budget=0 → 모든 BUY BLOCKED → 자본 보호
        assertThat(capital).isEqualByComparingTo("0")
    }

    @Test
    fun `Redis exception — fail-safe BigDecimal_ZERO 반환 (graceful)`() {
        // opsForValue() 단계에서 Redis connection 실패 시뮬레이션
        every { redis.opsForValue() } throws RuntimeException("Redis connection refused")

        val capital = sut.getCapital(userId)

        assertThat(capital).isEqualByComparingTo("0")
    }

    @Test
    fun `JSON 역직렬화 실패 — fail-safe BigDecimal_ZERO 반환 (cache 오염 graceful)`() {
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(cacheKey) } returns "not-a-valid-json{"

        val capital = sut.getCapital(userId)

        assertThat(capital).isEqualByComparingTo("0")
    }

    @Test
    fun `cache key prefix 정합 — api AccountQueryService 와 동일 (account_colon_balance)`() {
        // 모듈 경계 LOCK (ADR 0004) — string literal 중복 의도적, 변경 시 양 모듈 동시 갱신 의무
        assertThat(KisCapitalSnapshotProviderAdapter.CACHE_KEY_PREFIX).isEqualTo("account:balance")
    }

    @Test
    fun `cache HIT 0 netAssetValue — 명시적 0 반환 (자본 0 사용자)`() {
        // 매수 직후 자본 모두 소진한 사용자 시뮬레이션 — cache 데이터는 정상이나 자본만 0
        every { redis.opsForValue() } returns valueOps
        val json = objectMapper.writeValueAsString(fixtureSnapshot(BigDecimal.ZERO))
        every { valueOps.get(cacheKey) } returns json

        val capital = sut.getCapital(userId)

        assertThat(capital).isEqualByComparingTo("0")
    }
}
