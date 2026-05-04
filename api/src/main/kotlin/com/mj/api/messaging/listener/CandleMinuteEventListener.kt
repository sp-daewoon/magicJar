package com.mj.api.messaging.listener

import com.mj.api.application.market.UserChartCandleService
import com.mj.api.infrastructure.persistence.market.CandleJpa
import com.mj.api.infrastructure.persistence.market.CandleJpaRepository
import com.mj.api.messaging.producer.UserCandleEventPublisher
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.event.CandleMinuteAggregatedEvent
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentHashMap

/**
 * candle.minute.aggregated.v1 수신 → candle 테이블 INSERT (interval=M1).
 *
 * ### idempotent 처리
 * candle 테이블의 PRIMARY KEY (code, interval, open_time) UNIQUE 제약을 이용.
 * 동일 (code, M1, openTime) 이 이미 존재하는 경우 DataIntegrityViolationException을
 * 무시하고 로그만 남긴다 (ON CONFLICT DO NOTHING 의미론).
 *
 * ### 권한 분리
 * api 모듈이 DB owner(Flyway 실행 주체)이므로 candle JPA 어댑터를 api에 둔다.
 * consumer 모듈은 Kafka publish 전담, api 모듈이 INSERT 전담 (ADR 0015 정합).
 */
@Component
class CandleMinuteEventListener(
    private val candleRepository: CandleJpaRepository,
    private val userCandleEventPublisher: UserCandleEventPublisher,
) {
    private val log = LoggerFactory.getLogger(CandleMinuteEventListener::class.java)

    /**
     * R3-5b — 종목별 마지막 user-fanout publish 시각 (epoch ms).
     * yfinance backfill은 분당 수십~수백 봉을 burst INSERT하므로 종목당 [FANOUT_THROTTLE_MS] 간격으로
     * 묶어서 publish — frontend useUserCandleStream의 invalidate 폭주 방지.
     */
    private val lastFanoutAt = ConcurrentHashMap<String, Long>()

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.candleMinuteAggregated:candle.minute.aggregated.v1}"],
        groupId = "magicjar-api-candle-minute",
    )
    @Transactional
    fun onCandleMinuteAggregated(event: CandleMinuteAggregatedEvent) {
        val entity =
            CandleJpa(
                code = event.code,
                interval = Interval.M1,
                openTime = event.openTime,
                open = event.open,
                high = event.high,
                low = event.low,
                close = event.close,
                volume = event.volume,
                tradeCount = event.tradeCount,
            )

        try {
            candleRepository.save(entity)
            log.debug(
                "CandleMinuteEventListener INSERT (code={}, openTime={}, close={})",
                event.code,
                event.openTime,
                event.close,
            )
        } catch (ex: DataIntegrityViolationException) {
            // ON CONFLICT DO NOTHING — 동일 (code, M1, openTime) 이미 존재
            log.debug(
                "CandleMinuteEventListener: 중복 무시 (code={}, openTime={})",
                event.code,
                event.openTime,
            )
        }

        // R3-5b — 종목별 throttled user-fanout publish.
        // yfinance backfill burst(분당 수십 봉)를 한 번의 STOMP fanout으로 묶음.
        publishThrottledFanout(event)
    }

    private fun publishThrottledFanout(event: CandleMinuteAggregatedEvent) {
        val now = System.currentTimeMillis()
        val previous = lastFanoutAt[event.code]
        if (previous != null && now - previous < FANOUT_THROTTLE_MS) {
            return
        }
        lastFanoutAt[event.code] = now

        try {
            // 단일 candle만 payload에 담아 STOMP fanout 트리거. frontend는 invalidate 후 전체 refetch.
            val candle =
                Candle(
                    code = event.code,
                    interval = Interval.M1,
                    openTime = event.openTime,
                    open = event.open,
                    high = event.high,
                    low = event.low,
                    close = event.close,
                    volume = event.volume,
                    tradeCount = event.tradeCount,
                )
            userCandleEventPublisher.publish(
                userId = UserChartCandleService.DEFAULT_USER_ID,
                code = event.code,
                interval = Interval.M1.name,
                candles = listOf(candle),
            )
        } catch (e: Exception) {
            log.debug("[CANDLE_MINUTE] fanout publish skip code={}: {}", event.code, e.message)
        }
    }

    companion object {
        /** R3-5b — 종목당 user-fanout 최소 간격 (ms). 5초. */
        const val FANOUT_THROTTLE_MS: Long = 5_000L
    }
}
