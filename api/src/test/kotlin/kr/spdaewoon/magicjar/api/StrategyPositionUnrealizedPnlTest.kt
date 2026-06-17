package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.StrategiesController
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.api.service.market.LivePriceCacheReader
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Instant

/**
 * 전략 포지션 미실현 평가손익(currentPrice·priceSource·unrealizedPnl·unrealizedPnlPct) 검증.
 *
 * 폴백 체인:
 * - LIVE  : WS 시세 캐시(quote:last) hit → 그 가격으로 평가손익, priceSource=LIVE
 * - CLOSE : WS 캐시 miss + 캔들 있음 → 최신 캔들 종가로 평가손익, priceSource=CLOSE (장전·마감 "시세 대기" 방지)
 * - null  : WS·캔들 모두 miss → 3필드 + priceSource 전부 null (새 KIS 호출 0 — feedback_no_mock_data)
 *
 * 룰 5: LLM provider import 0건.
 */
class StrategyPositionUnrealizedPnlTest {
    private val strategyService = mock<StrategyService>()
    private val kisYamlDslParser = mock<KisYamlDslParser>()
    private val instrumentJpaRepository = mock<InstrumentJpaRepository>()
    private val positionRepo = mock<StrategyPositionReadJpaRepository>()
    private val livePriceCacheReader = mock<LivePriceCacheReader>()
    private val strategyStartService = mock<kr.spdaewoon.magicjar.api.service.strategy.StrategyStartService>()
    private val favoritePort = mock<kr.spdaewoon.magicjar.domain.strategy.port.StrategyFavoritePort>()

    private val controller =
        StrategiesController(
            strategyService = strategyService,
            kisYamlDslParser = kisYamlDslParser,
            instrumentJpaRepository = instrumentJpaRepository,
            strategyPositionReadJpaRepository = positionRepo,
            livePriceCacheReader = livePriceCacheReader,
            strategyStartService = strategyStartService,
            favoritePort = favoritePort,
        )

    private fun entity(
        code: String = "005930",
        quantity: Int = 10,
        avgPrice: BigDecimal = BigDecimal("70000.0000"),
    ) = StrategyPositionReadJpaEntity(
        id = 1L,
        userId = "1",
        strategyId = 1L,
        code = code,
        quantity = quantity,
        avgPrice = avgPrice,
        filledLots = 2,
        realizedPnl = BigDecimal("15000.0000"),
    )

    private fun live(price: String) = LivePriceCacheReader.ResolvedPrice(BigDecimal(price), LivePriceCacheReader.PriceSource.LIVE)

    private fun close(price: String) = LivePriceCacheReader.ResolvedPrice(BigDecimal(price), LivePriceCacheReader.PriceSource.CLOSE)

    @Test
    fun `LIVE hit이면 현재가·평가손익·수익률을 계산하고 priceSource=LIVE`() {
        whenever(strategyService.findById(1L)).thenReturn(mock<Strategy>())
        whenever(positionRepo.findByStrategyId(1L)).thenReturn(listOf(entity()))
        whenever(instrumentJpaRepository.findAllById(any())).thenReturn(emptyList())
        // 현재가 71,500 — 평단 70,000 · 수량 10
        whenever(livePriceCacheReader.resolvePrices(any()))
            .thenReturn(mapOf("005930" to live("71500.0000")))

        val result = controller.getPosition(1L)

        assertThat(result).hasSize(1)
        val item = result.first()
        assertThat(item.currentPrice).isEqualByComparingTo("71500.0000")
        assertThat(item.priceSource).isEqualTo("LIVE")
        // (71500 − 70000) × 10 = 15000
        assertThat(item.unrealizedPnl).isEqualByComparingTo("15000")
        // 15000 / (70000 × 10) × 100 = 2.142857%
        assertThat(item.unrealizedPnlPct).isEqualByComparingTo("2.142900")
        // 기존 필드 불변 확인
        assertThat(item.quantity).isEqualTo(10)
        assertThat(item.avgPrice).isEqualByComparingTo("70000.0000")
        assertThat(item.realizedPnl).isEqualByComparingTo("15000.0000")
    }

