package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisQuoteCacheAdapter
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * WatchlistPollingWorker 단위 테스트.
 *
 * 검증 목표:
 * 1. polling tick 시 Redis quote cache 갱신 + DB candle upsert(saveAll) 호출
 * 2. 동일 ts 재호출(idempotent) — saveAll 매번 호출 (멱등성은 insertIgnore SQL 수준에서 보장,
 *    worker는 항상 saveAll 호출해야 함)
 *
 * hotfix 배경: KIS WS 끊김 시 REST fallback candle 미영속 결함 (2026-05-15).
 */
class WatchlistPollingWorkerTest {
    private val redisQuoteCacheAdapter = mockk<RedisQuoteCacheAdapter>(relaxed = true)
    private val kisRestPort = mockk<KisRestPort>()
    private val candleRepository = mockk<CandleRepository>(relaxed = true)

    private lateinit var worker: WatchlistPollingWorker

    private val sampleCandle =
        Candle(
            id = null,
            code = "005930",
            interval = CandleInterval.ONE_DAY,
            ts = Instant.fromEpochSeconds(1_747_267_200L), // 2026-05-15 00:00 KST
            openRaw = BigDecimal("74000"),
            highRaw = BigDecimal("75500"),
            lowRaw = BigDecimal("73500"),
            closeRaw = BigDecimal("75000"),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 12_000_000L,
            source = "kis",
        )

    @BeforeEach
    fun setUp() {
        worker =
            WatchlistPollingWorker(
                redisQuoteCacheAdapter = redisQuoteCacheAdapter,
                kisRestPort = kisRestPort,
                candleRepository = candleRepository,
            )
        worker.add("005930")
        worker.start()
    }

    @Test
    fun `polling tick 시 Redis quote cache 갱신 + DB candle saveAll 호출`() {
        // given
        every { kisRestPort.fetchCandles("005930", CandleInterval.ONE_DAY, 1) } returns listOf(sampleCandle)
        every { candleRepository.saveAll(any()) } returns 1

        // when
        worker.tick()

        // then — Redis 갱신
        verify(exactly = 1) {
            redisQuoteCacheAdapter.putQuote(
                code = "005930",
                price = BigDecimal("75000"),
                volume = 12_000_000L,
                source = "polling",
            )
        }
        // then — DB candle upsert
        verify(exactly = 1) { candleRepository.saveAll(listOf(sampleCandle)) }
    }

    @Test
    fun `동일 ts 재호출 시 saveAll 매번 호출 — 멱등성은 insertIgnore SQL 수준 보장`() {
        // given — 두 번 연속 동일 candle 반환
        every { kisRestPort.fetchCandles("005930", CandleInterval.ONE_DAY, 1) } returns listOf(sampleCandle)
        every { candleRepository.saveAll(any()) } returnsMany listOf(1, 0) // 첫 번째 INSERT, 두 번째 DO NOTHING

        // when — tick 2회
        worker.tick()
        worker.tick()

        // then — saveAll 2회 호출 (worker는 항상 호출, insertIgnore SQL이 중복 처리)
        verify(exactly = 2) { candleRepository.saveAll(listOf(sampleCandle)) }
    }
}
