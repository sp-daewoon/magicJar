package kr.spdaewoon.magicjar.domain.messaging

/**
 * Kafka 토픽 중앙 레지스트리 (single source of truth).
 *
 * 목적: 토픽 wire 문자열이 producer/listener/test에 raw string으로 산재해 "헷갈림"이 컸다.
 * 이 object는 **wire 값을 한 글자도 바꾸지 않고** 중앙으로 모으기만 한다 (Option A — registry only).
 *
 * ### 불변식 (절대 위반 금지)
 * - 모든 `const val` 값 == 현재 운영 중인 wire 문자열. 어떤 토픽 wire 값도 변하지 않는다.
 *   (REAL canary 운영 중 — wire string 불변이 절대 불변식)
 * - 새 명명규칙은 각 const 위 주석의 `nextName:` 으로만 표기한다. 실제 적용은 별도 마이그레이션 PR.
 *
 * ### 명명규칙 제안 출처 (nextName)
 * `docs/architecture/backup/realtime-topic-inventory.md`
 *   - "Kafka Inventory & Rename Proposal" 표 (Proposed topic 컬럼)
 *   - "DLT" 표
 *   - 권장 규칙: `kafka.{owner}.{kind}.{domain}.{resource}.{event}.v{version}`
 *
 * ### 도메인 분류 (가독성용 nested object)
 * - [Market]    : 시세·호가·시장상태·실시간수요·지수·섹터
 * - [Candle]    : 봉마감 이벤트 (1m + boundary 8종)
 * - [Order]     : 주문 의도/요청/체결/거부/취소 + 잔고변경
 * - [Risk]      : 시그널·리스크위반·킬스위치
 * - [Reconcile] : 정합 실행/드리프트/수동트리거
 * - [System]    : 시스템이벤트·KIS키변경·watchlist·종목상세
 * - [Data]      : instrument·corp_action·disclosure·news
 * - [Ai]        : AI 코멘트 요청/응답
 * - [Dlt]       : Dead Letter Topic 4종
 *
 * 순수 Kotlin object — Spring/JPA import 0 (domain 모듈 제약 준수).
 */
object KafkaTopics {
    /** 시세·호가·시장상태·실시간수요·지수·섹터 */
    object Market {
        // owner=consumer kind=evt | nextName: kafka.consumer.evt.market.tick.v1
        const val TICK = "market.tick.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.market.orderbook.v1
        const val ORDERBOOK = "market.orderbook.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.market.status.v1
        const val STATUS = "market.status.v1"

        // owner=api kind=cmd | nextName: kafka.api.cmd.market.realtime-demand.v1
        const val REALTIME_DEMAND = "market.realtime.demand.v1"

        // owner=batch kind=evt | nextName: kafka.batch.evt.market.index-updated.v1
        const val INDEX_UPDATED = "market.index.updated.v1"

        // owner=batch kind=evt | nextName: kafka.batch.evt.market.sector-updated.v1
        const val SECTOR_UPDATED = "market.sector.updated.v1"
    }

    /** 봉마감 이벤트 (1m aggregator + boundary aggregator 8종) */
    object Candle {
        // owner=consumer kind=evt | nextName: kafka.consumer.evt.market.candle.1m.closed.v1
        const val MINUTE_CLOSED = "market.candle.minute.closed.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.market.candle.3m.closed.v1
        const val M3_CLOSED = "market.candle.3m.closed.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.market.candle.5m.closed.v1
        const val M5_CLOSED = "market.candle.5m.closed.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.market.candle.15m.closed.v1
        const val M15_CLOSED = "market.candle.15m.closed.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.market.candle.30m.closed.v1
        const val M30_CLOSED = "market.candle.30m.closed.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.market.candle.90m.closed.v1
        const val M90_CLOSED = "market.candle.90m.closed.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.market.candle.1h.closed.v1
        const val H1_CLOSED = "market.candle.1h.closed.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.market.candle.4h.closed.v1
        const val H4_CLOSED = "market.candle.4h.closed.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.market.candle.12h.closed.v1
        const val H12_CLOSED = "market.candle.12h.closed.v1"
    }

