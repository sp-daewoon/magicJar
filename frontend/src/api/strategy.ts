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
 * T-SIG Phase 3 — ADR 0066 신규 8 endpoint:
 *   POST   /api/strategies               신규
 *   GET    /api/strategies?page=&size=   목록
 *   GET    /api/strategies/{id}          단건
 *   PUT    /api/strategies/{id}          수정
 *   DELETE /api/strategies/{id}          soft 삭제 (status=DELETED)
 *   PUT    /api/strategies/{id}/status   ACTIVE/PAUSED/DISABLED 토글
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

// ──────────────────────────────────────────────
// T-SIG Phase 3 — ADR 0066 신규 타입 & API
// ──────────────────────────────────────────────

/** 전략 상태 4단 (ADR 0066 §3-A) */
export type StrategyStatus = 'ACTIVE' | 'PAUSED' | 'DISABLED' | 'DELETED'

/** ADR 0066 전략 상세 DTO (signal tracking 포함) */
export interface SigStrategyDto {
  id: string
  userId: string
  name: string
  /** 감시 종목 코드 배열 (JSONB) */
  codes: string[]
  /** 감시 인터벌 배열 (JSONB) */
  intervals: string[]
  /** buy_condition DSL */
  buyCondition?: string
  /** sell_condition DSL */
  sellCondition?: string
  status: StrategyStatus
  /** 최근 7일 시그널 카운트 */
  recentSignalCount?: number
  createdAt: string
  updatedAt?: string
}

/** ADR 0066 전략 목록 페이지 */
export interface SigStrategyPage {
  content: SigStrategyDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/** POST /api/strategies 요청 */
export interface CreateSigStrategyRequest {
  name: string
  codes: string[]
  intervals: string[]
  buyCondition?: string
  sellCondition?: string
}

/** PUT /api/strategies/{id} 요청 */
export interface UpdateSigStrategyRequest {
  name?: string
  codes?: string[]
  intervals?: string[]
  buyCondition?: string
  sellCondition?: string
}

export const sigStrategyApi = {
  /**
   * GET /api/strategies?userId=&page=&size=
   * 전략 목록 (페이징) — userId: Long (StrategiesController 정합)
   */
  list: async (userId: string, page = 0, size = 20): Promise<SigStrategyPage> => {
    const res = await apiClient.get<SigStrategyPage>(
      `/strategies?userId=${encodeURIComponent(userId)}&page=${page}&size=${size}`,
    )
    return res.data
  },

  /**
   * GET /api/strategies/{id}
   * 전략 단건 조회
   */
  get: async (id: string): Promise<SigStrategyDto> => {
    const res = await apiClient.get<SigStrategyDto>(`/strategies/${id}`)
    return res.data
  },

  /**
   * POST /api/strategies
   * 신규 전략 생성
   */
  create: async (req: CreateSigStrategyRequest): Promise<SigStrategyDto> => {
    const res = await apiClient.post<SigStrategyDto>('/strategies', req)
    return res.data
  },

  /**
   * PUT /api/strategies/{id}
   * 전략 수정
   */
  update: async (id: string, req: UpdateSigStrategyRequest): Promise<SigStrategyDto> => {
    const res = await apiClient.put<SigStrategyDto>(`/strategies/${id}`, req)
    return res.data
  },

  /**
   * DELETE /api/strategies/{id}
   * soft 삭제 (status → DELETED)
   */
  delete: async (id: string): Promise<void> => {
    await apiClient.delete(`/strategies/${id}`)
  },

  /**
   * PUT /api/strategies/{id}/status
   * 상태 토글 (ACTIVE·PAUSED·DISABLED)
   */
  toggleStatus: async (id: string, status: StrategyStatus): Promise<SigStrategyDto> => {
    const res = await apiClient.put<SigStrategyDto>(`/strategies/${id}/status`, { status })
    return res.data
  },
}
