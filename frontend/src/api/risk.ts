/**
 * Risk REST API 클라이언트 (T-RISK Phase 3 — ADR 0067)
 *
 * GET    /api/risk-config              → RiskConfigDto
 * PUT    /api/risk-config              → RiskConfigDto
 * POST   /api/risk-config/reset        → RiskConfigDto
 * GET    /api/risk-events?from=&to=&page=&size=  → RiskEventPage
 * GET    /api/risk-events/{id}         → RiskEventDto
 *
 * 룰 5: LLM provider import 0건
 *
 * [hotfix] backend flat 구조 정합 — 이전 nested(positionLimit·dll·maxHolding·tradeFrequency)
 * 가정 제거, backend 실제 응답 키와 1:1 매핑.
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// enum / literal
// ──────────────────────────────────────────────

export type RiskVerdict = 'ALLOWED' | 'BLOCKED' | 'AUTO_PAUSED'

// ──────────────────────────────────────────────
// RiskConfig DTO — backend flat 구조 정합
// ──────────────────────────────────────────────

/**
 * GET/PUT /api/risk-config 응답 구조 (flat).
 * backend 실제 응답 키와 1:1 일치.
 *
 * 단위 주의:
 *   maxPositionPercent: 퍼센트 단위 (10 = 10%)  — backend가 percent 단위 저장
 *   dllPct:             퍼센트 단위 (3 = 3%)
 */
export interface RiskConfigDto {
  userId: string
  /** 포지션 절대 한도 (원) */
  maxPositionAmount: number
  /** 포지션 비율 한도 (퍼센트, 예: 10 = 10%) */
  maxPositionPercent: number
  /** 일일 손실 한도 절대값 (원). null = 미설정 */
  dllAbs: number | null
  /** 일일 손실 한도 비율 (퍼센트, 예: 3 = 3%) */
  dllPct: number
  /** DLL 활성 여부 */
  isDailyLossLimitActive: boolean
  /** 동시 보유 가능 최대 종목 수 */
  maxConcurrentPositions: number
  /** 분당 최대 거래 횟수 */
  maxTradesPerMinute: number
  /** 일별 최대 거래 횟수 */
  maxTradesPerDay: number
  /** 연속 BLOCKED → 자동 정지 임계치 (default 10) */
  autoPauseThreshold: number
  updatedAt: string
}

/** PUT /api/risk-config 요청 바디 */
export type RiskConfigUpdateRequest = Omit<RiskConfigDto, 'userId' | 'updatedAt'>

// ──────────────────────────────────────────────
// RiskEvent DTO
// ──────────────────────────────────────────────

export interface RiskEventDto {
  id: string
  userId: string
  verdict: RiskVerdict
  rule: string
  reason: string
  signalId?: string
  strategyId?: string
  strategyName?: string
  ts: string
}

export interface RiskEventPage {
  content: RiskEventDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

export interface RiskEventListParams {
  userId?: string
  from?: string
  to?: string
  page?: number
  size?: number
  verdict?: RiskVerdict
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const riskApi = {
  /**
   * GET /api/risk-config?userId=
   * 현재 리스크 설정 조회 (default seed 포함)
   */
  getConfig: async (userId: string): Promise<RiskConfigDto> => {
    const res = await apiClient.get<RiskConfigDto>(
      `/risk-config?userId=${encodeURIComponent(userId)}`,
    )
    return res.data
  },

  /**
   * PUT /api/risk-config?userId=
   * 리스크 설정 갱신
   */
  updateConfig: async (userId: string, req: RiskConfigUpdateRequest): Promise<RiskConfigDto> => {
    const res = await apiClient.put<RiskConfigDto>(
      `/risk-config?userId=${encodeURIComponent(userId)}`,
      req,
    )
    return res.data
  },

  /**
   * POST /api/risk-config/reset?userId=
   * default seed로 초기화
   */
  resetConfig: async (userId: string): Promise<RiskConfigDto> => {
    const res = await apiClient.post<RiskConfigDto>(
      `/risk-config/reset?userId=${encodeURIComponent(userId)}`,
    )
    return res.data
  },

  /**
   * GET /api/risk-events?userId=
   * 리스크 이벤트 시계열 (페이징)
   */
  listEvents: async (params: RiskEventListParams = {}): Promise<RiskEventPage> => {
    const searchParams = new URLSearchParams()
    if (params.userId) searchParams.set('userId', params.userId)
    if (params.from) searchParams.set('from', params.from)
    if (params.to) searchParams.set('to', params.to)
    if (params.page !== undefined) searchParams.set('page', String(params.page))
    if (params.size !== undefined) searchParams.set('size', String(params.size))
    if (params.verdict) searchParams.set('verdict', params.verdict)

    const qs = searchParams.toString()
    const res = await apiClient.get<RiskEventPage>(`/risk-events${qs ? `?${qs}` : ''}`)
    return res.data
  },

  /**
   * GET /api/risk-events/{id}?userId=
   * 리스크 이벤트 단건
   */
  getEvent: async (id: string, userId: string): Promise<RiskEventDto> => {
    const res = await apiClient.get<RiskEventDto>(
      `/risk-events/${id}?userId=${encodeURIComponent(userId)}`,
    )
    return res.data
  },
}
