package kr.spdaewoon.magicjar.consumer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisQuoteCacheAdapter
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisSourceTrackerAdapter
import kr.spdaewoon.magicjar.consumer.ingestion.ColdStartExecutor
import kr.spdaewoon.magicjar.consumer.ingestion.SubscriptionRouter
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * watchlist.changed.v1 Kafka consumer (ADR 0028·0048 §2-6 §4 정합).
 *
 * - groupId: magicjar-consumer-watchlist-ingestion
 * - manual ack (MANUAL_IMMEDIATE — ADR 0045 graceful shutdown 정합)
 * - at-least-once delivery + idempotent 처리
 * - action=ADDED: cold-start + WS/polling 분배 + Redis warm
 * - action=REMOVED: unsubscribe + Redis evict
 *
 * 41 slot 한도 게이트: SubscriptionRouter.add 내부에서 처리.
 * slot 상태는 응답 payload로 노출하지 않음 (UI 배지는 T-S1에서 별도 endpoint 구현 예정).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class WatchlistChangedKafkaListener(
    private val objectMapper: ObjectMapper,
    private val subscriptionRouter: SubscriptionRouter,
    private val coldStartExecutor: ColdStartExecutor,
    private val redisQuoteCacheAdapter: RedisQuoteCacheAdapter,
    private val redisSourceTracker: RedisSourceTrackerAdapter,
    private val candleRepository: CandleRepository,
) {
    private val log = LoggerFactory.getLogger(WatchlistChangedKafkaListener::class.java)

    companion object {
        private val COLD_START_INTERVALS =
            listOf(
                CandleInterval.ONE_MIN,
                CandleInterval.FIVE_MIN,
                CandleInterval.FIFTEEN_MIN,
                CandleInterval.THIRTY_MIN,
                CandleInterval.NINETY_MIN,
                CandleInterval.ONE_HOUR,
                CandleInterval.ONE_DAY,
            )
    }

    @KafkaListener(
        topics = ["watchlist.changed.v1"],
        groupId = "magicjar-consumer-watchlist-ingestion",
        containerFactory = "watchlistChangedKafkaListenerContainerFactory",
    )
    fun onMessage(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        val event =
            try {
                objectMapper.readValue<WatchlistChangedEvent>(record.value())
            } catch (e: Exception) {
                log.error("watchlist.changed.v1 파싱 실패 — offset={} err={}", record.offset(), e.message)
                // parse 실패는 Dead Letter로 보낼 수 없으므로 ack + 스킵
                ack.acknowledge()
                return
            }

        log.info(
            "WatchlistChangedKafkaListener 수신: userId={} code={} action={}",
            maskUserId(event.userId),
            event.code,
            event.action,
        )

        try {
            when (event.action) {
                "ADDED" -> handleAdded(event)
                "REMOVED" -> handleRemoved(event)
                else -> log.warn("알 수 없는 action: {} — 무시", event.action)
            }
            ack.acknowledge()
        } catch (e: Exception) {
            // 예외 시 ack 호출 안 함 → 재처리 (idempotent 보장)
            log.error(
                "watchlist.changed.v1 처리 실패 (재처리 대상) — code={} action={} err={}",
                event.code,
                event.action,
                e.message,
                e,
            )
        }
    }

    private fun handleAdded(event: WatchlistChangedEvent) {
        val code = event.code
        val userId = event.userId

        // 1. DB candle 부재 검증 → Yahoo cold-start (idempotent)
        val needsColdStart =
            candleRepository.countByCodeAndIntervalIn(code, COLD_START_INTERVALS) == 0L
        if (needsColdStart) {
            log.info("cold-start 트리거: code={}", code)
            coldStartExecutor.submit(code)
        } else {
            log.debug("cold-start 생략 (이미 캔들 존재): code={}", code)
        }

        // 2. SubscriptionRouter에 위임 (slot 여유 검사 + WS/polling 분배 — ADR 0048 §2-6)
        subscriptionRouter.add(userId, code, priority = 100)

        // 3. Redis quote:last warm (즉시 첫 가격 표시 위해)
        // warm은 PollingWorker가 5초 내 갱신하므로 별도 warm 불필요
        // (KIS REST 1회 호출은 WatchlistPollingWorker 첫 tick에서 자동 실행)

        log.info("ADDED 처리 완료: code={}", code)
    }

    private fun handleRemoved(event: WatchlistChangedEvent) {
        val code = event.code
        val userId = event.userId

        // 1. SubscriptionRouter에 위임 (unsubscribe or polling remove + promotion 재계산)
        subscriptionRouter.remove(userId, code)

        // 2. Redis quote:last evict (ADR 0014 §4-3 — 1차 MVP: 즉시 DELETE)
        redisQuoteCacheAdapter.evict(code)

        // 3. Redis watchlist:source evict
        redisSourceTracker.remove(userId, code)

        log.info("REMOVED 처리 완료: code={}", code)
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
