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
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// enum / literal
// ──────────────────────────────────────────────

export type RiskVerdict = 'ALLOWED' | 'BLOCKED' | 'AUTO_PAUSED'

export type DllMode = 'ABSOLUTE' | 'PERCENT'

// ──────────────────────────────────────────────
// RiskConfig DTO
// ──────────────────────────────────────────────

/** A: 포지션 한도 설정 */
export interface PositionLimitConfig {
  /** 절대 한도 (원) */
  absoluteLimit: number
  /** 비율 한도 (0~1, 예: 0.2 = 20%) */
  percentLimit: number
}

/** B: 일일 손실 한도 (DLL) 설정 */
export interface DllConfig {
  /** 전역 일일 손실 한도 (원) */
  globalDll: number
  /** 사용자 선택 모드: 절대 or % */
  mode: DllMode
  /** 절대 DLL (원) */
  absoluteDll: number
  /** % DLL (0~1) */
  percentDll: number
}

/** C: 동시 보유 종목 한도 */
export interface MaxHoldingConfig {
  maxHoldingCount: number
}

/** D: 거래 빈도 한도 */
export interface TradeFrequencyConfig {
  maxTradesPerMinute: number
  maxTradesPerDay: number
}

/** Risk Config DTO (전체) */
export interface RiskConfigDto {
  id: string
  userId: string
  positionLimit: PositionLimitConfig
  dll: DllConfig
  maxHolding: MaxHoldingConfig
  tradeFrequency: TradeFrequencyConfig
  updatedAt: string
}

/** PUT /api/risk-config 요청 바디 */
export type RiskConfigUpdateRequest = Omit<RiskConfigDto, 'id' | 'userId' | 'updatedAt'>

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
   * GET /api/risk-config
   * 현재 리스크 설정 조회 (default seed 포함)
   */
  getConfig: async (): Promise<RiskConfigDto> => {
    const res = await apiClient.get<RiskConfigDto>('/risk-config')
    return res.data
  },

  /**
   * PUT /api/risk-config
   * 리스크 설정 갱신
   */
  updateConfig: async (req: RiskConfigUpdateRequest): Promise<RiskConfigDto> => {
    const res = await apiClient.put<RiskConfigDto>('/risk-config', req)
    return res.data
  },

  /**
   * POST /api/risk-config/reset
   * default seed로 초기화
   */
  resetConfig: async (): Promise<RiskConfigDto> => {
    const res = await apiClient.post<RiskConfigDto>('/risk-config/reset')
    return res.data
  },

  /**
   * GET /api/risk-events
   * 리스크 이벤트 시계열 (페이징)
   */
  listEvents: async (params: RiskEventListParams = {}): Promise<RiskEventPage> => {
    const searchParams = new URLSearchParams()
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
   * GET /api/risk-events/{id}
   * 리스크 이벤트 단건
   */
  getEvent: async (id: string): Promise<RiskEventDto> => {
    const res = await apiClient.get<RiskEventDto>(`/risk-events/${id}`)
    return res.data
  },
}
