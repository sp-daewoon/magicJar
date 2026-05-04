package com.mj.api.messaging.producer

import com.mj.domain.common.Candle
import com.mj.domain.market.event.CandleSnapshot
import com.mj.domain.market.event.UserCandleUpdatedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * `market.candle.user.v1` 토픽으로 사용자 차트 캔들 업데이트 이벤트를 발행한다.
 *
 * ADR 0050 R3-4 — dual-write 1차:
 *   UserChartCandleService가 DB→Redis warm-up 직후 이 publisher를 호출한다.
 *   consumer 측 UserCandleListener가 수신 후 동일 Redis ZADD를 수행한다 (idempotent).
 *   발행 실패는 fail-soft — 사용자 API 응답을 차단하지 않는다.
 *
 * 직렬화 표준:
 *   camelCase JSON + Type-Id 헤더 (Phase D-P3 pyworker 호환 표준).
 *   KafkaTemplate<String, Any>는 JsonSerializer에 의해 타입 헤더 자동 첨부.
 */
@Component
class UserCandleEventPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.userCandleUpdated:market.candle.user.v1}")
    private val topic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 캔들 목록을 [UserCandleUpdatedEvent]로 변환해 Kafka 발행한다.
     *
     * @param userId   사용자 식별자
     * @param code     KRX 종목코드 (partition key)
     * @param interval Interval 문자열 ("D1", "M1" 등)
     * @param candles  warm-up된 캔들 목록
     */
    fun publish(
        userId: String,
        code: String,
        interval: String,
        candles: List<Candle>,
    ) {
        if (candles.isEmpty()) return

        val event =
            UserCandleUpdatedEvent(
                userId = userId,
                code = code,
                interval = interval,
                candles =
                    candles.map { c ->
                        CandleSnapshot(
                            openTime = c.openTime,
                            open = c.open,
                            high = c.high,
                            low = c.low,
                            close = c.close,
                            volume = c.volume,
                        )
                    },
                occurredAt = Instant.now(),
            )

        // fail-soft: 발행 실패가 사용자 API 응답을 차단하지 않는다.
        try {
            kafka
                .send(topic, code, event)
                .whenComplete { _, ex ->
                    if (ex != null) {
                        log.warn(
                            "[USER_CANDLE_PUBLISH] send failed userId={} code={} interval={} n={}: {}",
                            userId,
                            code,
                            interval,
                            candles.size,
                            ex.message,
                        )
                    } else {
                        log.debug(
                            "[USER_CANDLE_PUBLISH] sent userId={} code={} interval={} n={} topic={}",
                            userId,
                            code,
                            interval,
                            candles.size,
                            topic,
                        )
                    }
                }
        } catch (ex: Exception) {
            // send() 자체 예외 (예: producer 초기화 실패) — fail-soft
            log.warn(
                "[USER_CANDLE_PUBLISH] send exception userId={} code={} interval={}: {}",
                userId,
                code,
                interval,
                ex.message,
            )
        }
    }
}
