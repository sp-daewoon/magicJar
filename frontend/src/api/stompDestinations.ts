/**
 * STOMP destination 중앙 레지스트리 (Option A — registry only / wire 불변)
 *
 * magicJar STOMP 구독 destination 문자열이 14개 파일에 인라인 template literal로
 * 흩어져 있어 "fanout인지 source event인지" 헷갈림이 컸다. 이 모듈은 17개 패턴을
 * 한곳에 모아 빌더 함수/상수로 노출한다.
 *
 * ★ 불변식: 모든 빌더 반환값 == 현재 destination 문자열(prefix `/topic/...` 포함)과
 *   정확히 동일하다. 어떤 wire도 바뀌지 않으며, 백엔드 fanout도 그대로다.
 *   lockstep 리스크 0 — 내일 09:00 실전 테스트 무위험.
 *
 * nextName(주석)은 미래 개편 시 적용할 명명 규칙 제안이며, 출처는
 * `docs/architecture/backup/realtime-topic-inventory.md`
 * "STOMP Inventory & Rename Proposal" 표의 "Proposed destination" 컬럼이다.
 * 제안 규칙: `/topic/stomp.{owner}.{domain}.{stream}[.{key}]`.
 *
 * 동적 키({code}/{userId}/{requestId}/{jobId})는 인자로 받는 함수, 정적은 상수.
 */

// ──────────────────────────────────────────────────────────────
// Market — 시세·캔들·지수·섹터·선호도
// ──────────────────────────────────────────────────────────────

/** 현재가 tick — Kafka `market.tick.v1` fanout
 *  nextName: /topic/stomp.api.market.tick.{code} */
export const marketTick = (code: string) => `/topic/market.tick.${code}`

/** 통합 호가 — Kafka `market.orderbook.v1` fanout
 *  nextName: /topic/stomp.api.market.orderbook.{code} */
export const marketOrderbook = (code: string) => `/topic/market.orderbook.${code}`

/** 1분봉 closed — Kafka `market.candle.minute.closed.v1` fanout
 *  nextName: /topic/stomp.api.market.candle.1m.{code} */
export const marketCandleMinute = (code: string) => `/topic/market.candle.minute.${code}`

/** boundary closed 캔들 (interval = 3m·5m·15m·30m·90m·1h·4h·12h)
 *  nextName: /topic/stomp.api.market.candle.{interval}.{code} */
export const marketCandle = (interval: string, code: string) =>
  `/topic/market.candle.${interval}.${code}`

/** 지수 트렌드 — market trend notifier (1분 cron)
 *  nextName: /topic/stomp.api.market.index.{code} */
export const marketIndex = (code: string) => `/topic/market.index.${code}`

/** 섹터 랭킹 — market trend notifier (5분 캐시) — 정적
 *  nextName: /topic/stomp.api.market.sector.rank */
export const marketSectorRank = '/topic/market.sector.rank'

/** 트렌드 선호도 sync — 다중 탭 동기화
 *  nextName: /topic/stomp.api.market.preference.user.{userId} */
export const marketUserPref = (userId: string | number) =>
  `/topic/market.user.${userId}.pref`

// ──────────────────────────────────────────────────────────────
// Trading / Risk / Account — user-scoped
// ──────────────────────────────────────────────────────────────

/** 주문 이벤트 — Kafka `order.*.v1` fanout
 *  nextName: /topic/stomp.api.order.user.{userId} */
export const orderUser = (userId: string | number) => `/topic/order.user.${userId}`

/** 시그널 — Kafka `signal.generated.v1` fanout
 *  nextName: /topic/stomp.api.signal.user.{userId} */
export const signalUser = (userId: string | number) => `/topic/signal.user.${userId}`

/** 리스크 위반 — Kafka `risk.violation.v1` fanout
 *  nextName: /topic/stomp.api.risk.user.{userId} */
export const riskUser = (userId: string | number) => `/topic/risk.user.${userId}`

/** 계좌 잔고 변경 — Kafka `account.balance.changed.v1` fanout
 *  nextName: /topic/stomp.api.account.balance.user.{userId} */
export const accountBalanceUser = (userId: string | number) =>
  `/topic/account.balance.${userId}`

/** 정합/킬스위치 — Kafka reconcile/kill switch fanout (현재 미구독, 정의만 추가)
 *  nextName: /topic/stomp.api.reconcile.user.{userId} */
export const reconUser = (userId: string | number) => `/topic/recon.user.${userId}`

// ──────────────────────────────────────────────────────────────
// System — user-scoped + broadcast
// ──────────────────────────────────────────────────────────────

/** 시스템 이벤트 (사용자별) — Kafka `system.event.v1` fanout
 *  nextName: /topic/stomp.api.system.event.user.{userId} */
export const systemEventUser = (userId: string | number) =>
  `/topic/system.event.user.${userId}`

/** 시스템 이벤트 (broadcast) — 정적
 *  nextName: /topic/stomp.api.system.event.broadcast */
export const systemEventBroadcast = '/topic/system.event.broadcast'

// ──────────────────────────────────────────────────────────────
// AI — request-scoped
// ──────────────────────────────────────────────────────────────

/** AI 코멘트 응답 — Kafka `ai.comment.responded.v1` fanout (requestId당 1회)
 *  nextName: /topic/stomp.api.ai.explain.{requestId} */
export const aiExplain = (requestId: string) => `/topic/ai.explain.${requestId}`

// ──────────────────────────────────────────────────────────────
// Job — candle recollect 진행률
// ──────────────────────────────────────────────────────────────

/**
 * 캔들 재수집 잡 진행률 — candle recollect job service.
 *
 * ⚠ 현행 source에서 이 destination은 백엔드 응답(job.topic)을 그대로 구독하며
 *   클라이언트가 직접 문자열을 조립하지 않는다. 빌더는 인벤토리 정합·향후 사용을
 *   위해 정의만 추가한다. (jobId는 backend가 `job-{n}` 형태로 부여)
 *
 * nextName: /topic/stomp.api.job.candle-recollect.{code}.{jobId}
 */
export const candleRecollectJob = (code: string, jobId: string) =>
  `/topic/candle.recollect.${code}.${jobId}`
