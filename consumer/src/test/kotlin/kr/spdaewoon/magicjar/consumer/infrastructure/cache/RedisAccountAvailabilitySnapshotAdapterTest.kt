package kr.spdaewoon.magicjar.consumer.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.account.port.AccountAvailabilitySnapshot
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class RedisAccountAvailabilitySnapshotAdapterTest {
    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val adapter = RedisAccountAvailabilitySnapshotAdapter(redisTemplate, objectMapper, ttlSeconds = 900)

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForValue() } returns valueOps
    }

    @Test
    fun `snapshot을 계좌 단위 key에서 읽는다`() {
        every { valueOps.get("account:availability:REAL:user1") } returns objectMapper.writeValueAsString(snapshot(1000))

        val result = adapter.read("user1", TradingMode.REAL)

        assertThat(result?.cashD2).isEqualByComparingTo(BigDecimal("1000000"))
        assertThat(result?.sellableQty("005930")).isEqualTo(7)
    }

    @Test
    fun `putIfNewer는 오래된 응답을 LWW로 버린다`() {
        every { valueOps.get("account:availability:REAL:user1") } returns objectMapper.writeValueAsString(snapshot(2000))

        val stored = adapter.putIfNewer(snapshot(1000))

        assertThat(stored).isFalse()
        verify(exactly = 0) { valueOps.set(any<String>(), any<String>(), any<Duration>()) }
    }

    @Test
    fun `putIfNewer는 최신 응답을 저장하고 TTL을 건다`() {
        every { valueOps.get("account:availability:REAL:user1") } returns objectMapper.writeValueAsString(snapshot(1000))
        val jsonSlot = slot<String>()

        val stored = adapter.putIfNewer(snapshot(2000))

        assertThat(stored).isTrue()
        verify { valueOps.set("account:availability:REAL:user1", capture(jsonSlot), Duration.ofSeconds(900)) }
        val saved = objectMapper.readValue(jsonSlot.captured, AccountAvailabilitySnapshot::class.java)
        assertThat(saved.versionMillis).isEqualTo(2000)
    }

    private fun snapshot(versionMillis: Long) =
        AccountAvailabilitySnapshot(
            userId = "user1",
            mode = TradingMode.REAL,
            cashBalance = BigDecimal("1000000"),
            cashD1 = BigDecimal("1000000"),
            cashD2 = BigDecimal("1000000"),
            netAssetValue = BigDecimal("5000000"),
            holdingQtyByCode = mapOf("005930" to 10),
            sellableQtyByCode = mapOf("005930" to 7),
            versionMillis = versionMillis,
            refreshedAt = Instant.parse("2026-06-12T01:00:00Z"),
        )
}
