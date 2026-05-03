package com.mj.api.messaging.listener

import com.mj.api.infrastructure.persistence.market.CandleJpa
import com.mj.api.infrastructure.persistence.market.CandleJpaRepository
import com.mj.domain.common.Interval
import com.mj.domain.market.event.CandleMinuteAggregatedEvent
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

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
) {
    private val log = LoggerFactory.getLogger(CandleMinuteEventListener::class.java)

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
    }
}
