/**
 * Strategy REST API 클라이언트
 *
 * T-DSL PR-36 backend-core 7 endpoint LOCK:
 *   POST   /api/strategy/validate
 *   POST   /api/strategy/{id}/evaluate
 *   POST   /api/strategy
 *   GET    /api/strategy?userId=
 *   GET    /api/strategy/{id}
 *   DELETE /api/strategy/{id}
 *   PUT    /api/strategy/{id}/enable
 *
 * spec §3-3 모듈 인터페이스 동결 — backend 변경 0건
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// DTO 타입 (backend T-DSL StrategyController LOCK)
// ──────────────────────────────────────────────

/** POST /api/strategy/validate 요청 */
export interface ValidateRequest {
  expr: string
}

/** POST /api/strategy/validate 응답 — StrategyComplexityDto */
export interface StrategyComplexityDto {
  astDepth: number
  nodeCount: number
  indicatorCallCount: number
  estimatedEvalTimeMs: number
  complexityClass: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
}

/** POST /api/strategy/{id}/evaluate 요청 */
export interface EvaluateRequest {
  code: string
  interval: string
}

/** POST /api/strategy/{id}/evaluate 응답 */
export interface EvaluateResponse {
  strategyId: string
  signal: 'BUY' | 'SELL' | 'HOLD'
  strength: number
  reason: string
  evalTimeMs: number
}

/** POST /api/strategy 요청 — CreateStrategyRequest */
export interface CreateStrategyRequest {
  userId?: string
  name: string
  nameKo?: string
  /** DSL entry 표현식 (buy_condition) */
  entry: string
  /** DSL exit 표현식 (sell_condition) — optional */
  exit?: string
  universe?: string
  tf?: string
}

/** POST /api/strategy 응답 */
export interface CreateStrategyResponse {
  strategyId: string
  complexity: StrategyComplexityDto
}

/** 전략 단건 DTO */
export interface StrategyDto {
  id: string
  userId: string
  name: string
  nameKo?: string
  entry: string
  exit?: string
  universe?: string
  tf?: string
  enabled: boolean
  createdAt: string
  complexity?: StrategyComplexityDto
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const strategyApi = {
  /**
   * POST /api/strategy/validate
   * DSL 표현식 복잡도 검증 → StrategyComplexityDto
   */
  validate: async (req: ValidateRequest): Promise<StrategyComplexityDto> => {
    const res = await apiClient.post<StrategyComplexityDto>('/strategy/validate', req)
    return res.data
  },

  /**
   * POST /api/strategy/{id}/evaluate
   * 단건 종목·인터벌로 DSL 평가 → 신호 결과
   */
  evaluate: async (strategyId: string, req: EvaluateRequest): Promise<EvaluateResponse> => {
    const res = await apiClient.post<EvaluateResponse>(`/strategy/${strategyId}/evaluate`, req)
    return res.data
  },

  /**
   * POST /api/strategy
   * 전략 저장 → strategyId 반환
   */
  create: async (req: CreateStrategyRequest): Promise<CreateStrategyResponse> => {
    const res = await apiClient.post<CreateStrategyResponse>('/strategy', req)
    return res.data
  },

  /**
   * GET /api/strategy?userId=
   * 사용자별 전략 목록
   */
  list: async (userId?: string): Promise<StrategyDto[]> => {
    const params = userId ? `?userId=${encodeURIComponent(userId)}` : ''
    const res = await apiClient.get<StrategyDto[]>(`/strategy${params}`)
    return res.data
  },

  /**
   * GET /api/strategy/{id}
   * 단일 전략 조회
   */
  get: async (id: string): Promise<StrategyDto> => {
    const res = await apiClient.get<StrategyDto>(`/strategy/${id}`)
    return res.data
  },

  /**
   * DELETE /api/strategy/{id}
   * 전략 삭제
   */
  delete: async (id: string): Promise<void> => {
    await apiClient.delete(`/strategy/${id}`)
  },

  /**
   * PUT /api/strategy/{id}/enable
   * 전략 활성화 토글
   */
  enable: async (id: string): Promise<void> => {
    await apiClient.put(`/strategy/${id}/enable`)
  },
}
