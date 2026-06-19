/**
 * 전략별 포지션 REST API 클라이언트 — ADR 0110 전략별 포지션 귀속 (분할매수 R3-a)
 *
 * GET /api/strategies/{id}/position → StrategyPositionDto[]
 *   - 이 전략이 산 물량(수량·평단·진행 회차·실현손익)
 *   - currentPrice·unrealizedPnl·unrealizedPnlPct: 실시간 평가손익 add-only nullable (PR #1035)
 *     · Redis WS 시세캐시 출처, miss → null (mock 합성 금지 — feedback_no_mock_data 정합)
 *   - 보유 없으면 빈 배열 (mock 합성 0건 — feedback_no_mock_data 정합)
 *
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

export interface StrategyPositionDto {
  strategyId: number
  userId: string
  code: string
  codeName?: string | null
  /** 이 전략 보유 수량 (분할 누적 - 청산). 0=청산 */
  quantity: number
  /** 이 전략 평단 (분할 가중평균) */
  avgPrice: number
  /** scale-in 진행 회차 (filledLots) */
  filledLots: number
  /** 이 전략 누적 실현 손익 */
  realizedPnl: number
  /** 전량 청산 여부 (quantity=0) */
  isClosed: boolean
  updatedAt: string
  /** 실시간 현재가 (Redis WS 시세캐시). 캐시 miss → null (mock 합성 금지) */
  currentPrice: number | null
  /** 미실현 평가손익 = (현재가 − 평단) × 수량. 현재가 miss → null */
  unrealizedPnl: number | null
  /** 미실현 평가손익률 (%). 현재가 miss → null */
  unrealizedPnlPct: number | null
  /**
   * currentPrice 출처 (backend 별도 PR).
   *   - 'LIVE'  : KIS WS 실시간 시세
   *   - 'CLOSE' : 마감/장전 → 캔들 종가로 채움 (평가손익 종가기준)
   *   - null    : 시세 미확보 (currentPrice/unrealizedPnl도 null)
   */
  priceSource: 'LIVE' | 'CLOSE' | null
}

type StrategyPositionBackendDto = Omit<Partial<StrategyPositionDto>, 'strategyId'> & {
  strategyId?: number | string
  avgPrice?: number | string
  realizedPnl?: number | string
  currentPrice?: number | string | null
  unrealizedPnl?: number | string | null
  unrealizedPnlPct?: number | string | null
  priceSource?: string | null
}

function toNumber(value: number | string | null | undefined): number {
  if (value === null || value === undefined) return 0
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : 0
}

/** null 허용 number 파싱 — null/undefined/비유한 → null (mock 합성 0 금지) */
function toNullableNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined) return null
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : null
}

function normalize(raw: StrategyPositionBackendDto): StrategyPositionDto {
  return {
    strategyId: toNumber(raw.strategyId),
    userId: String(raw.userId ?? ''),
    code: String(raw.code ?? ''),
    codeName: raw.codeName ?? null,
    quantity: toNumber(raw.quantity),
    avgPrice: toNumber(raw.avgPrice),
    filledLots: toNumber(raw.filledLots),
    realizedPnl: toNumber(raw.realizedPnl),
    isClosed: raw.isClosed ?? raw.quantity === 0,
    updatedAt: String(raw.updatedAt ?? ''),
    currentPrice: toNullableNumber(raw.currentPrice),
    unrealizedPnl: toNullableNumber(raw.unrealizedPnl),
    unrealizedPnlPct: toNullableNumber(raw.unrealizedPnlPct),
    priceSource: raw.priceSource === 'LIVE' || raw.priceSource === 'CLOSE' ? raw.priceSource : null,
  }
}

export const strategyPositionApi = {
  /**
   * GET /api/strategies/{id}/position
   * 전략별 포지션(보유 현황) — 보유 없으면 빈 배열
   */
  get: async (strategyId: string): Promise<StrategyPositionDto[]> => {
    const res = await apiClient.get<StrategyPositionBackendDto[]>(`/strategies/${strategyId}/position`)
    return (res.data ?? []).map(normalize)
  },

  /**
   * GET /api/strategies/{id}/scalein-plan?currentPrice=
   * 분할 매수 계획(회차별 목표 수량·진입 조건·예상 손절가·floor) — ADR 0128 R3
   * 분할 매수 미설정 전략은 400(서버) — 호출 측에서 게이트(scaleIn 있을 때만 호출).
   */
  getScaleInPlan: async (strategyId: string, currentPrice?: number | null): Promise<ScaleInPlanDto> => {
    const params = currentPrice !== null && currentPrice !== undefined ? { currentPrice } : undefined
    const res = await apiClient.get<ScaleInPlanBackendDto>(`/strategies/${strategyId}/scalein-plan`, { params })
    return normalizeScaleInPlan(res.data)
  },
}

