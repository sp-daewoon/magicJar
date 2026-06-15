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
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

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
            )
        worker.add("005930")
        worker.start()
    }

    @Test
    fun `polling tick 시 현재가 fetch + Redis quote cache 갱신`() {
        // given
        every { credentialRepository.findAllByAccountType(AccountType.REAL.name) } returns listOf(realCredentialEntity)
        every { kisRestPriceAdapter.fetchPrice("user001", "005930") } returns tickEvent()

        // when
        worker.tick()

        // then — 현재가 endpoint 호출 + Redis 갱신
        verify(exactly = 1) { kisRestPriceAdapter.fetchPrice("user001", "005930") }
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
        verify(exactly = 0) { kisRestPriceAdapter.fetchPrice(any(), any()) }
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
        verify(exactly = 0) { kisRestPriceAdapter.fetchPrice(any(), any()) }
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
}
