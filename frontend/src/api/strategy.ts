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
 * 빌더 저장은 /api/strategies로 정렬해 전략 목록·자동매매 운영 흐름과 같은 저장소를 사용한다.
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
export type EvaluateReason =
  | string
  | number
  | boolean
  | null
  | Record<string, unknown>
  | unknown[]

export interface EvaluateResponse {
  strategyId: string | number
  signal: 'BUY' | 'SELL' | 'HOLD' | (string & {})
  strength: number | string
  /** 백엔드 reason은 Map<String, Any>; 오래된 mock은 문자열을 보낼 수 있다. */
  reason: EvaluateReason
  evalTimeMs: number | string
}

/** POST /api/strategy 요청 — CreateStrategyRequest (ADR 0089 — userId number) */
export interface CreateStrategyRequest {
  userId?: number
  name: string
  nameKo?: string
  /** DSL entry 표현식 (buy_condition) */
  entry: string
  /** DSL exit 표현식 (sell_condition) — optional */
  exit?: string
  universe?: string
  tf?: string
  stopLossPct?: number | string | null
  takeProfitPct?: number | string | null
}

/** POST /api/strategy 응답 */
export interface CreateStrategyResponse {
  strategyId: string
  complexity: StrategyComplexityDto
}

/** 전략 단건 DTO (ADR 0089 — userId number) */
export interface StrategyDto {
  id: string
  userId: number
  name: string
  nameKo?: string
  entry: string
  exit?: string
  universe?: string
  tf?: string
  stopLossPct?: number | string | null
  takeProfitPct?: number | string | null
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
  list: async (userId?: number): Promise<StrategyDto[]> => {
    const params = userId !== undefined ? `?userId=${userId}` : ''
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

/** 전략 상태 (ADR 0066 §3-A + BUY_BLOCKED risk 보호 상태) */
export type StrategyStatus = 'ACTIVE' | 'BUY_BLOCKED' | 'PAUSED' | 'DISABLED' | 'DELETED'

/** ADR 0066 전략 상세 DTO (signal tracking 포함, ADR 0089 — userId number) */
export interface SigStrategyDto {
  id: string
  userId: number
  name: string
  nameKo?: string
  universe?: string
  tf?: string
  /** 감시 종목 코드 배열 (JSONB) */
  codes: string[]
  /** 종목 코드별 종목명 */
  codeNames?: Record<string, string>
  /** 감시 인터벌 배열 (JSONB) */
  intervals: string[]
  /** buy_condition DSL */
  buyCondition?: string
  /** sell_condition DSL */
  sellCondition?: string
  /** 자동매매 hard stop 손절 비율 (%) */
  stopLossPct?: number | string | null
  /** 보유 포지션 평가 수익률 기준 익절 비율 (%) */
  takeProfitPct?: number | string | null
  complexity?: StrategyComplexityDto
  status: StrategyStatus
  /** 자동매매 시작 가능 여부 */
  activationReady?: boolean
  /** 자동매매 시작 차단 사유 */
  activationBlockedReason?: string | null
  /** 최근 7일 시그널 카운트 */
  recentSignalCount?: number
  createdAt: string
  updatedAt?: string
  enabled?: boolean
  disabledReason?: string
  lastDisabledAt?: string | null
}

type SigStrategyBackendDto = Omit<Partial<SigStrategyDto>, 'id' | 'userId'> & {
  id: string | number
  userId: string | number
  entry?: string
  exit?: string
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

type SigStrategyBackendPage = Omit<Partial<SigStrategyPage>, 'content'> & {
  content?: SigStrategyBackendDto[]
}

/** POST /api/strategies 요청 */
export interface CreateSigStrategyRequest {
  userId: number
  name: string
  nameKo?: string
  entry?: string
  exit?: string
  universe?: string
  tf?: string
  stopLossPct?: number | string | null
  takeProfitPct?: number | string | null
  codes?: string[]
  intervals?: string[]
  buyCondition?: string
  sellCondition?: string
}

/** PUT /api/strategies/{id} 요청 */
export interface UpdateSigStrategyRequest {
  userId: number
  name: string
  nameKo?: string
  entry?: string
  exit?: string
  universe?: string
  tf?: string
  stopLossPct?: number | string | null
  takeProfitPct?: number | string | null
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
  list: async (userId: number, page = 0, size = 20): Promise<SigStrategyPage> => {
    const res = await apiClient.get<SigStrategyBackendPage>(
      `/strategies?userId=${userId}&page=${page}&size=${size}`,
    )
    return normalizeSigStrategyPage(res.data, page, size)
  },

  /**
   * GET /api/strategies/{id}
   * 전략 단건 조회
   */
  get: async (id: string): Promise<SigStrategyDto> => {
    const res = await apiClient.get<SigStrategyBackendDto>(`/strategies/${id}`)
    return normalizeSigStrategy(res.data)
  },

  /**
   * POST /api/strategies
   * 신규 전략 생성
   */
  create: async (req: CreateSigStrategyRequest): Promise<SigStrategyDto> => {
    const res = await apiClient.post<SigStrategyBackendDto>('/strategies', toSigStrategyRequestBody(req))
    return normalizeSigStrategy(res.data)
  },

  /**
   * PUT /api/strategies/{id}
   * 전략 수정
   */
  update: async (id: string, req: UpdateSigStrategyRequest): Promise<SigStrategyDto> => {
    const res = await apiClient.put<SigStrategyBackendDto>(`/strategies/${id}`, toSigStrategyRequestBody(req))
    return normalizeSigStrategy(res.data)
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
   * 상태 토글 (ACTIVE·PAUSED·DELETED, BUY_BLOCKED·DISABLED는 시스템 보호 상태)
   */
  toggleStatus: async (id: string, status: StrategyStatus): Promise<SigStrategyDto> => {
    const res = await apiClient.put<SigStrategyBackendDto>(`/strategies/${id}/status`, { status })
    return normalizeSigStrategy(res.data)
  },
}

function toSigStrategyRequestBody(req: CreateSigStrategyRequest | UpdateSigStrategyRequest) {
  const entry = req.entry ?? req.buyCondition
  if (!entry) {
    throw new Error('매수 조건이 없습니다')
  }
  return {
    userId: req.userId,
    name: req.name,
    nameKo: req.nameKo,
    entry,
    exit: req.exit ?? req.sellCondition,
    universe: req.universe ?? 'KOSPI',
    tf: req.tf ?? req.intervals?.[0] ?? '1d',
    stopLossPct: req.stopLossPct ?? null,
    takeProfitPct: req.takeProfitPct ?? null,
    codes: req.codes ?? [],
    intervals: req.intervals ?? (req.tf ? [req.tf] : []),
  }
}

function asStringArray(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map((item) => String(item)).filter(Boolean)
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed) return []
    try {
      const parsed = JSON.parse(trimmed) as unknown
      if (Array.isArray(parsed)) {
        return parsed.map((item) => String(item)).filter(Boolean)
      }
    } catch {
      return [trimmed]
    }
  }
  return []
}

function asCodeNames(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>)
      .map(([code, name]) => [code, String(name)])
      .filter(([code, name]) => code.trim() && name.trim()),
  )
}

