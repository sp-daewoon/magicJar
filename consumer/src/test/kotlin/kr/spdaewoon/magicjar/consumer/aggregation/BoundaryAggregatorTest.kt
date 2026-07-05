package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.cache.RedisCandleCache
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaRepository
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class BoundaryAggregatorTest {
    private val candleJpaRepository: ConsumerCandleJpaRepository = mockk(relaxed = true)
    private val redisCandleCache: RedisCandleCache = mockk(relaxed = true)
    private val kafkaTemplate: KafkaTemplate<String, String> = mockk(relaxed = true)
    private val sut =
        BoundaryAggregator(
            candleJpaRepository = candleJpaRepository,
            redisCandleCache = redisCandleCache,
            kafkaTemplate = kafkaTemplate,
            objectMapper = ObjectMapper(),
        )

    private val kst = ZoneId.of("Asia/Seoul")

    @Test
    fun `12h boundary uses full NXT integrated 720 one-minute candles`() {
        val candles =
            (0 until 720).map { i ->
                val minute = 8 * 60 + i
                val price = BigDecimal(100 + i)
                ConsumerCandleJpaEntity(
                    code = "005930",
                    interval = "1m",
                    ts = kstInstant(minute / 60, minute % 60),
                    openRaw = price,
                    highRaw = price,
                    lowRaw = price,
                    closeRaw = price,
                    volume = 1L,
                    source = "KIS",
                )
            }

        // 감사 §2-① 전환 반영 — 풀스캔 findByCodeAndIntervalOrderByTsAsc → boundary 범위쿼리.
        // DB range 쿼리가 window 내 candle만 반환하는 것을 mock 으로 재현(요청 [start,close] 필터).
        every {
            candleJpaRepository.findByCodeAndIntervalAndTsBetweenOrderByTsAsc("005930", "1m", any(), any())
        } answers {
            val start = arg<Instant>(2)
            val end = arg<Instant>(3)
            candles.filter { !it.ts.isBefore(start) && !it.ts.isAfter(end) }
        }
        every {
            candleJpaRepository.insertOrUpdateIfGapfill(
                code = any(),
                interval = any(),
                ts = any(),
                openRaw = any(),
                highRaw = any(),
                lowRaw = any(),
                closeRaw = any(),
                volume = any(),
                source = any(),
            )
        } returns 1

        sut.onTickClosed("005930", kstInstant(19, 59))

        verify {
            candleJpaRepository.insertOrUpdateIfGapfill(
                code = "005930",
                interval = "12h",
                ts = kstInstant(8, 0),
                openRaw = BigDecimal("100"),
                highRaw = BigDecimal("819"),
                lowRaw = BigDecimal("100"),
                closeRaw = BigDecimal("819"),
                volume = 720L,
                source = "KIS",
            )
        }
    }

    private fun kstInstant(
        hour: Int,
        minute: Int,
    ): Instant = LocalDateTime.of(2026, 5, 11, hour, minute).atZone(kst).toInstant()
}
