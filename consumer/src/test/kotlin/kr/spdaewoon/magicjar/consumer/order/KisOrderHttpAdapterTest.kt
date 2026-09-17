package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisAuthAdapter
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisHashKeyAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.OrderIntent
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Instant

/**
 * KisOrderHttpAdapter 단위 테스트 (ADR 0068 §14-5·§14-7 LOCK).
 *
 * 5 케이스 매트릭스 (§14-7) — tr_id 분기 + body 구성 verify (WebClient 호출은 별도 통합 테스트):
 *  (a) BUY MOCK → tr_id=VTTC0012U
 *  (b) BUY REAL → tr_id=TTTC0012U
 *  (c) SELL MOCK → tr_id=VTTC0011U
 *  (d) SELL REAL → tr_id=TTTC0011U
 *  (e) MARKET ORD_DVSN=01 ORD_UNPR=0 / LIMIT ORD_DVSN=00 ORD_UNPR=limitPrice
 *  (f) SELL body — SLL_TYPE="01" 부착
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class KisOrderHttpAdapterTest {
    private val webClient = mockk<WebClient>(relaxed = true)
    private val secretCipher = mockk<SecretCipher>(relaxed = true)
    private val kisAuthAdapter = mockk<KisAuthAdapter>(relaxed = true)
    private val kisHashKeyAdapter = mockk<KisHashKeyAdapter>(relaxed = true)
    private val credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>(relaxed = true)
    private val kisRateLimiterPort = mockk<KisRateLimiterPort>(relaxed = true)

    private val adapter =
        KisOrderHttpAdapter(
            webClient = webClient,
            secretCipher = secretCipher,
            kisAuthAdapter = kisAuthAdapter,
            kisHashKeyAdapter = kisHashKeyAdapter,
            credentialRepository = credentialRepository,
            kisRateLimiterPort = kisRateLimiterPort,
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )

    @Test
    fun `(a) BUY MOCK → tr_id VTTC0012U`() {
        assertThat(adapter.resolveTrId(OrderSide.BUY, TradingMode.MOCK)).isEqualTo("VTTC0012U")
    }

    @Test
    fun `(b) BUY REAL → tr_id TTTC0012U`() {
        assertThat(adapter.resolveTrId(OrderSide.BUY, TradingMode.REAL)).isEqualTo("TTTC0012U")
    }

    @Test
    fun `(c) SELL MOCK → tr_id VTTC0011U`() {
        assertThat(adapter.resolveTrId(OrderSide.SELL, TradingMode.MOCK)).isEqualTo("VTTC0011U")
    }

    @Test
    fun `(d) SELL REAL → tr_id TTTC0011U`() {
        assertThat(adapter.resolveTrId(OrderSide.SELL, TradingMode.REAL)).isEqualTo("TTTC0011U")
    }

    @Test
    fun `(e) MARKET intent → ORD_DVSN=01 ORD_UNPR=0`() {
        val cred = stubCredential()
        val intent = buildIntent(OrderSide.BUY, OrderPriceType.MARKET, limit = null)

        val body = adapter.buildOrderCashBody(cred, intent)

        assertThat(body["ORD_DVSN"]).isEqualTo("01")
        assertThat(body["ORD_UNPR"]).isEqualTo("0")
        assertThat(body["PDNO"]).isEqualTo("005930")
        assertThat(body["ORD_QTY"]).isEqualTo("10")
        assertThat(body["EXCG_ID_DVSN_CD"]).isEqualTo("KRX")
        assertThat(body["CNDT_PRIC"]).isEqualTo("0")
        // BUY는 SLL_TYPE 부착 X
        assertThat(body).doesNotContainKey("SLL_TYPE")
    }

    @Test
    fun `(f) LIMIT SELL intent → ORD_DVSN=00 ORD_UNPR=limitPrice + SLL_TYPE=01`() {
        val cred = stubCredential()
        val intent = buildIntent(OrderSide.SELL, OrderPriceType.LIMIT, limit = BigDecimal("78000"))

        val body = adapter.buildOrderCashBody(cred, intent)

        assertThat(body["ORD_DVSN"]).isEqualTo("00")
        assertThat(body["ORD_UNPR"]).isEqualTo("78000")
        // SELL은 SLL_TYPE="01" (일반매도) 부착 LOCK
        assertThat(body["SLL_TYPE"]).isEqualTo("01")
    }

    // ── ORD_UNPR 최종 호가단위(tick) 정렬 방어선 (2026-09-17 사용자 최우선 "원하는 값=실주문 값") ──

    @Test
    fun `(tick-1) 미정렬 LIMIT 주문단가는 KIS body에서 KRX 호가단위로 floor 정렬된다`() {
        val cred = stubCredential()
        // 12,345원 = 5,000~19,999 구간(10원 단위) → 12,340으로 내림 정렬돼야 KIS가 접수.
        val intent = buildIntent(OrderSide.BUY, OrderPriceType.LIMIT, limit = BigDecimal("12345"))

        val body = adapter.buildOrderCashBody(cred, intent)

        assertThat(body["ORD_UNPR"]).isEqualTo("12340")
    }

    @Test
    fun `(tick-2) 이미 호가단위에 맞는 LIMIT 주문단가는 변경 없이 그대로 전달`() {
        val cred = stubCredential()
        // 49,950원 = 20,000~49,999 구간(50원 단위) 정확히 정렬됨 → 불변.
        val intent = buildIntent(OrderSide.SELL, OrderPriceType.LIMIT, limit = BigDecimal("49950"))

        val body = adapter.buildOrderCashBody(cred, intent)

        assertThat(body["ORD_UNPR"]).isEqualTo("49950")
    }

    @Test
    fun `(tick-3) 구간 경계 직하 가격은 하위 구간 호가단위로 정렬 (49,990 → 49,950)`() {
        val cred = stubCredential()
        // 49,990원: 50,000 직하이므로 50원 단위 구간(20,000~49,999). 100원 단위로 과도 하향(49,900) 금지.
        val intent = buildIntent(OrderSide.BUY, OrderPriceType.LIMIT, limit = BigDecimal("49990"))

        val body = adapter.buildOrderCashBody(cred, intent)

        assertThat(body["ORD_UNPR"]).isEqualTo("49950")
    }

    @Test
    fun `(tick-4) MARKET 주문은 tick 정렬 무관하게 ORD_UNPR=0`() {
        val cred = stubCredential()
        val intent = buildIntent(OrderSide.BUY, OrderPriceType.MARKET, limit = null)

        val body = adapter.buildOrderCashBody(cred, intent)

        assertThat(body["ORD_UNPR"]).isEqualTo("0")
    }

    @Test
    fun `hashkey rate-limit timeout은 order-cash 미전송 REJECT 사유로 반환`() {
        every { credentialRepository.findAllByAccountType("MOCK") } returns listOf(stubCredential())
        every { kisAuthAdapter.getAccessToken("12345678", AccountType.MOCK) } returns "token"
        every { secretCipher.decrypt(any()) } returns "plain".toByteArray()
        every {
            kisHashKeyAdapter.issueHashKey(any(), any(), any(), any(), AccountType.MOCK)
        } throws KisRateLimitTimeoutException(KisCallPriority.USER_PATH, 3000L)

        val result =
            adapter.submit(
                buildIntent(OrderSide.SELL, OrderPriceType.LIMIT, limit = BigDecimal("78000")),
                TradingMode.MOCK,
            )

        assertThat(result).isInstanceOf(OrderResult.Left::class.java)
        val deny = (result as OrderResult.Left).value
        assertThat(deny).isInstanceOf(DenyReason.KisException::class.java)
        assertThat((deny as DenyReason.KisException).cause)
            .startsWith("ORDER_NOT_SENT:HASHKEY_RATE_LIMIT_TIMEOUT")
        verify(exactly = 0) { kisRateLimiterPort.acquire(any(), any(), any()) }
    }

    private fun stubCredential() =
        ConsumerUserKisCredentialJpaEntity(
            id = ConsumerUserKisCredentialId(userId = "12345678", accountType = "MOCK"),
            accountNumber = "50000000",
            accountPrdtCd = "01",
            appKeyEnc = ByteArray(0),
            secretKeyEnc = ByteArray(0),
        )

    private fun buildIntent(
        side: OrderSide,
        priceType: OrderPriceType,
        limit: BigDecimal?,
    ) = OrderIntent(
        id = 1L,
        userId = "12345678",
        signalId = 42L,
        strategyId = 100L,
        code = "005930",
        side = side,
        priceType = priceType,
        quantity = 10,
        limitPrice = limit,
        idempotencyKey = "42:12345678",
        requestedAt = Instant.parse("2026-05-13T01:00:00Z"),
    )
}