    /** 주문 의도/요청/체결/거부/취소 + 잔고변경 */
    object Order {
        // owner=shared kind=cmd | nextName: kafka.shared.cmd.order.intent.v1
        const val INTENT = "order.intent.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.order.requested.v1
        const val REQUESTED = "order.requested.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.order.filled.v1
        const val FILLED = "order.filled.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.order.rejected.v1
        const val REJECTED = "order.rejected.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.order.cancelled.v1
        const val CANCELLED = "order.cancelled.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.account.balance-changed.v1
        const val ACCOUNT_BALANCE_CHANGED = "account.balance.changed.v1"
    }

    /** 시그널·리스크위반·킬스위치 */
    object Risk {
        // owner=consumer kind=evt | nextName: kafka.consumer.evt.strategy.signal-generated.v1
        const val SIGNAL_GENERATED = "signal.generated.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.risk.violation.v1
        const val VIOLATION = "risk.violation.v1"

        // owner=shared kind=evt | nextName: kafka.shared.evt.killswitch.engaged.v1
        const val KILLSWITCH_ENGAGED = "killswitch.engaged.v1"
    }

    /** 정합 실행/드리프트/수동트리거 */
    object Reconcile {
        // owner=consumer kind=evt | nextName: kafka.consumer.evt.reconcile.run.v1
        const val RUN = "reconcile.run.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.reconcile.drift.v1
        const val DRIFT = "reconcile.drift.v1"

        // owner=api kind=cmd | nextName: kafka.api.cmd.reconcile.manual-trigger.v1
        const val MANUAL_TRIGGER = "reconcile.manual.trigger.v1"
    }

    /** 시스템이벤트·KIS키변경·watchlist·종목상세 */
    object System {
        // owner=shared kind=evt | nextName: kafka.shared.evt.system.event.v1
        const val EVENT = "system.event.v1"

        // owner=api kind=evt | nextName: kafka.api.evt.system.kis-key.changed.v1
        const val KIS_KEY_CHANGED = "system.kis-key-changed.v1"

        // owner=api kind=evt | nextName: kafka.api.evt.watchlist.changed.v1
        const val WATCHLIST_CHANGED = "watchlist.changed.v1"

        // owner=api kind=cmd | nextName: kafka.api.cmd.symbol.detail-realtime.v1
        const val SYMBOL_DETAIL_COMMAND = "symbol.detail.command.v1"
    }

    /** instrument·corp_action·disclosure·news */
    object Data {
        // owner=batch kind=evt | nextName: kafka.batch.evt.instrument.updated.v1
        const val INSTRUMENT_UPDATED = "instrument.updated.v1"

        // owner=batch kind=evt | nextName: kafka.batch.evt.corporate-action.applied.v1
        const val CORP_ACTION_APPLIED = "corp_action.applied.v1"

        // owner=api kind=evt | nextName: kafka.api.evt.disclosure.new.v1
        const val DISCLOSURE_NEW = "disclosure.new.v1"

        // owner=api kind=evt | nextName: kafka.api.evt.news.collected.v1
        const val NEWS_COLLECTED = "news.collected.v1"
    }

    /** AI 코멘트 요청/응답 */
    object Ai {
        // owner=api kind=cmd | nextName: kafka.api.cmd.ai.comment-requested.v1
        const val COMMENT_REQUESTED = "ai.comment.requested.v1"

        // owner=consumer kind=evt | nextName: kafka.consumer.evt.ai.comment-responded.v1
        const val COMMENT_RESPONDED = "ai.comment.responded.v1"
    }

    /**
     * Dead Letter Topic 4종.
     *
     * wire 규칙: `<원토픽>.DLT` suffix (Spring Kafka DeadLetterPublishingRecoverer 기본 규칙).
     * `KafkaErrorHandlerConfig`의 `"${record.topic()}.DLT"` 동적 suffix와 정합한다.
     */
    object Dlt {
        // owner=shared kind=dlt | nextName: kafka.shared.dlt.order.intent.v1
        const val ORDER_INTENT = "order.intent.v1.DLT"

        // owner=consumer kind=dlt | nextName: kafka.consumer.dlt.order.requested.v1
        const val ORDER_REQUESTED = "order.requested.v1.DLT"

        // owner=consumer kind=dlt | nextName: kafka.consumer.dlt.order.rejected.v1
        const val ORDER_REJECTED = "order.rejected.v1.DLT"

        // owner=consumer kind=dlt | nextName: kafka.consumer.dlt.order.filled.v1
        const val ORDER_FILLED = "order.filled.v1.DLT"
    }
}
