/**
 * 실시간 구독 상태 관리자 REST API 클라이언트 + TS 타입
 *
 * backend (market-data 병렬 구현 — 계약 LOCK, 필드명 추측 금지):
 *   - GET /api/admin/realtime-status → RealtimeStatusDto
 *
 * 화면: /admin/realtime (RealtimeStatusPage)
 *   - 최고 관리자 장애 처리용 KIS WS 실시간 구독 상태 가시화
 *   - 5초 auto-refresh + 수동 새로고침 (useRealtimeStatus)
 *
 * 데이터 권위:
 *   - consumer 가 주기적으로 갱신하는 KIS WS 런타임 상태 스냅샷.
 *   - stale=true 면 consumer 가 상태를 갱신하지 못하고 있음 (컨테이너 점검 신호).
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// 타입 (backend RealtimeStatusDto 계약 LOCK 1:1 매핑)
// ──────────────────────────────────────────────

/**
 * KIS WS 연결 상태.
 *
 * - connected: STOMP/KIS WS 세션 연결 여부
 * - sessionCid: 현재 세션 식별자 (null = 미연결)
 * - reconnects24h: 최근 24시간 재연결 횟수 (높으면 불안정 신호)
 * - lastConnectedAt: 마지막 연결 성공 시각 (ISO-8601, null = 한번도 미연결)
 */
export interface RealtimeWsStatus {
  connected: boolean
  sessionCid: string | null
  reconnects24h: number
  lastConnectedAt: string | null
}

/**
 * KIS WS 구독 슬롯 사용량.
 *
 * KIS OpenAPI 는 동시 WS 구독 슬롯 수에 상한이 있음 (max).
 * - used: 현재 사용 슬롯
 * - max: 허용 상한
 * - byTrId: trId 별 사용 슬롯 분해 (예: { "H0STCNT0": 12, "H0STASP0": 4 })
 */
export interface RealtimeSlots {
  used: number
  max: number
  byTrId: Record<string, number>
}

/**
 * 단일 구독 항목.
 *
 * - trId: KIS WS 거래 ID (H0STCNT0=체결가, H0STASP0=호가 등)
 * - code: 종목 코드
 * - codeName: 종목명
 * - owners: 이 구독을 요구한 owner 목록 (strategy·detail 등 — 색 배지 분기)
 * - since: 구독 시작 시각 (ISO-8601)
 */
export interface RealtimeSubscription {
  trId: string
  code: string
  codeName: string
  owners: string[]
  since: string
}

/**
 * 체결 통보 구독 상태 (실주문 체결 알림 경로).
 *
 * - subscribed: 체결 통보 WS 구독 여부 (false 면 체결 알림 정지 — 비정상)
 * - mode: 계좌 모드 (REAL / MOCK 등)
 * - userIdMasked: 마스킹된 사용자 계정 식별자 (null = 미구독)
 */
export interface RealtimeExecutionNotice {
  subscribed: boolean
  mode: string | null
  userIdMasked: string | null
}

/**
 * 캔들 신선도 (장중 시세 유입 여부 진단).
 *
 * ageSeconds 가 180 초과면 시세 유입 정지 의심 (장 마감/동시호가 제외).
 */
export interface RealtimeCandleFreshness {
  code: string
  interval: string
  ageSeconds: number
}

/**
 * GET /api/admin/realtime-status 응답 — 계약 LOCK.
 *
 * - updatedAt: 스냅샷 갱신 시각 (ISO-8601)
 * - stale: true 면 consumer 가 상태를 갱신하지 못함 (빨간 경고)
 */
export interface RealtimeStatus {
  updatedAt: string
  stale: boolean
  ws: RealtimeWsStatus
  slots: RealtimeSlots
  subscriptions: RealtimeSubscription[]
  executionNotice: RealtimeExecutionNotice
  candleFreshness: RealtimeCandleFreshness[]
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const adminRealtimeApi = {
  /**
   * GET /api/admin/realtime-status
   * KIS WS 실시간 구독 상태 스냅샷 조회 (관리자 장애 처리용).
   *
   * apiClient baseURL='/api' → 경로는 '/admin/realtime-status'.
   */
  status: async (): Promise<RealtimeStatus> => {
    const res = await apiClient.get<RealtimeStatus>('/admin/realtime-status')
    return res.data
  },
}
