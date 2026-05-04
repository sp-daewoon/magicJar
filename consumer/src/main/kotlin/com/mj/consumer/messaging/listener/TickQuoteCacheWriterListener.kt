package com.mj.consumer.messaging.listener

import com.mj.consumer.application.market.QuoteResolverService
import com.mj.domain.market.event.TickEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * market.tick.v1 → QuoteCache 적재 (Tier 1·2 LIVE/CACHED 응답을 가능케 함).
 *
 * Quote hotfix B (R-A) — handoff 2026-05-04 P0:
 *   기존엔 Kafka에 tick은 흐르지만 누구도 [QuoteResolverService.putLatest]를 호출하지 않아
 *   `quote:last:*` Redis 키가 항상 비어 있었다. REST `/api/instruments/{code}/quote`는
 *   결과적으로 항상 Tier 3 (LAST_DAY_CLOSE) 또는 404로 떨어졌다.
 *
 * 본 리스너는 `market.tick.v1`을 별도 group(`magicjar-consumer-quote-cache`)으로 컨슘해
 * 모든 tick(KIS_WS · MOCK · CACHED)을 캐시에 쓴다.
 *
 * - TickStompFanoutListener / TickToMinuteAggregator 등 다른 컨슈머와 group 분리.
 * - 캐시 쓰기 실패는 warn 로그만 남기고 메시지 손실 허용 (best-effort).
 * - profile 무관 — mock에서도 InMemoryQuoteCacheAdapter로 동작해 일관된 흐름 유지.
 */
@Component
class TickQuoteCacheWriterListener(
    private val quoteResolver: QuoteResolverService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.tick:market.tick.v1}"],
        groupId = "magicjar-consumer-quote-cache",
    )
    fun onTick(event: TickEvent) {
        try {
            quoteResolver.putLatest(event)
            log.debug(
                "[QUOTE_CACHE_WRITER] code={} price={} source={} stale={}",
                event.code,
                event.price,
                event.source,
                event.stale,
            )
        } catch (e: Exception) {
            log.warn(
                "[QUOTE_CACHE_WRITER] putLatest failed code={} error={}",
                event.code,
                e.message,
            )
        }
    }
}
