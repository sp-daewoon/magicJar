import type { StrategyResearchCandidateDto, StrategyResearchReportDto } from '../../api/strategyResearch'
import { parseDslTextToTree } from '../dsl/parser'

export interface StrategyResearchBuilderDraft {
  version: 1
  source: 'strategy-research'
  candidateKey: string
  candidateTitle: string
  code: string
  interval: string
  entryDsl: string
  exitDsl?: string
  stopLossPct: number
  takeProfitPct: number
  maxHoldBars: number
  name: string
  nameKo: string
  conversionNotes: string[]
}

export interface StrategyResearchDraftBuildResult {
  draft: StrategyResearchBuilderDraft | null
  reason?: string
}

const STORAGE_PREFIX = 'magicjar.strategyResearch.builderDraft.'

const ENTRY_DSL_BY_CANDIDATE: Record<string, { dsl: string; notes: string[] }> = {
  MA10_RECLAIM_SCALP: {
    dsl: 'close crosses_above ma(10) AND close >= open',
    notes: [],
  },
  RSI30_REBOUND_SCALP: {
    dsl: 'rsi(14) crosses_above 30 AND close > open AND volume > volume_ma(20) * 0.6',
    notes: ['원 후보의 close[1] 비교는 현재 빌더 DSL에서 직접 표현되지 않아 양봉 조건으로 근사했습니다.'],
  },
  VOLUME_WASHOUT_REBOUND: {
    dsl: 'close > open AND volume > volume_ma(20) * 1.5',
    notes: ['원 후보의 직전 candle 급락·직전 종가 회복 조건은 현재 빌더 DSL에서 직접 표현되지 않아 거래량 양봉 반등 조건으로 근사했습니다.'],
  },
}

export function strategyResearchDraftStorageKey(id: string): string {
  return `${STORAGE_PREFIX}${id}`
}

export function createStrategyResearchDraftId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export function storeStrategyResearchDraft(draft: StrategyResearchBuilderDraft): string {
  const id = createStrategyResearchDraftId()
  window.sessionStorage.setItem(strategyResearchDraftStorageKey(id), JSON.stringify(draft))
  return id
}

export function consumeStrategyResearchDraft(id: string): StrategyResearchBuilderDraft | null {
  const key = strategyResearchDraftStorageKey(id)
  const raw = window.sessionStorage.getItem(key)
  if (!raw) return null
  window.sessionStorage.removeItem(key)
  try {
    const parsed = JSON.parse(raw) as Partial<StrategyResearchBuilderDraft>
    if (
      parsed.version !== 1 ||
      parsed.source !== 'strategy-research' ||
      !parsed.candidateKey ||
      !parsed.code ||
      !parsed.interval ||
      !parsed.entryDsl
    ) {
      return null
    }
    return parsed as StrategyResearchBuilderDraft
  } catch {
    return null
  }
}

export function buildStrategyResearchDraft(
  report: StrategyResearchReportDto,
  candidate: StrategyResearchCandidateDto,
): StrategyResearchDraftBuildResult {
  const entry = ENTRY_DSL_BY_CANDIDATE[candidate.key]
  if (!entry) {
    return { draft: null, reason: '이 후보는 아직 전략 빌더 DSL로 변환할 수 없습니다.' }
  }

  const exitProfile = parseExitProfile(candidate.exitDslDraft)
  const draft: StrategyResearchBuilderDraft = {
    version: 1,
    source: 'strategy-research',
    candidateKey: candidate.key,
    candidateTitle: candidate.title,
    code: report.request.code,
    interval: report.request.interval,
    entryDsl: entry.dsl,
    stopLossPct: exitProfile.stopLossPct ?? report.request.stopLossPct,
    takeProfitPct: exitProfile.takeProfitPct ?? report.request.targetProfitPct,
    maxHoldBars: exitProfile.maxHoldBars ?? report.request.maxHoldBars,
    name: researchStrategyName(report.request.code, candidate.key),
    nameKo: `${candidate.title} (${report.request.code})`,
    conversionNotes: entry.notes,
  }

  try {
    parseDslTextToTree(draft.entryDsl, draft.exitDsl)
  } catch (error) {
    return {
      draft: null,
      reason: error instanceof Error ? `빌더 DSL 변환 검증 실패: ${error.message}` : '빌더 DSL 변환 검증 실패',
    }
  }

  return { draft }
}

function parseExitProfile(exitDslDraft: string): {
  takeProfitPct?: number
  stopLossPct?: number
  maxHoldBars?: number
} {
  return {
    takeProfitPct: numberFromMatch(/take_profit\(([\d.]+)%\)/.exec(exitDslDraft)),
    stopLossPct: numberFromMatch(/stop_loss\(([\d.]+)%\)/.exec(exitDslDraft)),
    maxHoldBars: numberFromMatch(/or\s+(\d+)\s+bars\s+timeout/.exec(exitDslDraft)),
  }
}

function numberFromMatch(match: RegExpExecArray | null): number | undefined {
  if (!match?.[1]) return undefined
  const value = Number(match[1])
  return Number.isFinite(value) ? value : undefined
}

function researchStrategyName(code: string, candidateKey: string): string {
  const normalized = candidateKey.toLowerCase().replace(/[^a-z0-9_]/g, '_')
  return `research_${code}_${normalized}`.slice(0, 64)
}