// ──────────────────────────────────────────────
// 분할 매수 계획 — ADR 0128 R3 (ScaleInPlanResponse 정합)
//   GET /api/strategies/{id}/scalein-plan?currentPrice=
//   - 기준가: currentPrice > 보유 평단 > 라이브 현재가 순. 없으면 목표 수량 null(미진입 미리보기)
//   - 0% 회차 제외(slices) · floor는 알람만(자동 청산 X)
// ──────────────────────────────────────────────

/** 분할 매수 회차 1건 계획 (ScaleInSlicePlanResponse 정합) */
export interface ScaleInSlicePlanDto {
  /** 0-기반 원본 회차 인덱스 (0% skip 시 비연속 가능) */
  sliceIndex: number
  /** 화면 표시 순번 (1-기반, 0% 제외 후) */
  displayOrder: number
  /** 회차 비중% */
  weightPct: number
  /** 목표 수량 = floor(고정 base × weight% / 100 / 기준가). base·기준가 미정 시 null */
  targetQuantity: number | null
  /** 진입 조건 사람 설명 (예: "1차 진입 — BUY 시그널 발화 시 즉시") */
  triggerDescription: string
}

/** 분할 매수 계획 응답 (ScaleInPlanResponse 정합) */
export interface ScaleInPlanDto {
  strategyId: number
  /** 보유 종목 코드(보유 중일 때). 미보유면 null */
  code: string | null
  /** 보유 여부 — true면 박제 base·평단으로 정확 계산, false면 미리보기 */
  held: boolean
  /** 현재 진행 회차(filledLots). 미보유=0 */
  filledLots: number
  /** 현재 평단(보유 중). 미보유면 null */
  avgPrice: number | null
  /** 고정 base(원) — 1차 진입 시점 buyable 박제값. 미진입(미박제)이면 null → 목표 수량 미산출 */
  baseBudget: number | null
  /** 회차 수량 산정 기준가(원). 없으면 null */
  referencePrice: number | null
  /** 설정된 총 회차 수(0% 포함) */
  totalSlices: number
  /** 실효 회차 수(0% 제외 — slices.length와 동일) */
  effectiveSlices: number
  /** 회차별 계획(0% 회차 제외) */
  slices: ScaleInSlicePlanDto[]
  /** 예상 손절가 = 기준 평단 × (1 − 손절%/100). 평단·손절% 미정 시 null */
  expectedStopLossPrice: number | null
  /** 손절%(전략 fixedStopLoss). null이면 미설정 */
  fixedStopLossPct: number | null
  /** catastrophic floor%(양수) */
  floorPct: number
  /** floor 도달가 = 기준 평단 × (1 − floor%/100). 알람만(자동 청산 X). 평단 미정 시 null */
  floorPrice: number | null
}

type ScaleInSlicePlanBackendDto = Omit<Partial<ScaleInSlicePlanDto>, 'targetQuantity'> & {
  weightPct?: number | string
  targetQuantity?: number | string | null
}

type ScaleInPlanBackendDto = Omit<
  Partial<ScaleInPlanDto>,
  'slices' | 'avgPrice' | 'baseBudget' | 'referencePrice' | 'expectedStopLossPrice' | 'fixedStopLossPct' | 'floorPct' | 'floorPrice'
> & {
  avgPrice?: number | string | null
  baseBudget?: number | string | null
  referencePrice?: number | string | null
  expectedStopLossPrice?: number | string | null
  fixedStopLossPct?: number | string | null
  floorPct?: number | string
  floorPrice?: number | string | null
  slices?: ScaleInSlicePlanBackendDto[]
}

function normalizeSlice(raw: ScaleInSlicePlanBackendDto): ScaleInSlicePlanDto {
  return {
    sliceIndex: toNumber(raw.sliceIndex),
    displayOrder: toNumber(raw.displayOrder),
    weightPct: toNumber(raw.weightPct),
    targetQuantity: toNullableNumber(raw.targetQuantity),
    triggerDescription: String(raw.triggerDescription ?? ''),
  }
}

function normalizeScaleInPlan(raw: ScaleInPlanBackendDto): ScaleInPlanDto {
  return {
    strategyId: toNumber(raw.strategyId),
    code: raw.code ?? null,
    held: raw.held ?? false,
    filledLots: toNumber(raw.filledLots),
    avgPrice: toNullableNumber(raw.avgPrice),
    baseBudget: toNullableNumber(raw.baseBudget),
    referencePrice: toNullableNumber(raw.referencePrice),
    totalSlices: toNumber(raw.totalSlices),
    effectiveSlices: toNumber(raw.effectiveSlices),
    slices: Array.isArray(raw.slices) ? raw.slices.map(normalizeSlice) : [],
    expectedStopLossPrice: toNullableNumber(raw.expectedStopLossPrice),
    fixedStopLossPct: toNullableNumber(raw.fixedStopLossPct),
    floorPct: toNumber(raw.floorPct),
    floorPrice: toNullableNumber(raw.floorPrice),
  }
}
