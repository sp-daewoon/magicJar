package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisQuoteCacheAdapter
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisRestPriceAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

/**
 * WatchlistPollingWorker 단위 테스트 (결함④ fix · 2026-06-15).
 *
 * 검증 목표:
 * 1. polling tick 시 현재가 단건(FHKST01010100 inquire-price) fetch → Redis quote cache 갱신.
 * 2. 더 이상 candle fetch/DB upsert 를 하지 않는다(50페이지 5,000봉 폭주 root cause 제거).
 * 3. REAL 자격증명 부재 시 fetch skip(현재가 endpoint 는 userId 토큰 필요).
 *
 * 배경: 기존 `fetchCandles(ONE_DAY, 1)` 은 count 무시 → 1d max page(50) 페이지네이션으로 종목당 최대
 * 5,000봉을 매 tick 받아 단일 스케줄러 스레드를 점유 → status 발행이 밀려 빨간 배너 false positive 유발.
 */
class WatchlistPollingWorkerTest {
    private val redisQuoteCacheAdapter = mockk<RedisQuoteCacheAdapter>(relaxed = true)
    private val kisRestPriceAdapter = mockk<KisRestPriceAdapter>()
    private val credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>()

    private lateinit var worker: WatchlistPollingWorker

    private val realCredentialEntity =
        ConsumerUserKisCredentialJpaEntity(
            id = ConsumerUserKisCredentialId(userId = "user001", accountType = AccountType.REAL.name),
            accountNumber = "12345678",
            appKeyEnc = ByteArray(0),
            secretKeyEnc = ByteArray(0),
        )

    private fun tickEvent(
        code: String = "005930",
        price: String = "75000",
        volume: Long = 12_000_000L,
    ) = TickEvent(
        code = code,
        price = BigDecimal(price),
        volume = volume,
        ts = Clock.System.now(),
        source = "polling",
        stale = false,
    )

    @BeforeEach
    fun setUp() {
        worker =
            WatchlistPollingWorker(
                redisQuoteCacheAdapter = redisQuoteCacheAdapter,
                kisRestPriceAdapter = kisRestPriceAdapter,
                credentialRepository = credentialRepository,
                interCallDelayMs = 0,
            )
        worker.add("005930")
        worker.start()
    }

    @Test
    fun `polling tick 시 현재가 fetch + Redis quote cache 갱신`() {
        // given
        every { credentialRepository.findAllByAccountType(AccountType.REAL.name) } returns listOf(realCredentialEntity)
        every { kisRestPriceAdapter.fetchPollingPrice("user001", "005930") } returns tickEvent()

        // when
        worker.tick()

        // then — 현재가 endpoint 호출 + Redis 갱신
        verify(exactly = 1) { kisRestPriceAdapter.fetchPollingPrice("user001", "005930") }
        verify(exactly = 1) {
            redisQuoteCacheAdapter.putQuote(
                code = "005930",
                price = BigDecimal("75000"),
                volume = 12_000_000L,
                source = "polling",
            )
        }
    }

    @Test
    fun `REAL 자격증명 부재 시 현재가 fetch skip`() {
        // given — REAL credential 없음
        every { credentialRepository.findAllByAccountType(AccountType.REAL.name) } returns emptyList()

        // when
        worker.tick()

        // then — fetch 자체 미진입
        verify(exactly = 0) { kisRestPriceAdapter.fetchPollingPrice(any(), any()) }
        verify(exactly = 0) { redisQuoteCacheAdapter.putQuote(any(), any(), any(), any()) }
    }

    @Test
    fun `credential userId 빈 문자열이면 skip (DB 결함 방어)`() {
        // given — userId 빈 문자열
        val blankUserEntity =
            ConsumerUserKisCredentialJpaEntity(
                id = ConsumerUserKisCredentialId(userId = "", accountType = AccountType.REAL.name),
                accountNumber = "12345678",
                appKeyEnc = ByteArray(0),
                secretKeyEnc = ByteArray(0),
            )
        every { credentialRepository.findAllByAccountType(AccountType.REAL.name) } returns listOf(blankUserEntity)

        // when
        worker.tick()

        // then
        verify(exactly = 0) { kisRestPriceAdapter.fetchPollingPrice(any(), any()) }
    }

