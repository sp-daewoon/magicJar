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
}
