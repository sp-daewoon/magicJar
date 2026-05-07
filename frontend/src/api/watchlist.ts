/**
 * Watchlist REST API 클라이언트
 *
 * spec §4-1 REST 시그니처 LOCK 1:1 정합
 * 3 endpoint: GET/POST /api/watchlists · DELETE /api/watchlists/{code}
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// Response / Request 타입 정의 (spec §4-1 LOCK)
// ──────────────────────────────────────────────

/** GET /api/watchlists 응답 — 배열 요소 */
export interface WatchlistEntryDto {
  id: number
  userId: string
  code: string
  /** 종목명 (instrument 테이블 join — 미동기화 시 null, PR-20) */
  name?: string | null
  /** 시장 구분 KOSPI·KOSDAQ·UNKNOWN (PR-20) */
  market?: string | null
  priority: number
  createdAt: string // ISO 8601
  /**
   * 상장폐지 여부 (instrument.active=false).
   * true 시 "상장폐지" 작은 라벨 표시 + 사용자가 직접 watchlist에서 제거 결정.
   */
  delisted?: boolean
}

/** POST /api/watchlists 요청 */
export interface WatchlistAddRequest {
  code: string
  priority?: number
}

/** 공통 에러 응답 포맷 (spec §4-2 LOCK) */
export interface WatchlistErrorResponse {
  code:
    | 'INVALID_TICKER_FORMAT'
    | 'WATCHLIST_DUPLICATE'
    | 'WATCHLIST_NOT_FOUND'
    | 'ONBOARDING_REQUIRED'
    | 'VALIDATION_ERROR'
  message: string
}

// ──────────────────────────────────────────────
// axios API 함수
// ──────────────────────────────────────────────

export const watchlistApi = {
  /** GET /api/watchlists — 등록된 관심종목 목록 (priority + createdAt 정렬) */
  list: async (): Promise<WatchlistEntryDto[]> => {
    const res = await apiClient.get<WatchlistEntryDto[]>('/watchlists')
    return res.data
  },

  /** POST /api/watchlists — 관심종목 등록. 201 반환 */
  add: async (req: WatchlistAddRequest): Promise<WatchlistEntryDto> => {
    const res = await apiClient.post<WatchlistEntryDto>('/watchlists', req)
    return res.data
  },

  /** DELETE /api/watchlists/{code} — 관심종목 삭제. 204 반환 */
  remove: async (code: string): Promise<void> => {
    await apiClient.delete(`/watchlists/${encodeURIComponent(code)}`)
  },
}
