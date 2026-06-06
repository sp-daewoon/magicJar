import axios from 'axios'
import apiClient from '../lib/axios'

const AI_DISCOVERY_TIMEOUT_MS = 310_000

export interface StrategyResearchRequestDto {
  userId: string
  code: string
  interval: string
  from: string
  to: string
  targetProfitPct: number
  stopLossPct: number
  maxHoldBars: number
}

export interface StrategyResearchAiDiscoveryRequestDto {
  userId: string
  query: string
  presetId?: string | null
  market?: 'KOSPI' | 'KOSDAQ' | 'KONEX' | ''
  limit: number
  interval?: string
  from?: string
  to?: string
}

export interface StrategyResearchAiDiscoveryResponseDto {
  query: string
  presetId: string | null
  market: 'KOSPI' | 'KOSDAQ' | 'KONEX' | '' | null
  limit: number
  provider: string
  generatedAt: string
  warnings: string[]
  items: StrategyResearchAiDiscoveryItemDto[]
}

export interface StrategyResearchAiDiscoveryLatestDto {
  report: StrategyResearchAiDiscoveryResponseDto | null
}

export type StrategyResearchAiDiscoveryItemStatus = 'NEW' | 'KEPT' | 'DISMISSED'

export interface StrategyResearchAiDiscoveryItemStatusRequestDto {
  userId: string
  itemKey: string
  status: StrategyResearchAiDiscoveryItemStatus
}

export interface StrategyResearchAiDiscoveryItemDto {
  itemKey: string
  code: string | null
  name: string
  market: string | null
  sector: string | null
  theme: string | null
  aiReason: string
  themes: string[]
  confidence: number
  matched: boolean
  rawName: string | null
  rawCode: string | null
  tradingHalt: boolean
  managementStock: boolean
  status: StrategyResearchAiDiscoveryItemStatus
  /**
   * 발굴 요청 interval 기준 보유 캔들 수.
   * backend가 내려줄 때만 존재(add-only). null/undefined면 "알 수 없음"으로 취급해 캔들 가져오기 UI를 노출하지 않는다.
   */
  candleCount?: number | null
  /** 리서치용 캔들 보유 여부(backend 제공 시). 캔들 0건 후보 판정 보조. */
  hasResearchCandles?: boolean | null
}

export interface StrategyResearchAiDiscoveryPresetDto {
  id: string
  label: string
  description: string
  promptText: string
  sortOrder: number
  active: boolean
  builtIn: boolean
  updatedAt: string
}

export interface StrategyResearchAiDiscoveryPresetUpdateRequestDto {
  label: string
  description: string
  promptText: string
  sortOrder: number
  active: boolean
}

export interface StrategyResearchAiDiscoveryPresetCreateRequestDto {
  id?: string | null
  label: string
  description: string
  promptText: string
  sortOrder?: number | null
  active: boolean
}

export interface StrategyResearchAiDiscoveryPromptPreviewRequestDto {
  userId: string
  query: string
  presetId?: string | null
  market?: 'KOSPI' | 'KOSDAQ' | 'KONEX' | ''
  limit: number
}

export interface StrategyResearchAiDiscoveryPromptPreviewDto {
  systemPrompt: string
  preset: StrategyResearchAiDiscoveryPresetDto | null
  userQuery: string
  finalPrompt: string
  generatedAt: string
  warnings: string[]
}

export interface StrategyResearchReportDto {
  request: StrategyResearchRequestEchoDto
  candleCount: number
  marketRegime: StrategyResearchMarketRegimeDto
  warnings: string[]
  candles: StrategyResearchCandleDto[]
  candidates: StrategyResearchCandidateDto[]
}

export interface StrategyResearchRequestEchoDto {
  userId: string
  code: string
  interval: string
  from: string
  to: string
  targetProfitPct: number
  stopLossPct: number
  maxHoldBars: number
}

export interface StrategyResearchMarketRegimeDto {
  type: 'DOWNTREND' | 'UPTREND' | 'SIDEWAYS' | 'UNKNOWN' | (string & {})
  trendPct: number
  volatilityPct: number
  explanation: string
}

export interface StrategyResearchCandleDto {
  ts: string
  open: string
  high: string
  low: string
  close: string
  volume: number
}