    @Test
    fun `현재가가 평단보다 낮으면 평가손익이 음수다`() {
        whenever(strategyService.findById(1L)).thenReturn(mock<Strategy>())
        whenever(positionRepo.findByStrategyId(1L)).thenReturn(listOf(entity()))
        whenever(instrumentJpaRepository.findAllById(any())).thenReturn(emptyList())
        whenever(livePriceCacheReader.resolvePrices(any()))
            .thenReturn(mapOf("005930" to live("68000.0000")))

        val item = controller.getPosition(1L).first()

        // (68000 − 70000) × 10 = −20000
        assertThat(item.unrealizedPnl).isEqualByComparingTo("-20000")
        assertThat(item.unrealizedPnlPct?.signum()).isEqualTo(-1)
    }

    @Test
    fun `WS miss·캔들 있으면 종가로 평가손익을 계산하고 priceSource=CLOSE`() {
        whenever(strategyService.findById(1L)).thenReturn(mock<Strategy>())
        whenever(positionRepo.findByStrategyId(1L)).thenReturn(listOf(entity()))
        whenever(instrumentJpaRepository.findAllById(any())).thenReturn(emptyList())
        // WS 캐시 만료 → 최신 캔들 종가 71,000 으로 폴백
        whenever(livePriceCacheReader.resolvePrices(any()))
            .thenReturn(mapOf("005930" to close("71000.0000")))

        val item = controller.getPosition(1L).first()

        assertThat(item.currentPrice).isEqualByComparingTo("71000.0000")
        assertThat(item.priceSource).isEqualTo("CLOSE")
        // (71000 − 70000) × 10 = 10000 — "시세 대기" 대신 종가로 평가손익이 보인다
        assertThat(item.unrealizedPnl).isEqualByComparingTo("10000")
        assertThat(item.unrealizedPnlPct).isEqualByComparingTo("1.428600")
    }

    @Test
    fun `WS·캔들 모두 miss이면 현재가·priceSource·평가손익·수익률이 전부 null이다`() {
        whenever(strategyService.findById(1L)).thenReturn(mock<Strategy>())
        whenever(positionRepo.findByStrategyId(1L)).thenReturn(listOf(entity()))
        whenever(instrumentJpaRepository.findAllById(any())).thenReturn(emptyList())
        // miss → map 에 code 없음
        whenever(livePriceCacheReader.resolvePrices(any())).thenReturn(emptyMap())

        val item = controller.getPosition(1L).first()

        assertThat(item.currentPrice).isNull()
        assertThat(item.priceSource).isNull()
        assertThat(item.unrealizedPnl).isNull()
        assertThat(item.unrealizedPnlPct).isNull()
        // 기존 필드는 정상
        assertThat(item.quantity).isEqualTo(10)
    }

    @Test
    fun `취득원가가 0이면(수량 0 청산) 수익률은 null이다`() {
        whenever(strategyService.findById(1L)).thenReturn(mock<Strategy>())
        whenever(positionRepo.findByStrategyId(1L)).thenReturn(listOf(entity(quantity = 0)))
        whenever(instrumentJpaRepository.findAllById(any())).thenReturn(emptyList())
        whenever(livePriceCacheReader.resolvePrices(any()))
            .thenReturn(mapOf("005930" to live("71500.0000")))

        val item = controller.getPosition(1L).first()

        // 수량 0 → 평가손익 0, 취득원가 0 → 수익률 null
        assertThat(item.unrealizedPnl).isEqualByComparingTo("0")
        assertThat(item.unrealizedPnlPct).isNull()
    }

