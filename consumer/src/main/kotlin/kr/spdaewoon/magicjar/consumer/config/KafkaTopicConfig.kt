package kr.spdaewoon.magicjar.consumer.config

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * Kafka topic 자동 생성 설정 (dispatch §3-2-G 정합).
 *
 * 생성 topic:
 * - market.tick.v1        — KIS WS tick + polling fallback publish (retention 1h)
 * - market.candle.minute.closed.v1 — 1m closing 캔들 (retention 1d)
 *
 * PR-9b market-data 담당.
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
}
