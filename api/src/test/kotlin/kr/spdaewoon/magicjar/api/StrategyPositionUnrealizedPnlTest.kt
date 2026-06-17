package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.StrategiesController
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
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal

/**
 * 전략 포지션 미실현 평가손익(currentPrice·unrealizedPnl·unrealizedPnlPct) 검증.
 *
 * - cache hit → (현재가 − 평단) × 수량 계산, 수익률% 계산
 * - cache miss → 3필드 전부 null (새 KIS 호출 0 — feedback_no_mock_data, F10 정신)
 * - LivePriceCacheReader 자체 hit/miss read 검증
 *
 * 룰 5: LLM provider import 0건.
 */
class StrategyPositionUnrealizedPnlTest {
    private val strategyService = mock<StrategyService>()
    private val kisYamlDslParser = mock<KisYamlDslParser>()
    private val instrumentJpaRepository = mock<InstrumentJpaRepository>()
    private val positionRepo = mock<StrategyPositionReadJpaRepository>()
    private val livePriceCacheReader = mock<LivePriceCacheReader>()

    private val controller =
        StrategiesController(
            strategyService = strategyService,
            kisYamlDslParser = kisYamlDslParser,
            instrumentJpaRepository = instrumentJpaRepository,
            strategyPositionReadJpaRepository = positionRepo,
            livePriceCacheReader = livePriceCacheReader,
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

    @Test
    fun `cache hit이면 현재가·평가손익·수익률을 계산한다`() {
        whenever(strategyService.findById(1L)).thenReturn(mock<Strategy>())
        whenever(positionRepo.findByStrategyId(1L)).thenReturn(listOf(entity()))
        whenever(instrumentJpaRepository.findAllById(any())).thenReturn(emptyList())
        // 현재가 71,500 — 평단 70,000 · 수량 10
        whenever(livePriceCacheReader.latestPrices(any()))
            .thenReturn(mapOf("005930" to BigDecimal("71500.0000")))

        val result = controller.getPosition(1L)

        assertThat(result).hasSize(1)
        val item = result.first()
        assertThat(item.currentPrice).isEqualByComparingTo("71500.0000")
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
    fun `cache hit이고 현재가가 평단보다 낮으면 평가손익이 음수다`() {
        whenever(strategyService.findById(1L)).thenReturn(mock<Strategy>())
        whenever(positionRepo.findByStrategyId(1L)).thenReturn(listOf(entity()))
        whenever(instrumentJpaRepository.findAllById(any())).thenReturn(emptyList())
        whenever(livePriceCacheReader.latestPrices(any()))
            .thenReturn(mapOf("005930" to BigDecimal("68000.0000")))

        val item = controller.getPosition(1L).first()

        // (68000 − 70000) × 10 = −20000
        assertThat(item.unrealizedPnl).isEqualByComparingTo("-20000")
        assertThat(item.unrealizedPnlPct?.signum()).isEqualTo(-1)
    }

    @Test
    fun `cache miss이면 현재가·평가손익·수익률 3필드가 전부 null이다`() {
        whenever(strategyService.findById(1L)).thenReturn(mock<Strategy>())
        whenever(positionRepo.findByStrategyId(1L)).thenReturn(listOf(entity()))
        whenever(instrumentJpaRepository.findAllById(any())).thenReturn(emptyList())
        // miss → map 에 code 없음
        whenever(livePriceCacheReader.latestPrices(any())).thenReturn(emptyMap())

        val item = controller.getPosition(1L).first()

        assertThat(item.currentPrice).isNull()
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
        whenever(livePriceCacheReader.latestPrices(any()))
            .thenReturn(mapOf("005930" to BigDecimal("71500.0000")))

        val item = controller.getPosition(1L).first()

        // 수량 0 → 평가손익 0, 취득원가 0 → 수익률 null
        assertThat(item.unrealizedPnl).isEqualByComparingTo("0")
        assertThat(item.unrealizedPnlPct).isNull()
    }

    @Test
    fun `LivePriceCacheReader는 cache hit시 price를 cache miss시 null을 반환한다`() {
        val redisTemplate = mock<StringRedisTemplate>()
        val valueOps = mock<ValueOperations<String, String>>()
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        val reader = LivePriceCacheReader(redisTemplate, ObjectMapper())

        // hit
        whenever(valueOps.get(eq(RedisKeyRegistry.Market.quoteLast("005930"))))
            .thenReturn("""{"price":"71500.0000","volume":100,"ts":"2026-06-17T00:00:00Z","source":"live"}""")
        assertThat(reader.latestPrice("005930")).isEqualByComparingTo("71500.0000")

        // miss
        whenever(valueOps.get(eq(RedisKeyRegistry.Market.quoteLast("000660")))).thenReturn(null)
        assertThat(reader.latestPrice("000660")).isNull()

        // 일괄 read — hit 종목만 map 에 포함
        val map = reader.latestPrices(listOf("005930", "000660"))
        assertThat(map).containsOnlyKeys("005930")
        assertThat(map["000660"]).isNull()
    }
}
