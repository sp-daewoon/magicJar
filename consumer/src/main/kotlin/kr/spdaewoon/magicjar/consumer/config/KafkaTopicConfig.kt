package kr.spdaewoon.magicjar.consumer.config

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * Kafka topic 자동 생성 설정 (dispatch §3-2-G 정합).
 *
 * 생성 topic:
 * - market.tick.v1                  — KIS WS tick + polling fallback publish (retention 1h)
 * - market.candle.minute.closed.v1  — 1m closing 캔들 (retention 1d)
 * - market.orderbook.v1             — H0STASP0 호가 스냅샷 (retention 1h) — PR-10b 신규
 * - symbol.detail.command.v1        — 호가 sub/unsub 명령 (retention 1h) — PR-10b 신규
 *
 * PR-9b market-data 담당. PR-10b 보강.
 */
@Configuration
class KafkaTopicConfig {
    /**
     * 실시간 tick (KIS WS live + polling fallback).
     * retention: 1h (시세 데이터 — 오래 보관 불필요)
     */
    @Bean
    fun marketTickTopic(): NewTopic =
        TopicBuilder
            .name("market.tick.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "3600000") // 1h
            .build()

    /**
     * 1분봉 closing 캔들 (TickToMinuteAggregator publish).
     * retention: 1d (일봉 집계 충분 시간)
     */
    @Bean
    fun marketCandleMinuteClosedTopic(): NewTopic =
        TopicBuilder
            .name("market.candle.minute.closed.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 호가 스냅샷 (KIS WS H0STASP0 → KisWebSocketBridge publish).
     * retention: 1h (실시간 호가 — 장기 보관 불필요)
     * PR-10b 신규.
     */
    @Bean
    fun marketOrderbookTopic(): NewTopic =
        TopicBuilder
            .name("market.orderbook.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "3600000") // 1h
            .build()

    /**
     * 호가 sub/unsub 명령 (api → consumer OrderbookSubscriptionRouter).
     * retention: 1h (명령 재처리 필요 없음)
     * PR-10b 신규.
     */
    @Bean
    fun symbolDetailCommandTopic(): NewTopic =
        TopicBuilder
            .name("symbol.detail.command.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "3600000") // 1h
            .build()

    /**
     * 종목 마스터 sync 변경 이벤트 (InstrumentSyncJob → consumers).
     * partition key = market (KOSPI·KOSDAQ 분리).
     * retention: 7d (신규 consumer 재처리 가능).
     * PR-11a T-IM1 market-data 신규.
     */
    @Bean
    fun instrumentUpdatedTopic(): NewTopic =
        TopicBuilder
            .name("instrument.updated.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "604800000") // 7d
            .build()
}
