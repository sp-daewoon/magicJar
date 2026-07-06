/**
 * 추세매매 Phase 1 — 추세선 → 전략 draft 변환·판정·저장 라운드트립 검증
 */
import { describe, expect, it, beforeEach } from 'vitest'
import {
  HORIZONTAL_TREND_TOLERANCE,
  TRENDLINE_DEFAULT_STOP_LOSS_PCT,
  buildTrendlineStrategyDraft,
  consumeTrendlineStrategyDraft,
  formatLinePriceForDsl,
  isHorizontalTrendline,
  storeTrendlineStrategyDraft,
  trendlineLinePrice,
} from '../trendlineDraft'

describe('isHorizontalTrendline', () => {
  it('두 앵커 가격이 동일하면 수평선', () => {
    expect(isHorizontalTrendline(70000, 70000)).toBe(true)
  })

  it('0.1% 이내 근접이면 수평선으로 허용', () => {
    // 70000 * 0.0009 = 63 → 오차 60원은 수평
    expect(isHorizontalTrendline(70000, 70060)).toBe(true)
  })

  it('0.1%를 초과하면 기울어진 선', () => {
    // 70000 * 0.001 = 70 → 오차 200원은 비수평
    expect(isHorizontalTrendline(70000, 70200)).toBe(false)
  })

  it('허용 오차 상수는 0.1%', () => {
    expect(HORIZONTAL_TREND_TOLERANCE).toBe(0.001)
  })

  it('비유한/0 가격은 false', () => {
    expect(isHorizontalTrendline(Number.NaN, 100)).toBe(false)
    expect(isHorizontalTrendline(0, 0)).toBe(false)
  })
})

describe('formatLinePriceForDsl', () => {
  it('정수는 정수 문자열', () => {
    expect(formatLinePriceForDsl(70000)).toBe('70000')
  })

  it('소수는 trailing zero 제거', () => {
    expect(formatLinePriceForDsl(70000.5)).toBe('70000.5')
    expect(formatLinePriceForDsl(1234.5000)).toBe('1234.5')
  })
})

describe('trendlineLinePrice', () => {
  it('두 앵커의 평균', () => {
    expect(trendlineLinePrice(70000, 70060)).toBe(70030)
  })
})

describe('buildTrendlineStrategyDraft', () => {
  it('수평 저항선 → 돌파 진입/이탈 청산 DSL + 손절 2% 제안', () => {
    const { draft, reason } = buildTrendlineStrategyDraft({
      code: '005930',
      interval: '1d',
      anchor1Price: 70000,
      anchor2Price: 70000,
      lineType: 'RESISTANCE',
      label: '전고점',
    })
    expect(reason).toBeUndefined()
    expect(draft).not.toBeNull()
    expect(draft!.entryDsl).toBe('close crosses_above 70000')
    expect(draft!.exitDsl).toBe('close crosses_below 70000')
    expect(draft!.stopLossPct).toBe(TRENDLINE_DEFAULT_STOP_LOSS_PCT)
    expect(draft!.linePrice).toBe(70000)
    expect(draft!.lineType).toBe('RESISTANCE')
    expect(draft!.nameKo).toContain('돌파')
    expect(draft!.nameKo).toContain('전고점')
    expect(draft!.name).toMatch(/^trendline_005930_resistance_70000$/)
  })

  it('수평 지지선 → 지지 표시명', () => {
    const { draft } = buildTrendlineStrategyDraft({
      code: '005930',
      interval: '5m',
      anchor1Price: 68000,
      anchor2Price: 68010,
      lineType: 'SUPPORT',
      label: null,
    })
    expect(draft).not.toBeNull()
    expect(draft!.nameKo).toContain('지지')
    // label 없으면 code 사용
    expect(draft!.nameKo).toContain('005930')
    expect(draft!.interval).toBe('5m')
  })

  it('기울어진 선은 변환 거부 + Phase 2 안내', () => {
    const { draft, reason } = buildTrendlineStrategyDraft({
      code: '005930',
      interval: '1d',
      anchor1Price: 70000,
      anchor2Price: 72000,
      lineType: 'RESISTANCE',
    })
    expect(draft).toBeNull()
    expect(reason).toContain('Phase 2')
  })

  it('종목코드가 6자리가 아니면 거부', () => {
    const { draft, reason } = buildTrendlineStrategyDraft({
      code: 'ABC',
      interval: '1d',
      anchor1Price: 70000,
      anchor2Price: 70000,
      lineType: 'SUPPORT',
    })
    expect(draft).toBeNull()
    expect(reason).toContain('종목코드')
  })
})

describe('store / consume 라운드트립', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('저장 후 소비하면 동일 draft 반환 + 소비 후 제거(1회성)', () => {
    const { draft } = buildTrendlineStrategyDraft({
      code: '005930',
      interval: '1d',
      anchor1Price: 70000,
      anchor2Price: 70000,
      lineType: 'RESISTANCE',
    })
    const id = storeTrendlineStrategyDraft(draft!)
    const first = consumeTrendlineStrategyDraft(id)
    expect(first).toEqual(draft)
    // 1회성 — 두 번째 소비는 null
    expect(consumeTrendlineStrategyDraft(id)).toBeNull()
  })

  it('없는 id는 null', () => {
    expect(consumeTrendlineStrategyDraft('nope')).toBeNull()
  })
})
