import axios from 'axios'
import apiClient from '../lib/axios'

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
  market?: 'KOSPI' | 'KOSDAQ' | 'KONEX' | ''
  limit: number
}

export interface StrategyResearchAiDiscoveryResponseDto {
  query: string
  provider: string
  generatedAt: string
  warnings: string[]
  items: StrategyResearchAiDiscoveryItemDto[]
}

export interface StrategyResearchAiDiscoveryItemDto {
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
    }
    const res = await apiClient.post<StrategyResearchAiDiscoveryResponseDto>('/strategy-research/ai-discovery', body)
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