    @Test
    fun `LivePriceCacheReader latestPrice는 cache hit시 price를 cache miss시 null을 반환한다`() {
        val redisTemplate = mock<StringRedisTemplate>()
        val valueOps = mock<ValueOperations<String, String>>()
        val candleRepo = mock<CandleJpaRepository>()
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        val reader = LivePriceCacheReader(redisTemplate, ObjectMapper(), candleRepo)

        // hit
        whenever(valueOps.get(eq(RedisKeyRegistry.Market.quoteLast("005930"))))
            .thenReturn("""{"price":"71500.0000","volume":100,"ts":"2026-06-17T00:00:00Z","source":"live"}""")
        assertThat(reader.latestPrice("005930")).isEqualByComparingTo("71500.0000")

        // miss (LIVE 만 — latestPrice 는 폴백 없음)
        whenever(valueOps.get(eq(RedisKeyRegistry.Market.quoteLast("000660")))).thenReturn(null)
        assertThat(reader.latestPrice("000660")).isNull()

        // 일괄 LIVE read — hit 종목만 map 에 포함
        val map = reader.latestPrices(listOf("005930", "000660"))
        assertThat(map).containsOnlyKeys("005930")
        assertThat(map["000660"]).isNull()
    }

    @Test
    fun `resolvePrice는 LIVE hit이면 LIVE, miss면 캔들 종가로 CLOSE, 둘 다 없으면 null`() {
        val redisTemplate = mock<StringRedisTemplate>()
        val valueOps = mock<ValueOperations<String, String>>()
        val candleRepo = mock<CandleJpaRepository>()
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        val reader = LivePriceCacheReader(redisTemplate, ObjectMapper(), candleRepo)

        // (1) LIVE hit
        whenever(valueOps.get(eq(RedisKeyRegistry.Market.quoteLast("005930"))))
            .thenReturn("""{"price":"71500.0000"}""")
        val r1 = reader.resolvePrice("005930")
        assertThat(r1?.source).isEqualTo(LivePriceCacheReader.PriceSource.LIVE)
        assertThat(r1?.price).isEqualByComparingTo("71500.0000")

        // (2) WS miss → 1m 캔들 종가 폴백 → CLOSE
        whenever(valueOps.get(eq(RedisKeyRegistry.Market.quoteLast("000660")))).thenReturn(null)
        whenever(candleRepo.findByCodeAndIntervalLatest(eq("000660"), eq("1m"), any<Pageable>()))
            .thenReturn(listOf(candle("000660", "1m", "123400.0000")))
        val r2 = reader.resolvePrice("000660")
        assertThat(r2?.source).isEqualTo(LivePriceCacheReader.PriceSource.CLOSE)
        assertThat(r2?.price).isEqualByComparingTo("123400.0000")

        // (3) WS miss + 1m 없음 → 일봉(1d) 종가 폴백 → CLOSE
        whenever(valueOps.get(eq(RedisKeyRegistry.Market.quoteLast("035720")))).thenReturn(null)
        whenever(candleRepo.findByCodeAndIntervalLatest(eq("035720"), eq("1m"), any<Pageable>()))
            .thenReturn(emptyList())
        whenever(candleRepo.findByCodeAndIntervalLatest(eq("035720"), eq("1d"), any<Pageable>()))
            .thenReturn(listOf(candle("035720", "1d", "55500.0000")))
        val r3 = reader.resolvePrice("035720")
        assertThat(r3?.source).isEqualTo(LivePriceCacheReader.PriceSource.CLOSE)
        assertThat(r3?.price).isEqualByComparingTo("55500.0000")

        // (4) WS·캔들 모두 없음 → null
        whenever(valueOps.get(eq(RedisKeyRegistry.Market.quoteLast("999999")))).thenReturn(null)
        whenever(candleRepo.findByCodeAndIntervalLatest(eq("999999"), any(), any<Pageable>()))
            .thenReturn(emptyList())
        assertThat(reader.resolvePrice("999999")).isNull()
    }

    private fun candle(
        code: String,
        interval: String,
        close: String,
    ) = CandleJpaEntity(
        id = 1L,
        code = code,
        interval = interval,
        ts = Instant.parse("2026-06-16T06:00:00Z"),
        closeRaw = BigDecimal(close),
        source = "KIS",
    )
}
