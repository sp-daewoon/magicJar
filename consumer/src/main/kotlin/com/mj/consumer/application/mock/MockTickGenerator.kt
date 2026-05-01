package com.mj.consumer.application.mock

import com.mj.consumer.messaging.producer.TickPublisher
import com.mj.domain.market.event.TickEvent
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mock tick 발행기. ConditionalOnProperty로 명시 활성화 시에만 빈 등록.
 *
 * 활성화: yml에 `magicjar.market.mock-tick.enabled=true` 또는 환경변수
 *        `MAGICJAR_MARKET_MOCKTICK_ENABLED=true`.
 *
 * 동작:
 *   - 설정된 symbols 각각에 대해 base price + sin·random 합성으로 가격 계산
 *   - intervalMs(기본 1초)마다 `TickPublisher.publish(TickEvent(...))` 호출
 *   - 발행된 이벤트는 Kafka `market.tick.v1` → api `TickStompFanoutListener` →
 *     STOMP `/topic/market.tick.{code}` → frontend
 *
 * 운영 시 KIS WebSocket 어댑터로 대체 (`magicjar.market.mock-tick.enabled=false`).
 */
@Component
@ConditionalOnProperty(prefix = "magicjar.market.mock-tick", name = ["enabled"], havingValue = "true")
class MockTickGenerator(
    private val publisher: TickPublisher,
    private val props: MockTickProperties,
) {
    private val log = LoggerFactory.getLogger(MockTickGenerator::class.java)

    /** 종목별 마지막 가격 (이전 가격에서 점진 변동). */
    private val lastPrice = ConcurrentHashMap<String, Long>()

    /** tick 카운터 — sin 위상 + 거래량 시드. */
    @Volatile
    private var tickCount: Long = 0

    @PostConstruct
    fun init() {
        log.info(
            "MockTickGenerator 활성 — symbols={}, intervalMs={}, volatility=±{}%",
            props.symbols,
            props.intervalMs,
            props.volatilityPercent,
        )
        // 시작 가격 시드
        props.symbols.forEach { code ->
            lastPrice[code] = props.basePrices[code] ?: 60_000L
        }
    }

    @Scheduled(fixedDelayString = "\${magicjar.market.mock-tick.interval-ms:1000}")
    fun emit() {
        if (props.symbols.isEmpty()) return
        val now = Instant.now()
        val n = ++tickCount

        for (code in props.symbols) {
            val base = props.basePrices[code] ?: 60_000L
            val prev = lastPrice[code] ?: base
            val newPrice = nextPrice(prev, base, n.toInt(), code.hashCode())
            lastPrice[code] = newPrice

            val volume = baseVolume(code) + Random.nextLong(0, baseVolume(code) / 2 + 1)

            publisher.publish(
                TickEvent(
                    code = code,
                    at = now,
                    price = BigDecimal(newPrice),
                    volume = volume,
                    source = "MOCK",
                ),
            )
        }
    }

    /**
     * 가격 합성: 이전 가격에서 sin(시간) + 작은 랜덤 노이즈로 점진 변동.
     * base에서 너무 멀어지지 않도록 약한 평균회귀(reversion) 적용.
     */
    private fun nextPrice(
        prev: Long,
        base: Long,
        tick: Int,
        seed: Int,
    ): Long {
        val maxDelta = (base * props.volatilityPercent / 100).toLong().coerceAtLeast(1L)
        // sin 위상은 종목별로 다르게 (seed)
        val sinComponent = sin((tick + seed) * 0.05) * maxDelta * 0.7
        val noise = (Random.nextDouble() - 0.5) * maxDelta * 0.6
        // 평균회귀: prev가 base에서 멀수록 base 쪽으로 끌어당김
        val reversion = (base - prev) * 0.02
        val next = (prev + sinComponent + noise + reversion).toLong()
        // ±10% 안에서 클램프 (안전장치)
        val lower = (base * 0.90).toLong()
        val upper = (base * 1.10).toLong()
        return next.coerceIn(lower, upper)
    }

    private fun baseVolume(code: String): Long =
        when (code) {
            "005930" -> 1_000_000L
            "000660" -> 500_000L
            "035720" -> 300_000L
            "005380" -> 200_000L
            "051910" -> 150_000L
            else -> 100_000L
        }
}