    @Test
    fun `running=false 면 tick skip`() {
        // given
        worker.stop()

        // when
        worker.tick()

        // then — credential 조회조차 안 함
        verify(exactly = 0) { credentialRepository.findAllByAccountType(any()) }
    }

    @Test
    fun `polling tick 은 max-codes-per-tick 만큼 round-robin 으로 조회한다`() {
        // given
        worker.add("000660")
        worker.add("035420")
        every { credentialRepository.findAllByAccountType(AccountType.REAL.name) } returns listOf(realCredentialEntity)
        every { kisRestPriceAdapter.fetchPollingPrice("user001", "000660") } returns tickEvent(code = "000660")
        every { kisRestPriceAdapter.fetchPollingPrice("user001", "005930") } returns tickEvent(code = "005930")
        every { kisRestPriceAdapter.fetchPollingPrice("user001", "035420") } returns tickEvent(code = "035420")

        // when — sorted pool: 000660, 005930, 035420. default max=2.
        worker.tick()
        worker.tick()

        // then — 첫 tick 2개, 둘째 tick 다음 2개(순환)만 조회.
        verify(exactly = 2) { kisRestPriceAdapter.fetchPollingPrice("user001", "000660") }
        verify(exactly = 1) { kisRestPriceAdapter.fetchPollingPrice("user001", "005930") }
        verify(exactly = 1) { kisRestPriceAdapter.fetchPollingPrice("user001", "035420") }
    }

    @Test
    fun `KIS rate-limit 응답 시 남은 slice 를 중단하고 cooldown 동안 다음 tick 을 skip 한다`() {
        // given
        worker.add("000660")
        every { credentialRepository.findAllByAccountType(AccountType.REAL.name) } returns listOf(realCredentialEntity)
        every { kisRestPriceAdapter.fetchPollingPrice("user001", "000660") } throws rateLimitException()
        every { kisRestPriceAdapter.fetchPollingPrice("user001", "005930") } returns tickEvent()

        // when
        worker.tick()
        worker.tick()

        // then — 첫 tick 에 000660 rate-limit 감지 후 005930 미호출, 둘째 tick 은 cooldown 으로 전체 skip.
        verify(exactly = 1) { kisRestPriceAdapter.fetchPollingPrice("user001", "000660") }
        verify(exactly = 0) { kisRestPriceAdapter.fetchPollingPrice("user001", "005930") }
    }

    @Test
    fun `local rate-limit acquire timeout 시 남은 slice 를 중단하고 cooldown 동안 다음 tick 을 skip 한다`() {
        // given
        worker.add("000660")
        every { credentialRepository.findAllByAccountType(AccountType.REAL.name) } returns listOf(realCredentialEntity)
        every { kisRestPriceAdapter.fetchPollingPrice("user001", "000660") } throws
            KisRateLimitTimeoutException(KisCallPriority.INGESTION, timeoutMillis = 3000L)
        every { kisRestPriceAdapter.fetchPollingPrice("user001", "005930") } returns tickEvent()

        // when
        worker.tick()
        worker.tick()

        // then — local limiter timeout 도 KIS quota 압박으로 보고 slice 중단 + cooldown.
        verify(exactly = 1) { kisRestPriceAdapter.fetchPollingPrice("user001", "000660") }
        verify(exactly = 0) { kisRestPriceAdapter.fetchPollingPrice("user001", "005930") }
    }

    private fun rateLimitException(): WebClientResponseException =
        WebClientResponseException.create(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Internal Server Error",
            org.springframework.http.HttpHeaders.EMPTY,
            """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}""".toByteArray(),
            StandardCharsets.UTF_8,
        )
}
