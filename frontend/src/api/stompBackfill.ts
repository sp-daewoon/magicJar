/**
 * STOMP Backfill REST API 클라이언트 (P1-3 frontend)
 *
 * GET /api/stomp/events/unread?userId=&since=    → UnreadStompEventCountDto
 * GET /api/stomp/events?userId=&since=&limit=    → StompEventDto[]
 *
 * 목적: STOMP 재연결 시점에 누락된 이벤트를 backend REST로 backfill — 끊김 구간 동안 발생한
 * order/signal/risk 이벤트를 사용자에게 한 번에 토스트/모달로 노출.
 *
 * backend contract LOCK (backend-core dispatch 별도 — frontend는 DTO 정합만):
 *   - userId: number (ADR 0089)
 *   - since: ISO 8601 instant (UTC) — localStorage `stomp:lastSeenAt:${userId}` 사용
 *   - limit: max 100 (frontend 기본 100 — 누락 시 limit reach 시 추가 페이지네이션 X — 매우 드문 케이스)
 *
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────────────────────
// DTO
// ──────────────────────────────────────────────────────────────

/** STOMP event payload 분기 — backend StompEventDto.eventKind */
export type StompEventKind = 'ORDER' | 'SIGNAL' | 'RISK'

/**
 * STOMP 미수신 이벤트 통일 DTO.
 *
 * 각 이벤트 종류별 payload는 backend StompOrderNotifier/StompSignalDispatcher/StompRiskNotifier가
 * 발행한 원본 STOMP body JSON 그대로 `payload` 필드에 직렬화되어 옴.
 *
 *  - ORDER:  payload = OrderStompPayload (useStompOrder.ts)
 *  - SIGNAL: payload = SignalDto (signal.ts)
 *  - RISK:   payload = RiskEventDto (risk.ts)
 *
 * 정확한 타입 분기는 hook에서 eventKind 기반 narrowing 수행.
 */
export interface StompEventDto {
  /** DB sequence ID (재발행 idempotency anchor) */
  id: number
  eventKind: StompEventKind
  /** ISO 8601 instant (UTC) — 이벤트 발생 시각 */
  occurredAt: string
  /** 이벤트 원본 payload JSON object (eventKind 기반 narrowing 필요) */
  payload: unknown
}

/** GET /api/stomp/events/unread 응답 — count 단건 */
export interface UnreadStompEventCountDto {
  count: number
  /** backend 측 최신 이벤트 occurredAt (frontend lastSeenAt 갱신 anchor — count > 0 시 의미 있음) */
  latestOccurredAt: string | null
}

// ──────────────────────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────────────────────

export const stompBackfillApi = {
  /**
   * GET /api/stomp/events/unread?userId=&since=
   * 사용자 미수신 이벤트 개수 + latest occurredAt anchor 조회.
   *
   * STOMP 재연결 시점에 호출 — count=0 시 backfill skip (빈 요청 0).
   */
  getUnreadCount: async (
    userId: number,
    since: string,
  ): Promise<UnreadStompEventCountDto> => {
    const res = await apiClient.get<UnreadStompEventCountDto>(
      `/stomp/events/unread?userId=${userId}&since=${encodeURIComponent(since)}`,
    )
    return res.data
  },

  /**
   * GET /api/stomp/events?userId=&since=&limit=
   * 누락된 이벤트 본체 시계열 조회 (occurredAt ASC).
   *
   * limit=100 default — 100건 초과 누락 시점은 상위 경고(토스트) + 사용자 수동 새로고침 유도.
   */
  list: async (
    userId: number,
    since: string,
    limit = 100,
  ): Promise<StompEventDto[]> => {
    const res = await apiClient.get<StompEventDto[]>(
      `/stomp/events?userId=${userId}&since=${encodeURIComponent(since)}&limit=${limit}`,
    )
    return res.data
  },
}
