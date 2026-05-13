package kr.spdaewoon.magicjar.consumer.config

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * T-O Phase 1 신규 주문 4 토픽 등록 (ADR 0068 §14-1 LOCK + §9-2 매트릭스).
 *
 * | topic                  | publisher                       | listener (Phase 2 합류) | retention |
 * |------------------------|---------------------------------|------------------------|-----------|
 * | order.requested.v1     | OrderRouterKafkaListener step 6 | backend-core STOMP     | 30d       |
 * | order.filled.v1        | KisExecutionWsListener          | backend-core STOMP     | 30d       |
 * | order.rejected.v1      | OrderRouterKafkaListener REJECT | backend-core STOMP     | 30d       |
 * | order.cancelled.v1     | KisExecutionWsListener ACPT=3   | backend-core STOMP     | 30d       |
 *
 * 모든 토픽 partition key = userId — 사용자별 ordering 보장.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Configuration
class OrderTopicConfig {
    companion object {
        private const val RETENTION_30D_MS = "2592000000" // 30d
    }

    /**
     * 주문 접수 통보 (ADR 0068 §9-2 + §14-2 step 6).
     *
     * OrderRouterKafkaListener step 5 INSERT 직후 publish — ADR 0044 NotificationDispatcher 진입 +
     * Phase 2 backend-core STOMP `/topic/order.user.{userId}` REQUESTED fanout.
     *
     * 부하 예측 (§9-2): ~50 msg/day/사용자.
     */
    @Bean
    fun orderRequestedTopic(): NewTopic =
        TopicBuilder
            .name("order.requested.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_30D_MS)
            .build()

    /**
     * 체결 통보 (ADR 0068 §9-2 + §14-6 흐름 분기 CNTG_YN="2").
     *
     * KisExecutionWsListener publish — partial/full 양쪽 — T-RECON 합류점 + STOMP FILLED fanout.
     */
    @Bean
    fun orderFilledTopic(): NewTopic =
        TopicBuilder
            .name("order.filled.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_30D_MS)
            .build()

    /**
     * 주문 거절 통보 (ADR 0068 §9-2 + §14-2 step 3·4·7).
     *
     * payload reason: REAL_GATE_DENIED:{cond} / KILLSWITCH_ON / {rt_cd}:{msg} / KIS_5XX 등.
     */
    @Bean
    fun orderRejectedTopic(): NewTopic =
        TopicBuilder
            .name("order.rejected.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_30D_MS)
            .build()

    /**
     * 주문 취소 통보 (ADR 0068 §9-2 + §14-6 ACPT_YN="3").
     *
     * KisExecutionWsListener 또는 Phase 2 OrderCancelService 성공 시 publish.
     */
    @Bean
    fun orderCancelledTopic(): NewTopic =
        TopicBuilder
            .name("order.cancelled.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_30D_MS)
            .build()
}
