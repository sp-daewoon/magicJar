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

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0064 A-1·C-1 — boundary 마감 5종 토픽 (v3.3.0 §34)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 5분봉 boundary closing 이벤트 (ADR 0064 §4-5 C-1 채택).
     * NXT cover 08:05~20:00 KST 144건/일 (ADR 0063 정합).
     * api StompCandleClosedForwarder → /topic/market.candle.5m.{code} STOMP fanout.
     * retention: 1d (하루 배치·fallback 충분)
     */
    @Bean
    fun marketCandle5mClosedTopic(): NewTopic =
        TopicBuilder
            .name("market.candle.5m.closed.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 15분봉 boundary closing 이벤트 (ADR 0064 §4-5 C-1 채택).
     * NXT cover 08:15~20:00 KST 48건/일.
     * retention: 1d
     */
    @Bean
    fun marketCandle15mClosedTopic(): NewTopic =
        TopicBuilder
            .name("market.candle.15m.closed.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 30분봉 boundary closing 이벤트 (ADR 0064 §4-5 C-1 채택).
     * NXT cover 08:30~20:00 KST 24건/일.
     * retention: 1d
     */
    @Bean
    fun marketCandle30mClosedTopic(): NewTopic =
        TopicBuilder
            .name("market.candle.30m.closed.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 90분봉 boundary closing 이벤트 (ADR 0064 §4-5 C-1 채택).
     * NXT cover 09:30·11:00·12:30·14:00·15:30·17:00·18:30·20:00 KST 8건/일.
     * retention: 1d
     */
    @Bean
    fun marketCandle90mClosedTopic(): NewTopic =
        TopicBuilder
            .name("market.candle.90m.closed.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 1시간봉 boundary closing 이벤트 (ADR 0064 §4-5 C-1 채택).
     * NXT cover 09:00~20:00 KST 12건/일.
     * retention: 1d
     */
    @Bean
    fun marketCandle1hClosedTopic(): NewTopic =
        TopicBuilder
            .name("market.candle.1h.closed.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0066 §11-1 Phase 1 (6) — T-SIG 시그널 생성 토픽 (W3 두 번째 트랙)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 시그널 발생 이벤트 (ADR 0066 §7-1 SignalKafkaProducer + §9-2 운영 영향).
     *
     * Phase 1 strategy-engine sub-agent publish — Phase 2 backend-core가
     * STOMP `/topic/signal.user.{userId}` fanout consumer 구현.
     *
     * partition key = userId — 사용자별 ordering 보장.
     * retention: 7d (audit · MCP 후속 정합 — ADR 0066 §9-2)
     *
     * 부하 예측 (ADR 0066 §9-2): 1,000 msg/day = 0.012 msg/s — 매우 가벼움.
     *
     * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
     */
    @Bean
    fun signalGeneratedTopic(): NewTopic =
        TopicBuilder
            .name("signal.generated.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "604800000") // 7d
            .build()

    /**
     * 기업 행위(Corporate Action) 적용 이벤트.
     * CorpActionSyncJob → CandleAdjustmentJob 트리거.
     * partition key = instrumentCode (종목별 순서 보장).
     * retention: 1d (소급 보정 완료 후 재처리 불필요).
     * PR-29b T-CA1 market-data 신규 (dispatch-spec §6-3 KafkaTopicConfig PR-29b 단독 LOCK).
     *
     * KIS ksdinfo 6 endpoint 출처 (ADR 0018 §2-5):
     *   HHKDB669100C0 paidin-capin, HHKDB669101C0 bonus-issue,
     *   HHKDB669102C0 dividend,     HHKDB669104C0 merger-split,
     *   HHKDB669105C0 rev-split,    HHKDB669106C0 cap-dcrs
     */
    @Bean
    fun corpActionAppliedTopic(): NewTopic =
        TopicBuilder
            .name("corp_action.applied.v1")
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()
}
