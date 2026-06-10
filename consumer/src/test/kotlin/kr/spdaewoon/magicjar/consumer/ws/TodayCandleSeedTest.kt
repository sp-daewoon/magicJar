package kr.spdaewoon.magicjar.consumer.ws

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisApprovalAdapter
import kr.spdaewoon.magicjar.consumer.order.KisExecutionWsListener
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.PriceMetrics
import kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort
import kr.spdaewoon.magicjar.domain.market.port.QuoteCachePort
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import java.math.BigDecimal
import java.time.Duration

/**
 * 구독 수립 시점 today-Hash 시드(TodayCandleSeedPort) + WS 틱 합류 정합 단위테스트.
 *
 * root cause(늦은/간헐 구독 시 WS 틱만으로는 open=첫 틱·고저·거래량 누락) fix 검증:
 *  - seed 후 open=공식 9시 시가 LOCK, 고/저=공식 baseline 위 확장만, volume=공식 ACML_VOL + WS CNTG_VOL 델타(이중계상 0).
 *
 * 화면(CandleQueryService)·전략(LiveCandleMerger)은 동일 `candle:today:{code}:1d` Hash를 읽으므로
 * 이 Hash 정합이 곧 양쪽 정합이다.
 */
class TodayCandleSeedTest {
    private val approvalAdapter = mockk<KisApprovalAdapter>(relaxed = true)
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val quoteCachePort = mockk<QuoteCachePort>(relaxed = true)
    private val wsClient = mockk<ReactorNettyWebSocketClient>(relaxed = true)
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)
    private val metricsPort = mockk<KisRestPriceMetricsPort>()

    // 인메모리 Redis Hash fake — key별 field map. seed putAll / tick read-modify-write 정합 검증용.
    private val store = HashMap<String, MutableMap<String, String>>()
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var bridge: KisWebSocketBridge

    private val code = "005930"
    private val todayKey = "candle:today:$code:1d"
    private val oneMinuteKey = "candle:current:$code:1m"

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk(relaxed = true)
        val hashOps = mockk<HashOperations<String, String, String>>()
        @Suppress("UNCHECKED_CAST")
        every { redisTemplate.opsForHash<String, String>() } returns hashOps

        val keySlot = slot<String>()
        every { hashOps.entries(capture(keySlot)) } answers {
            HashMap(store[keySlot.captured] ?: emptyMap())
        }
        val putKeySlot = slot<String>()
        val putMapSlot = slot<Map<String, String>>()
        every { hashOps.putAll(capture(putKeySlot), capture(putMapSlot)) } answers {
            store.getOrPut(putKeySlot.captured) { HashMap() }.putAll(putMapSlot.captured)
        }
        every { redisTemplate.expire(any<String>(), any<Duration>()) } returns true

        val execProvider = mockk<ObjectProvider<KisExecutionWsListener>>(relaxed = true)
        every { execProvider.getIfAvailable() } returns null
        val metricsProvider = mockk<ObjectProvider<KisRestPriceMetricsPort>>(relaxed = true)
        every { metricsProvider.getIfAvailable() } returns metricsPort

        bridge =
            KisWebSocketBridge(
                approvalAdapter = approvalAdapter,
                redisTemplate = redisTemplate,
                kafkaTemplate = kafkaTemplate,
                objectMapper = objectMapper,
                quoteCachePort = quoteCachePort,
                wsClient = wsClient,
                wsUrlProd = "ws://localhost:21000",
                wsUrlVps = "ws://localhost:31000",
                magicjarMode = "REAL",
                defaultUserId = "user001",
                executionListenerProvider = execProvider,
                systemEventLogger = systemEventLogger,
                priceMetricsPortProvider = metricsProvider,
                credentialRepositoryProvider =
                    mockk<ObjectProvider<ConsumerUserKisCredentialJpaRepository>>(
                        relaxed = true,
                    ).also {
                        every {
                            it.ifAvailable
                        } returns null
                    },
                todayCandleSeedPreOpenGuardEnabled = false,
            )
    }

    private fun officialMetrics(
        open: String,
        high: String,
        low: String,
        close: String,
        vol: Long,
    ) = PriceMetrics(
        per = null,
        pbr = null,
        eps = null,
        bps = null,
        marketCap = null,
        industry = null,
        name = null,
        market = null,
        currentPrice = BigDecimal(close),
        previousClose = null,
        priceChange = null,
        changeRate = null,
        changeSign = null,
        high = BigDecimal(high),
        low = BigDecimal(low),
        open = BigDecimal(open),
        volume = vol,
    )

    /** private updateLiveCandles(tick) reflection 호출 — WS 틱 합류 path 재현. */
    private fun feedTick(
        price: String,
        volume: Long,
    ) {
        val tick =
            TickEvent(
                code = code,
                price = BigDecimal(price),
                volume = volume,
                ts = Clock.System.now(),
                source = "live",
            )
        val m = KisWebSocketBridge::class.java.getDeclaredMethod("updateLiveCandles", TickEvent::class.java)
        m.isAccessible = true
        m.invoke(bridge, tick)
    }

    @Test
    fun `seedTodayCandle은 공식 KIS 당일 OHLCV로 Hash를 시드한다`() {
        every { metricsPort.fetchPriceMetrics("user001", code) } returns
            officialMetrics(open = "70000", high = "72000", low = "69500", close = "71000", vol = 1_234_567L)

        bridge.seedTodayCandle(code)

        val h = store[todayKey]!!
        assertEquals("70000", h["open"], "공식 9시 시가")
        assertEquals("72000", h["high"], "공식 당일 고가")
        assertEquals("69500", h["low"], "공식 당일 저가")
        assertEquals("71000", h["close"], "공식 현재가")
        assertEquals("1234567", h["volume"], "공식 누적거래량(ACML_VOL)")
        assertEquals("true", h["seeded"])
        assertEquals("1234567", h["acmlBase"])
        assertEquals("0", h["wsVolDelta"])
    }

    @Test
    fun `초기 틱이 누락된 늦은 구독에도 open은 공식 시가로 고정된다`() {
        every { metricsPort.fetchPriceMetrics("user001", code) } returns
            officialMetrics(open = "70000", high = "72000", low = "69500", close = "71000", vol = 1_000_000L)
        bridge.seedTodayCandle(code)

        // 시드 후 도착한 WS 틱(현재가 71500) — open은 절대 덮어쓰지 않아야 한다.
        feedTick(price = "71500", volume = 100L)

        val h = store[todayKey]!!
        assertEquals("70000", h["open"], "WS 틱이 open을 덮어쓰면 안 됨(공식 9시 시가 LOCK)")
        assertEquals("71500", h["close"], "close는 최신 틱")
    }

    @Test
    fun `시드 후 WS 틱은 공식 고가를 초과할 때만 고가를 확장한다`() {
        every { metricsPort.fetchPriceMetrics("user001", code) } returns
            officialMetrics(open = "70000", high = "72000", low = "69500", close = "71000", vol = 1_000_000L)
        bridge.seedTodayCandle(code)

        feedTick(price = "71800", volume = 10L) // 공식 고가 미만 → 확장 안 함
        assertEquals("72000", store[todayKey]!!["high"])
        assertEquals("69500", store[todayKey]!!["low"], "저가도 공식값 유지")

        feedTick(price = "72500", volume = 10L) // 공식 고가 초과 → 확장
        assertEquals("72500", store[todayKey]!!["high"], "공식 고가 초과 시에만 확장")

        feedTick(price = "69000", volume = 10L) // 공식 저가 미만 → 확장
        assertEquals("69000", store[todayKey]!!["low"], "공식 저가 미만 시에만 확장")
    }

    @Test
    fun `volume은 공식 ACML_VOL을 baseline으로 WS 체결량만 더한다(이중계상 0)`() {
        every { metricsPort.fetchPriceMetrics("user001", code) } returns
            officialMetrics(open = "70000", high = "72000", low = "69500", close = "71000", vol = 1_000_000L)
        bridge.seedTodayCandle(code)

        feedTick(price = "71000", volume = 30L)
        feedTick(price = "71100", volume = 70L)

        val h = store[todayKey]!!
        // 1,000,000(공식 누적) + 30 + 70 = 1,000,100. 공식값을 다시 누적(이중계상)하지 않는다.
        assertEquals("1000100", h["volume"])
        assertEquals("100", h["wsVolDelta"], "시드 이후 CNTG_VOL 누적 델타")
        assertEquals("1000000", h["acmlBase"], "공식 ACML_VOL baseline 불변")
    }

    @Test
    fun `미시드 종목은 1d tick-only 캔들을 만들지 않고 1m 누적은 유지한다(graceful)`() {
        // 시드 없이 틱만 — 1d는 open=첫 틱인 임시 일봉을 만들지 않는다.
        // 1m 등 intraday current candle 누적은 기존 동작을 유지한다.
        feedTick(price = "71000", volume = 40L)
        feedTick(price = "71200", volume = 60L)

        assertEquals(null, store[todayKey], "미시드 1d: 현재가 하나로 OHLC를 합성하지 않음")
        val h = store[oneMinuteKey]!!
        assertEquals("71000", h["open"], "미시드 1m: 첫 틱이 open")
        assertEquals("100", h["volume"], "미시드: CNTG_VOL 단순 누적")
        assertEquals(null, h["seeded"])
    }

    @Test
    fun `subscribe는 WS tick 송신 전에 공식 당일 OHLCV seed를 시도한다`() {
        every { approvalAdapter.getApprovalKey(any(), any()) } returns "mock-approval-key"
        every { metricsPort.fetchPriceMetrics("user001", code) } returns
            officialMetrics(open = "70000", high = "72000", low = "69500", close = "71000", vol = 1_234_567L)

        bridge.subscribe(listOf(code))

        val h = store[todayKey]!!
        assertEquals("70000", h["open"])
        assertEquals("72000", h["high"])
        assertEquals("69500", h["low"])
        assertEquals("71000", h["close"])
        assertEquals("true", h["seeded"])
    }

    @Test
    fun `공식 OHLC 결손 시 시드를 건너뛴다(mock 합성 금지)`() {
        every { metricsPort.fetchPriceMetrics("user001", code) } returns
            officialMetrics(open = "70000", high = "72000", low = "69500", close = "71000", vol = 1L)
                .copy(open = null)

        bridge.seedTodayCandle(code)

        assertEquals(null, store[todayKey], "공식 시가 결손 → Hash 시드 skip(부분 합성 금지)")
    }
}
