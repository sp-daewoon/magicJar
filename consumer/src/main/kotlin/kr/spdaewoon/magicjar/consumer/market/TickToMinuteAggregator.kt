package kr.spdaewoon.magicjar.consumer.market

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.aggregation.BoundaryAggregator
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaRepository
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 1분봉 closing 어그리게이터 (ADR 0029 §2-3 정합).
 *
 * @Scheduled(cron = "0 * * * * ?") 매 분 0초 실행:
 * 1. KIS WS 구독 중인 모든 종목 → Redis `candle:current:{code}:1m` HGETALL
 * 2. OHLCV → ConsumerCandleJpaEntity DB INSERT (ON CONFLICT DO NOTHING — idempotent)
 * 3. Kafka `market.candle.minute.closed.v1` publish
 * 4. Redis `candle:current:{code}:1m` DEL (다음 분봉 시작 준비)
 * 5. BoundaryAggregator.onTickClosed(code, closingTs) 호출 (ADR 0064 B-1 — 5m~1h boundary 검사)
 *
 * PR-9b market-data 담당 (dispatch §3-2-F — KIS WS handler 연관성).
 * ADR 0064 §4-3 BoundaryAggregator 연동 — 1m closing 완료 직후 boundary 검사 trigger.
 *
 * KIS-first raw 출처 (헌법 §3 의무):
 *   H0STCNT0 체결가 STCK_CNTG_HOUR (HHMMSS) → 1m closing ts 산출 기준.
 *   raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
 *   _workspace/baseline/v3.2.2/03-use-sequences.md §시나리오 3 R5 인용.
 */
@Component
class TickToMinuteAggregator(
    private val redisTemplate: StringRedisTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val candleRepository: ConsumerCandleJpaRepository,
    private val objectMapper: ObjectMapper,
    private val kisWebSocketBridge: KisWebSocketBridge,
    private val boundaryAggregator: BoundaryAggregator,
) {
    private val log = LoggerFactory.getLogger(TickToMinuteAggregator::class.java)

    /**
     * 매 분 0초 실행 — 1분봉 closing → DB INSERT + Kafka publish.
     * KIS WS 구독 종목 전체 처리.
     */
    @Scheduled(cron = "0 * * * * ?")
    fun aggregate() {
        val subscribedCodes = kisWebSocketBridge.subscribedCodes()
        if (subscribedCodes.isEmpty()) return

        val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val closingTs = now.minusSeconds(60) // 방금 닫힌 분봉의 시작 시각

        subscribedCodes.forEach { code ->
            try {
                aggregateOneMinuteCandle(code, closingTs)
                // ADR 0064 B-1 — 1m closing 완료 직후 boundary 검사 trigger
                // closingTs = 방금 닫힌 1m bar의 시작 ts (TickToMinuteAggregator 기준)
                boundaryAggregator.onTickClosed(code, closingTs)
            } catch (e: Exception) {
                log.error("1m closing 처리 실패: code={} error={}", code, e.message)
            }
        }
    }

    private fun aggregateOneMinuteCandle(
        code: String,
        closingTs: Instant,
    ) {
        val key = "candle:current:$code:1m"
        val hash = redisTemplate.opsForHash<String, String>().entries(key)

        if (hash.isEmpty()) {
            log.debug("1m closing: code={} Redis 데이터 없음 (장외 또는 tick 0건)", code)
            return
        }

        val openRaw = hash["open"]?.let { BigDecimal(it) } ?: return
        val highRaw = hash["high"]?.let { BigDecimal(it) } ?: return
        val lowRaw = hash["low"]?.let { BigDecimal(it) } ?: return
        val closeRaw = hash["close"]?.let { BigDecimal(it) } ?: return
        val volume = hash["volume"]?.toLongOrNull() ?: 0L

        // DB INSERT — idempotent (ON CONFLICT DO NOTHING)
        val entity =
            ConsumerCandleJpaEntity(
                code = code,
                interval = "1m",
                ts = closingTs,
                openRaw = openRaw,
                highRaw = highRaw,
                lowRaw = lowRaw,
                closeRaw = closeRaw,
                openAdj = null,
                highAdj = null,
                lowAdj = null,
                closeAdj = null,
                volume = volume,
                source = "KIS",
            )

        try {
            candleRepository.save(entity)
            log.debug("1m candle DB INSERT: code={} ts={} close={}", code, closingTs, closeRaw)
        } catch (e: Exception) {
            // UQ 위반 (이미 존재) = idempotent OK
            if (e.message?.contains("duplicate") == true || e.message?.contains("unique") == true) {
                log.debug("1m candle 이미 존재 (idempotent): code={} ts={}", code, closingTs)
            } else {
                log.error("1m candle DB INSERT 실패: code={} error={}", code, e.message)
            }
        }

        // Kafka market.candle.minute.closed.v1 publish
        publishMinuteCandle(code, closingTs, openRaw, highRaw, lowRaw, closeRaw, volume)

        // Redis candle:current:{code}:1m DEL — 다음 분봉 준비
        redisTemplate.delete(key)
        log.debug("1m Redis candle:current DEL: code={}", code)
    }

    private fun publishMinuteCandle(
        code: String,
        ts: Instant,
        openRaw: BigDecimal,
        highRaw: BigDecimal,
        lowRaw: BigDecimal,
        closeRaw: BigDecimal,
        volume: Long,
    ) {
        try {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "code" to code,
                        "interval" to "1m",
                        "ts" to ts.toString(),
                        "openRaw" to openRaw.toPlainString(),
                        "highRaw" to highRaw.toPlainString(),
                        "lowRaw" to lowRaw.toPlainString(),
                        "closeRaw" to closeRaw.toPlainString(),
                        "volume" to volume,
                    ),
                )
            kafkaTemplate.send("market.candle.minute.closed.v1", code, payload)
            log.debug("1m candle Kafka publish: code={} ts={}", code, ts)
        } catch (e: Exception) {
            log.error("1m candle Kafka publish 실패: code={} error={}", code, e.message)
        }
    }
}
