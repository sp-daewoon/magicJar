import { describe, expect, it } from 'vitest'
import type { StrategyResearchCandidateDto, StrategyResearchReportDto } from '../../../api/strategyResearch'
import { buildStrategyResearchDraft } from '../builderDraft'

describe('buildStrategyResearchDraft', () => {
  it('MA10 후보를 전략 빌더 DSL draft로 변환한다', () => {
    const result = buildStrategyResearchDraft(report(), candidate('MA10_RECLAIM_SCALP'))

    expect(result.draft?.entryDsl).toBe('close crosses_above ma(10) AND close >= open')
    expect(result.draft?.code).toBe('038110')
    expect(result.draft?.interval).toBe('1m')
    expect(result.draft?.takeProfitPct).toBe(0.8)
    expect(result.draft?.stopLossPct).toBe(0.5)
  })

  it('현재 빌더 DSL이 표현하지 못하는 조건은 근사 note를 남긴다', () => {
    const result = buildStrategyResearchDraft(report(), candidate('VOLUME_WASHOUT_REBOUND'))

    expect(result.draft?.entryDsl).toBe('close > open AND volume > volume_ma(20) * 1.5')
    expect(result.draft?.conversionNotes.join(' ')).toContain('근사')
  })

  it('알 수 없는 후보는 변환하지 않는다', () => {
    const result = buildStrategyResearchDraft(report(), candidate('UNKNOWN_RESEARCH_PATTERN'))

    expect(result.draft).toBeNull()
    expect(result.reason).toContain('변환할 수 없습니다')
  })
})

function report(): StrategyResearchReportDto {
  return {
    request: {
      userId: '74683223',
      code: '038110',
      interval: '1m',
      from: '2026-04-28T00:00:00Z',
      to: '2026-06-02T06:30:00Z',
      targetProfitPct: 1.5,
      stopLossPct: 0.8,
      maxHoldBars: 12,
    },
    candleCount: 120,
    marketRegime: {
      type: 'DOWNTREND',
      trendPct: -12.3,
      volatilityPct: 0.4,
      explanation: '하락장',
    },
    warnings: [],
    candles: [],
    candidates: [],
  }
}

function candidate(key: string): StrategyResearchCandidateDto {
  return {
    key,
    title: '테스트 후보',
    summary: '요약',
    marketFit: '하락장 반등',
    entryRules: [],
    exitRules: [],
    riskRules: [],
    entryDslDraft: 'draft',
    exitDslDraft: 'take_profit(0.8%) or stop_loss(0.5%) or 8 bars timeout',
    stats: {
      trades: 3,
      wins: 1,
      losses: 2,
      winRatePct: 33.33,
      totalReturnPct: -0.1,
      avgTradeReturnPct: -0.03,
      bestTradePct: 0.8,
      worstTradePct: -0.5,
      maxDrawdownPct: 0.5,
      maxConsecutiveLosses: 2,
      profitFactor: 0.8,
    },
    trades: [],
    score: 1.2,
  }
}
