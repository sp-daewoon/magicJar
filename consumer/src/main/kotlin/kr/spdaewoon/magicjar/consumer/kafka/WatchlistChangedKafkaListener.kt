package kr.spdaewoon.magicjar.consumer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisQuoteCacheAdapter
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisSourceTrackerAdapter
import kr.spdaewoon.magicjar.consumer.ingestion.SubscriptionRouter
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
 * - action=ADDED: WS/polling 분배 (+ Redis warm은 PollingWorker 첫 tick)
 * - action=REMOVED: unsubscribe + Redis evict
 *
 * ★ proactive cold-start 백필 폐지 (ADR 0119 · 2026-06-09):
 *   watchlist 종목 추가 시 과거 1m·일봉 무더기 KIS 백필을 더 이상 트리거하지 않는다.
 *   신규 종목의 일봉은 전종목 일봉 universe(R-BF/R2 · ADR 0118)가 이미 보유하고,
 *   1m은 WS 구독(SubscriptionRouter)이 실시간으로 채운다. 과거 1m 정밀 갭은 차트 진입
 *   on-demand(SymbolDetailMinuteGapFillService) 또는 admin 수동 backfill(CandleBackfillWorker)만 수행.
 *
 * 40 slot 한도 게이트: SubscriptionRouter.add 내부에서 처리.
 * slot 상태는 응답 payload로 노출하지 않음 (UI 배지는 T-S1에서 별도 endpoint 구현 예정).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class WatchlistChangedKafkaListener(
    private val objectMapper: ObjectMapper,
    private val subscriptionRouter: SubscriptionRouter,
    private val redisQuoteCacheAdapter: RedisQuoteCacheAdapter,
    private val redisSourceTracker: RedisSourceTrackerAdapter,
) {
    private val log = LoggerFactory.getLogger(WatchlistChangedKafkaListener::class.java)

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

        // 1. SubscriptionRouter에 위임 (slot 여유 검사 + WS/polling 분배 — ADR 0048 §2-6)
        //    proactive cold-start 백필 폐지 (ADR 0119): 신규 종목 일봉=R-BF/R2 기보유, 1m=WS 실시간.
        //    과거 1m 정밀 갭은 차트 진입 on-demand(SymbolDetailMinuteGapFillService)만 수행 — 여기서 KIS 백필 0.
        subscriptionRouter.add(userId, code, priority = 100)

        // 2. Redis quote:last warm (즉시 첫 가격 표시 위해)
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