function normalizeStatus(raw: SigStrategyBackendDto): StrategyStatus {
  if (
    raw.status === 'ACTIVE' ||
    raw.status === 'BUY_BLOCKED' ||
    raw.status === 'PAUSED' ||
    raw.status === 'DISABLED' ||
    raw.status === 'DELETED'
  ) {
    return raw.status
  }
  return raw.enabled === false ? 'DISABLED' : 'ACTIVE'
}

function normalizeSigStrategy(raw: SigStrategyBackendDto): SigStrategyDto {
  const codes = asStringArray(raw.codes)
  const intervals = asStringArray(raw.intervals)
  const tf = raw.tf ? String(raw.tf) : undefined
  return {
    ...raw,
    id: String(raw.id),
    userId: Number(raw.userId),
    name: raw.name ?? `strategy-${raw.id}`,
    nameKo: raw.nameKo,
    universe: raw.universe,
    tf,
    stopLossPct: raw.stopLossPct ?? null,
    takeProfitPct: raw.takeProfitPct ?? null,
    codes,
    codeNames: asCodeNames(raw.codeNames),
    intervals: intervals.length > 0 ? intervals : tf ? [tf] : [],
    buyCondition: raw.buyCondition ?? raw.entry,
    sellCondition: raw.sellCondition ?? raw.exit,
    complexity: raw.complexity,
    status: normalizeStatus(raw),
    activationReady: raw.activationReady,
    activationBlockedReason: raw.activationBlockedReason ?? null,
    createdAt: raw.createdAt ?? '',
    updatedAt: raw.updatedAt,
    enabled: raw.enabled,
    disabledReason: raw.disabledReason,
    lastDisabledAt: raw.lastDisabledAt ?? null,
  }
}

function normalizeSigStrategyPage(raw: SigStrategyBackendPage, page: number, size: number): SigStrategyPage {
  const totalPages = raw.totalPages ?? 0
  const currentPage = raw.page ?? page
  return {
    content: (raw.content ?? []).map(normalizeSigStrategy),
    page: currentPage,
    size: raw.size ?? size,
    totalElements: raw.totalElements ?? raw.content?.length ?? 0,
    totalPages,
    last: raw.last ?? (totalPages === 0 || currentPage + 1 >= totalPages),
  }
}
