package kr.spdaewoon.magicjar.api.messaging

/**
 * STOMP destination 중앙 레지스트리 (single source of truth — Option A registry only).
 *
 * 목적: STOMP fanout destination(`/topic/...`) 문자열이 11개 messaging 클래스 + 잡 서비스에
 * 인라인 string template으로 산재해 "fanout인지 source event인지" 헷갈림이 컸다.
 * 이 object는 **wire 값을 한 글자도 바꾸지 않고** 중앙으로 모으기만 한다.
 * (frontend `frontend/src/api/stompDestinations.ts` 의 백엔드 대응판 — wire 동일.)
 *
 * ### 불변식 (절대 위반 금지)
 * - 모든 빌더 반환값·상수 == 현재 운영 중인 destination 문자열(prefix `/topic/...` 포함)과 정확히 동일.
 *   어떤 STOMP destination wire도 변하지 않는다. (REAL canary 운영 중 — wire 불변이 절대 불변식)
 * - 새 명명규칙은 각 멤버 위 주석의 `nextName:` 으로만 표기한다. 실제 적용은 별도 마이그레이션 PR.
 *
 * ### 명명규칙 제안 출처 (nextName)
 * `docs/architecture/backup/realtime-topic-inventory.md`
 *   - "STOMP Inventory & Rename Proposal" 표 (Proposed destination 컬럼)
 *   - 권장 규칙: `/topic/stomp.{owner}.{domain}.{stream}[.{key}]`
 *
 * ### 설계
 * - 동적 키({code}/{userId}/{requestId}/{interval}/{jobId})는 함수 인자, 정적은 상수.
 * - broker prefix(`/topic`)는 [PREFIX] 상수로 단일화하되 behavior 불변(값 동일).
 *
 * 순수 Kotlin object — Spring/JPA import 0.
 */
object StompDestinations {
    /** STOMP simple broker prefix ([config.WebSocketConfig] `enableSimpleBroker("/topic")` 와 정합). */
    const val PREFIX = "/topic"

    // ──────────────────────────────────────────────────────────────
    // Market — 시세·캔들·지수·섹터·선호도
    // ──────────────────────────────────────────────────────────────

    /** 현재가 tick — Kafka `market.tick.v1` fanout (MarketTickStompForwarder).
     *  nextName: /topic/stomp.api.market.tick.{code} */
    fun marketTick(code: String): String = "$PREFIX/market.tick.$code"

    /** 통합 호가 — Kafka `market.orderbook.v1` fanout (MarketOrderbookStompForwarder).
     *  nextName: /topic/stomp.api.market.orderbook.{code} */
    fun marketOrderbook(code: String): String = "$PREFIX/market.orderbook.$code"

    /** 1분봉 closed — Kafka `market.candle.minute.closed.v1` fanout (MarketCandleStompForwarder).
     *  nextName: /topic/stomp.api.market.candle.1m.{code} */
    fun marketCandleMinute(code: String): String = "$PREFIX/market.candle.minute.$code"

    /** boundary closed 캔들 (interval = 3m·5m·15m·30m·90m·1h·4h·12h — CandleClosedStompListener).
     *  nextName: /topic/stomp.api.market.candle.{interval}.{code} */
    fun marketCandle(
        interval: String,
        code: String,
    ): String = "$PREFIX/market.candle.$interval.$code"

    /** 지수 트렌드 — market trend notifier (StompMarketNotifier, 1분 cron).
     *  nextName: /topic/stomp.api.market.index.{code} */
    fun marketIndex(code: String): String = "$PREFIX/market.index.$code"

    /** 섹터 랭킹 — market trend notifier (StompMarketNotifier, 5분 캐시) — 정적.
     *  nextName: /topic/stomp.api.market.sector.rank */
    const val MARKET_SECTOR_RANK = "$PREFIX/market.sector.rank"

    /** 트렌드 선호도 sync — 다중 탭 동기화 (StompMarketNotifier).
     *  nextName: /topic/stomp.api.market.preference.user.{userId} */
    fun marketUserPref(userId: Any): String = "$PREFIX/market.user.$userId.pref"

    // ──────────────────────────────────────────────────────────────
    // Trading / Risk / Account — user-scoped
    // ──────────────────────────────────────────────────────────────

    /** 주문 이벤트 — Kafka `order.*.v1` fanout (StompOrderNotifier).
     *  nextName: /topic/stomp.api.order.user.{userId} */
    fun orderUser(userId: Any): String = "$PREFIX/order.user.$userId"

    /** 시그널 — Kafka `signal.generated.v1` fanout (StompSignalDispatcher).
     *  nextName: /topic/stomp.api.signal.user.{userId} */
    fun signalUser(userId: Any): String = "$PREFIX/signal.user.$userId"

    /** 리스크 위반 — Kafka `risk.violation.v1` fanout (StompRiskNotifier).
     *  nextName: /topic/stomp.api.risk.user.{userId} */
    fun riskUser(userId: Any): String = "$PREFIX/risk.user.$userId"

    /** 계좌 잔고 변경 — Kafka `account.balance.changed.v1` fanout (StompAccountNotifier).
     *  nextName: /topic/stomp.api.account.balance.user.{userId} */
    fun accountBalanceUser(userId: Any): String = "$PREFIX/account.balance.$userId"

    /** 정합/킬스위치 — Kafka reconcile/kill switch fanout (StompReconcileNotifier).
     *  nextName: /topic/stomp.api.reconcile.user.{userId} */
    fun reconUser(userId: Any): String = "$PREFIX/recon.user.$userId"

    // ──────────────────────────────────────────────────────────────
    // System — user-scoped + broadcast
    // ──────────────────────────────────────────────────────────────

    /** 시스템 이벤트 (사용자별) — Kafka `system.event.v1` fanout (SystemEventStompNotifier).
     *  nextName: /topic/stomp.api.system.event.user.{userId} */
    fun systemEventUser(userId: Any): String = "$SYSTEM_EVENT_USER_PREFIX$userId"

    /** [systemEventUser] 의 정적 prefix — `${SYSTEM_EVENT_USER_PREFIX}{userId}` 조합.
     *  (기존 SystemEventStompNotifier.TOPIC_USER_PREFIX 와 wire 동일) */
    const val SYSTEM_EVENT_USER_PREFIX = "$PREFIX/system.event.user."

    /** 시스템 이벤트 (broadcast) — 정적 (SystemEventStompNotifier).
     *  nextName: /topic/stomp.api.system.event.broadcast */
    const val SYSTEM_EVENT_BROADCAST = "$PREFIX/system.event.broadcast"

    // ──────────────────────────────────────────────────────────────
    // AI — request-scoped
    // ──────────────────────────────────────────────────────────────

    /** AI 코멘트 응답 — Kafka `ai.comment.responded.v1` fanout (AiCommentResponseStompForwarder).
     *  nextName: /topic/stomp.api.ai.explain.{requestId} */
    fun aiExplain(requestId: String): String = "$PREFIX/ai.explain.$requestId"

    // ──────────────────────────────────────────────────────────────
    // Job — candle recollect 진행률
    // ──────────────────────────────────────────────────────────────

    /** 캔들 재수집 잡 진행률 — CandleRecollectJobService.
     *  nextName: /topic/stomp.api.job.candle-recollect.{code}.{jobId} */
    fun candleRecollectJob(
        code: String,
        jobId: Any,
    ): String = "$PREFIX/candle.recollect.$code.$jobId"
}
