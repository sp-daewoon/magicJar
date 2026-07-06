package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono

/**
 * KisRestPriceAdapter rate-limit 로그 위생 분기 검증.
 *
 * 무tick fallback seed(seedTodayCandle→fetchPriceMetrics)·polling(fetchPrice)이 저유동성 종목에서 KIS 공유
 * TPS(EGW00201)를 반복 초과할 때, RateLimit 분류는 onTpsExceeded WARN 1건만 남기고 ERROR 중복을 금지한다.
 * RateLimit 외 실패만 ERROR로 남긴다. 예외는 두 경우 모두 caller로 rethrow(동작 무변경).
 */
class KisRestPriceAdapterRateLimitLogTest {
    private val rateLimiter = mockk<KisRateLimiterPort>(relaxed = true)
    private val logger = LoggerFactory.getLogger(KisRestPriceAdapter::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun adapter(bodyJson: String): KisRestPriceAdapter {
        val exchange =
            ExchangeFunction { _ ->
                Mono.just(
                    ClientResponse
                        .create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header("Content-Type", "application/json")
                        .body(bodyJson)
                        .build(),
                )
            }
        val webClient = WebClient.builder().exchangeFunction(exchange).build()

        val credRepo = mockk<ConsumerUserKisCredentialJpaRepository>()
        every { credRepo.findAllByAccountType(AccountType.REAL.name) } returns
            listOf(
                ConsumerUserKisCredentialJpaEntity(
                    id = ConsumerUserKisCredentialId(userId = "1", accountType = AccountType.REAL.name),
                    accountNumber = "00000000",
                    appKeyEnc = "appkey".toByteArray(),
                    secretKeyEnc = "secret".toByteArray(),
                ),
            )

        val auth = mockk<KisAuthAdapter>()
        every { auth.getAccessToken("1", AccountType.REAL) } returns "token"

        return KisRestPriceAdapter(
            webClient = webClient,
            kisAuthAdapter = auth,
            kisRateLimiterPort = rateLimiter,
            credentialRepository = credRepo,
            secretCipher = PassthroughCipher(),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )
    }

    @Test
    fun `RateLimit(EGW00201) 실패는 onTpsExceeded WARN 1건만 남기고 ERROR를 남기지 않는다`() {
        val body = """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}"""
        val svc = adapter(body)

        assertThatThrownBy { svc.fetchPrice(userId = "1", code = "071970") }
            .isInstanceOf(WebClientResponseException::class.java)

        verify(exactly = 1) { rateLimiter.onTpsExceeded() }
        val errors = appender.list.filter { it.level == Level.ERROR }
        val warns = appender.list.filter { it.level == Level.WARN }
        assertThat(errors).isEmpty()
        assertThat(warns).anyMatch { it.formattedMessage.contains("Rate Limit") }
    }

    @Test
    fun `RateLimit 외 실패는 ERROR를 남기고 onTpsExceeded를 호출하지 않는다`() {
        val body = """{"rt_cd":"1","msg_cd":"OPSQ0001","msg1":"기타 서버 오류"}"""
        val svc = adapter(body)

        assertThatThrownBy { svc.fetchPrice(userId = "1", code = "071970") }
            .isInstanceOf(WebClientResponseException::class.java)

        verify(exactly = 0) { rateLimiter.onTpsExceeded() }
        val errors = appender.list.filter { it.level == Level.ERROR }
        assertThat(errors).anyMatch { it.formattedMessage.contains("KIS inquire-price 실패") }
        assertThat(appender.list.filter { it.level == Level.WARN }).noneMatch {
            it.formattedMessage.contains("Rate Limit")
        }
    }

    private class PassthroughCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(cipher: ByteArray): ByteArray = cipher
    }
}