export interface StrategyResearchCandidateDto {
  key: string
  title: string
  summary: string
  marketFit: string
  entryRules: string[]
  exitRules: string[]
  riskRules: string[]
  entryDslDraft: string
  exitDslDraft: string
  stats: StrategyResearchStatsDto
  trades: StrategyResearchTradeDto[]
  score: number
}

export interface StrategyResearchStatsDto {
  trades: number
  wins: number
  losses: number
  winRatePct: number
  totalReturnPct: number
  avgTradeReturnPct: number
  bestTradePct: number
  worstTradePct: number
  maxDrawdownPct: number
  maxConsecutiveLosses: number
  profitFactor: number | null
}

export interface StrategyResearchTradeDto {
  signalTime: string
  entryTime: string
  exitTime: string
  entryPrice: string
  exitPrice: string
  returnPct: number
  exitReason: 'TAKE_PROFIT' | 'STOP_LOSS' | 'TIME_EXIT' | (string & {})
  barsHeld: number
  reason: string
}

export const strategyResearchApi = {
  propose: async (req: StrategyResearchRequestDto): Promise<StrategyResearchReportDto> => {
    const res = await apiClient.post<StrategyResearchReportDto>('/strategy-research/proposals', req)
    return res.data
  },
  discoverWithAi: async (
    req: StrategyResearchAiDiscoveryRequestDto,
  ): Promise<StrategyResearchAiDiscoveryResponseDto> => {
    const body = {
      ...req,
      market: req.market || null,
      presetId: req.presetId || null,
    }
    const res = await apiClient.post<StrategyResearchAiDiscoveryResponseDto>('/strategy-research/ai-discovery', body, {
      timeout: AI_DISCOVERY_TIMEOUT_MS,
    })
    return res.data
  },
  getLatestAiDiscovery: async (userId: string): Promise<StrategyResearchAiDiscoveryResponseDto | null> => {
    const res = await apiClient.get<StrategyResearchAiDiscoveryLatestDto>('/strategy-research/ai-discovery/latest', {
      params: { userId },
    })
    return res.data.report
  },
  updateAiDiscoveryItemStatus: async (
    req: StrategyResearchAiDiscoveryItemStatusRequestDto,
  ): Promise<StrategyResearchAiDiscoveryResponseDto> => {
    const res = await apiClient.patch<StrategyResearchAiDiscoveryResponseDto>(
      '/strategy-research/ai-discovery/item-status',
      req,
    )
    return res.data
  },
  clearAiDiscovery: async (userId: string): Promise<void> => {
    await apiClient.delete('/strategy-research/ai-discovery/latest', {
      params: { userId },
    })
  },
  getAiDiscoveryPresets: async (): Promise<StrategyResearchAiDiscoveryPresetDto[]> => {
    const res = await apiClient.get<StrategyResearchAiDiscoveryPresetDto[]>('/strategy-research/ai-discovery/presets')
    return res.data
  },
  createAiDiscoveryPreset: async (
    req: StrategyResearchAiDiscoveryPresetCreateRequestDto,
  ): Promise<StrategyResearchAiDiscoveryPresetDto> => {
    const res = await apiClient.post<StrategyResearchAiDiscoveryPresetDto>(
      '/strategy-research/ai-discovery/presets',
      req,
    )
    return res.data
  },
  updateAiDiscoveryPreset: async (
    presetId: string,
    req: StrategyResearchAiDiscoveryPresetUpdateRequestDto,
  ): Promise<StrategyResearchAiDiscoveryPresetDto> => {
    const res = await apiClient.put<StrategyResearchAiDiscoveryPresetDto>(
      `/strategy-research/ai-discovery/presets/${encodeURIComponent(presetId)}`,
      req,
    )
    return res.data
  },
  previewAiDiscoveryPrompt: async (
    req: StrategyResearchAiDiscoveryPromptPreviewRequestDto,
  ): Promise<StrategyResearchAiDiscoveryPromptPreviewDto> => {
    const body = {
      ...req,
      market: req.market || null,
      presetId: req.presetId || null,
    }
    const res = await apiClient.post<StrategyResearchAiDiscoveryPromptPreviewDto>(
      '/strategy-research/ai-discovery/prompt-preview',
      body,
    )
    return res.data
  },
}

export function strategyResearchErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { message?: string; detail?: string; error?: string } | undefined
    return data?.message ?? data?.detail ?? data?.error ?? error.message
  }
  return error instanceof Error ? error.message : '전략 리서치 처리 중 오류가 발생했습니다'
}
