/**
 * strategyYamlSerializer 단위 테스트
 * - serialize: state → yaml 검증
 * - deserialize: yaml → state 왕복 검증
 */

import { describe, it, expect } from 'vitest'
import { serializeToYaml, deserializeFromYaml } from './strategyYamlSerializer'
import type { StrategyBuilderState } from '@/api/strategy'
import { createInitialState } from '@/api/strategy'

function makeSmaState(): StrategyBuilderState {
  return {
    metadata: {
      name: 'SMA 교차 전략',
      description: '단순이동평균 교차',
      tags: 'MA,모멘텀',
      author: '테스터',
    },
    indicators: [
      { id: 'i1', type: 'sma', alias: 'sma_5', params: { period: 5 } },
      { id: 'i2', type: 'sma', alias: 'sma_20', params: { period: 20 } },
    ],
    entryConditions: {
      logic: 'and',
      conditions: [
        {
          id: 'c1',
          left: 'sma_5',
          operator: 'cross_above',
          compareTo: { type: 'indicator', value: 'sma_20' },
        },
      ],
    },
    exitConditions: {
      logic: 'and',
      conditions: [
        {
          id: 'c2',
          left: 'sma_5',
          operator: 'cross_below',
          compareTo: { type: 'indicator', value: 'sma_20' },
        },
      ],
    },
    risk: { stopLoss: '5', takeProfit: '10', trailingStop: '' },
  }
}

describe('serializeToYaml', () => {
  it('기본 상태 직렬화 — 헤더 주석 포함', () => {
    const yaml = serializeToYaml(createInitialState())
    expect(yaml).toContain('# magicJar 전략 DSL')
    expect(yaml).toContain('strategy:')
  })

  it('이름 직렬화', () => {
    const yaml = serializeToYaml(makeSmaState())
    expect(yaml).toContain('SMA 교차 전략')
  })

  it('태그 직렬화 — 배열 형식', () => {
    const yaml = serializeToYaml(makeSmaState())
    expect(yaml).toContain('tags:')
    expect(yaml).toContain('MA')
    expect(yaml).toContain('모멘텀')
  })

  it('SMA 지표 직렬화 — type + alias + period', () => {
    const yaml = serializeToYaml(makeSmaState())
    expect(yaml).toContain('type: sma')
    expect(yaml).toContain('alias: sma_5')
    expect(yaml).toContain('period: 5')
  })

  it('조건 직렬화 — operator + compareTo', () => {
    const yaml = serializeToYaml(makeSmaState())
    expect(yaml).toContain('operator: cross_above')
    expect(yaml).toContain('type: indicator')
    expect(yaml).toContain('value: sma_20')
  })

  it('리스크 직렬화 — stopLoss + takeProfit', () => {
    const yaml = serializeToYaml(makeSmaState())
    expect(yaml).toContain('stopLoss: -5%')
    expect(yaml).toContain('takeProfit: +10%')
  })

  it('빈 지표 목록 — 빈 배열 표시', () => {
    const yaml = serializeToYaml(createInitialState())
    expect(yaml).toContain('indicators:')
    expect(yaml).toContain('[]')
  })

  it('MACD 지표 직렬화 — fastPeriod/slowPeriod/signalPeriod', () => {
    const state = createInitialState()
    state.indicators = [
      {
        id: 'i1',
        type: 'macd',
        alias: 'macd_12_26',
        params: { fastPeriod: 12, slowPeriod: 26, signalPeriod: 9 },
      },
    ]
    const yaml = serializeToYaml(state)
    expect(yaml).toContain('fastPeriod: 12')
    expect(yaml).toContain('slowPeriod: 26')
    expect(yaml).toContain('signalPeriod: 9')
  })

  it('BB 지표 직렬화 — period + multiplier', () => {
    const state = createInitialState()
    state.indicators = [
      {
        id: 'i1',
        type: 'bb',
        alias: 'bb_20',
        params: { period: 20, multiplier: 2 },
      },
    ]
    const yaml = serializeToYaml(state)
    expect(yaml).toContain('type: bb')
    expect(yaml).toContain('multiplier: 2')
  })
})

describe('deserializeFromYaml', () => {
  it('직렬화 후 역직렬화 — 이름 복원', () => {
    const original = makeSmaState()
    const yaml = serializeToYaml(original)
    const restored = deserializeFromYaml(yaml)
    expect(restored.metadata.name).toBe('SMA 교차 전략')
  })

  it('직렬화 후 역직렬화 — 설명 복원', () => {
    const original = makeSmaState()
    const yaml = serializeToYaml(original)
    const restored = deserializeFromYaml(yaml)
    expect(restored.metadata.description).toBe('단순이동평균 교차')
  })

  it('직렬화 후 역직렬화 — 저자 복원', () => {
    const original = makeSmaState()
    const yaml = serializeToYaml(original)
    const restored = deserializeFromYaml(yaml)
    expect(restored.metadata.author).toBe('테스터')
  })

  it('직렬화 후 역직렬화 — 리스크 복원', () => {
    const original = makeSmaState()
    const yaml = serializeToYaml(original)
    const restored = deserializeFromYaml(yaml)
    expect(restored.risk.stopLoss).toBe('5')
    expect(restored.risk.takeProfit).toBe('10')
  })

  it('직렬화 후 역직렬화 — SMA 지표 복원', () => {
    const original = makeSmaState()
    const yaml = serializeToYaml(original)
    const restored = deserializeFromYaml(yaml)
    expect(restored.indicators).toHaveLength(2)
    expect(restored.indicators[0].type).toBe('sma')
    expect(restored.indicators[0].alias).toBe('sma_5')
    expect((restored.indicators[0].params as { period: number }).period).toBe(5)
  })

  it('직렬화 후 역직렬화 — 진입 조건 복원', () => {
    const original = makeSmaState()
    const yaml = serializeToYaml(original)
    const restored = deserializeFromYaml(yaml)
    expect(restored.entryConditions.conditions).toHaveLength(1)
    expect(restored.entryConditions.conditions[0].left).toBe('sma_5')
    expect(restored.entryConditions.conditions[0].operator).toBe('cross_above')
  })

  it('빈 yaml — 초기 상태 반환', () => {
    const restored = deserializeFromYaml('')
    expect(restored.metadata.name).toBe('')
    expect(restored.indicators).toHaveLength(0)
  })
})

describe('generateAlias', () => {
  it('generateAlias — 중복 없을 때 base 반환', async () => {
    const { generateAlias } = await import('@/api/strategy')
    expect(generateAlias('sma', [])).toBe('sma')
  })

  it('generateAlias — 중복 있을 때 _2 suffix', async () => {
    const { generateAlias } = await import('@/api/strategy')
    expect(generateAlias('sma', ['sma'])).toBe('sma_2')
  })

  it('generateAlias — 연속 중복', async () => {
    const { generateAlias } = await import('@/api/strategy')
    expect(generateAlias('sma', ['sma', 'sma_2'])).toBe('sma_3')
  })
})
