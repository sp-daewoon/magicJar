/**
 * Signal REST API 클라이언트 (T-SIG Phase 3 — ADR 0066)
 *
 * GET /api/signals?userId=&from=&to=&page=&size=   → PageResponse<SignalDto>
 * GET /api/signals/{id}                             → SignalDto
 *
 * DTO: SignalDto · SignalSide · SignalPage
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// enum / literal
// ──────────────────────────────────────────────

export type SignalSide = 'BUY' | 'SELL' | 'HOLD'

// ──────────────────────────────────────────────
// DTO
// ──────────────────────────────────────────────

export interface SignalDto {
  id: string
  strategyId: string
  strategyName?: string
  userId: string
  code: string
  /** 종목명 (optional — backend enrichment) */
  codeName?: string
  interval: string
  side: SignalSide
  /** 발생 가격 (문자열 원화) */
  price: string
  /** 시그널 발생 규칙 텍스트 (예: "RSI(14) < 30") */
  rule?: string
  /** 시그널 발생 시각 (ISO 8601 KST) */
  ts: string
}

/** GET /api/signals 응답 페이지 래퍼 */
export interface SignalPage {
  content: SignalDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/** GET /api/signals 필터 파라미터 */
export interface SignalListParams {
  userId?: string
  code?: string
  interval?: string
  side?: SignalSide
  from?: string
  to?: string
  page?: number
  size?: number
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const signalApi = {
  /**
   * GET /api/signals
   * 사용자 시그널 시계열 (페이징)
   */
  list: async (params: SignalListParams = {}): Promise<SignalPage> => {
    const searchParams = new URLSearchParams()
    if (params.userId) searchParams.set('userId', params.userId)
    if (params.code) searchParams.set('code', params.code)
    if (params.interval) searchParams.set('interval', params.interval)
    if (params.side) searchParams.set('side', params.side)
    if (params.from) searchParams.set('from', params.from)
    if (params.to) searchParams.set('to', params.to)
    if (params.page !== undefined) searchParams.set('page', String(params.page))
    if (params.size !== undefined) searchParams.set('size', String(params.size))

    const qs = searchParams.toString()
    const res = await apiClient.get<SignalPage>(`/signals${qs ? `?${qs}` : ''}`)
    return res.data
  },

  /**
   * GET /api/signals/{id}?userId=
   * 시그널 단건 조회
   */
  get: async (id: string, userId: string): Promise<SignalDto> => {
    const res = await apiClient.get<SignalDto>(
      `/signals/${id}?userId=${encodeURIComponent(userId)}`,
    )
    return res.data
  },
}
