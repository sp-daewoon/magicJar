package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * 추세선 손절 봉 경계 재박제 listener — ADR 0140 §3-4 R2b (선-기반 동적 SL · 추세매매 Phase 2).
 *
 * ## 왜 별도 listener 인가 (복잡도 격리 · zero-regression)
 * 봉 경계마다 추세선 손절 slPrice 를 재박제하는 R2b 는 R2 에서 복잡도 격리로 이월된 항목이다. 신호 생성 hot path
 * ([BoundaryClosingKafkaListener] → RealtimeSignalGenerator)를 침범하지 않도록 **독립 groupId** 로 같은 봉 경계
 * 토픽을 별도 구독해, 마감 봉 (code, interval, ts)만 뽑아 [PlanExecutor.restampTrendlineStops] 로 위임한다. 신호 경로와
 * 물리적으로 분리된 thread 라 재박제 지연·실패가 시그널 생성에 전파되지 않는다([PositionStopLossTickWatcher] 독립 안전망 정신 정합).
 *
 * ## 대상 (자연 no-op 격리)
 * PlanExecutor 가 planIndex(owner=AUTOPILOT + trade_plan) 안에서 `type=TRENDLINE` 손절 lot 만 재박제한다. 추세선 손절
 * 전략이 없거나 planned-entry 게이트 off 면 planIndex 가 비어 즉시 return(DB 조회 0 · 행동 변화 0). 봉 경계 대량 유입에도
 * 인메모리 필터로 흡수한다(FIXED/ATR 손절·타 interval lot 무영향).
 *
 * ### 구독 토픽 (BoundaryClosingKafkaListener 정합 — 9 interval)
 * market.candle.{minute,3m,5m,15m,30m,90m,1h,4h,12h}.closed.v1
 *
 * // AI 호출 0 — T-SIG/추세매매 트랙 정책 (ADR 0023 §2-4)
 */
@Component
class TrendlineStopBoundaryListener(
    private val planExecutor: PlanExecutor,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(TrendlineStopBoundaryListener::class.java)

    @KafkaListener(topics = [KafkaTopics.Candle.MINUTE_CLOSED], groupId = "$GROUP-1m")
    fun on1m(record: ConsumerRecord<String, String>) = dispatch(record, CandleInterval.ONE_MIN)

    @KafkaListener(topics = [KafkaTopics.Candle.M3_CLOSED], groupId = "$GROUP-3m")
    fun on3m(record: ConsumerRecord<String, String>) = dispatch(record, CandleInterval.THREE_MIN)

    @KafkaListener(topics = [KafkaTopics.Candle.M5_CLOSED], groupId = "$GROUP-5m")
    fun on5m(record: ConsumerRecord<String, String>) = dispatch(record, CandleInterval.FIVE_MIN)

    @KafkaListener(topics = [KafkaTopics.Candle.M15_CLOSED], groupId = "$GROUP-15m")
    fun on15m(record: ConsumerRecord<String, String>) = dispatch(record, CandleInterval.FIFTEEN_MIN)

    @KafkaListener(topics = [KafkaTopics.Candle.M30_CLOSED], groupId = "$GROUP-30m")
    fun on30m(record: ConsumerRecord<String, String>) = dispatch(record, CandleInterval.THIRTY_MIN)

    @KafkaListener(topics = [KafkaTopics.Candle.M90_CLOSED], groupId = "$GROUP-90m")
    fun on90m(record: ConsumerRecord<String, String>) = dispatch(record, CandleInterval.NINETY_MIN)

    @KafkaListener(topics = [KafkaTopics.Candle.H1_CLOSED], groupId = "$GROUP-1h")
    fun on1h(record: ConsumerRecord<String, String>) = dispatch(record, CandleInterval.ONE_HOUR)

    @KafkaListener(topics = [KafkaTopics.Candle.H4_CLOSED], groupId = "$GROUP-4h")
    fun on4h(record: ConsumerRecord<String, String>) = dispatch(record, CandleInterval.FOUR_HOUR)

    @KafkaListener(topics = [KafkaTopics.Candle.H12_CLOSED], groupId = "$GROUP-12h")
    fun on12h(record: ConsumerRecord<String, String>) = dispatch(record, CandleInterval.TWELVE_HOUR)

    private fun dispatch(
        record: ConsumerRecord<String, String>,
        interval: CandleInterval,
    ) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val code = payload["code"]?.toString() ?: return
            val ts = parseTs(payload["ts"]) ?: return
            planExecutor.restampTrendlineStops(code, interval, ts)
        } catch (e: Exception) {
            log.error(
                "TrendlineStopBoundaryListener dispatch 실패: interval={} offset={} err={}",
                interval.code,
                record.offset(),
                e.message,
            )
        }
    }

    private fun parseTs(value: Any?): Instant? =
        when (value) {
            null -> null
            is Number -> Instant.fromEpochSeconds(value.toLong())
            is String ->
                value.toLongOrNull()?.let { Instant.fromEpochSeconds(it) }
                    ?: runCatching { Instant.parse(value) }.getOrNull()
            else -> null
        }

    companion object {
        const val GROUP = "magicjar-consumer-trendline-stop-restamp"
    }
}
