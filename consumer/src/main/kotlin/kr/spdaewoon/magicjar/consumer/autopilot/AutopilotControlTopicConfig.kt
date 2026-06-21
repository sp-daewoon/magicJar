package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * magicjar-autopilot 제어 평면 토픽 1건 (spec 2026-06-22 §2-0-B·§3 · Phase 0 Task 4).
 *
 * [KafkaTopics.Autopilot.CONTROL] — api `/api/autopilot/{stop,resume,rollback}` cmd → consumer 제어 리스너.
 * 제어는 매우 드물고(수동·kill 사후) 순서가 중요하므로 partition 1(단일 ordering)·retention 30d(audit).
 *
 * 명시 NewTopic 으로 broker auto-create 의존을 제거한다(제어 채널은 누락 시 안전사고 직결 — 보수적 명시).
 */
@Configuration
class AutopilotControlTopicConfig {
    @Bean
    fun autopilotControlTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Autopilot.CONTROL)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "2592000000") // 30d — 제어 audit
            .build()
}
