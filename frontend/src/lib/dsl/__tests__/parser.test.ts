/**
 * DSL 파서 단위 테스트 (5 fixture + 화이트리스트 위반)
 *
 * ADR 0022 §2-4 화이트리스트 위반 throw 검증
 */
import { describe, expect, it } from 'vitest'
import { parseDslText, parseDslTextToTree } from '../parser'

describe('parseDslText — KIS 호환 입력 → BlockTree', () => {
  it('fixture1 — rsi(14) < 30', () => {
    const tree = parseDslText('rsi(14) < 30')
    expect(tree.kind).toBe('comparison')
    if (tree.kind === 'comparison') {
      expect(tree.op).toBe('<')
      expect(tree.left?.kind).toBe('indicator')
      if (tree.left?.kind === 'indicator') {
        expect(tree.left.id).toBe('rsi')
      }
      expect(tree.right?.kind).toBe('literal')
    }
  })

  it('fixture2 — AND 논리', () => {
    const tree = parseDslText('(rsi(14) < 30) AND (ma(5) crosses_above ma(20))')
    expect(tree.kind).toBe('logical')
    if (tree.kind === 'logical') {
      expect(tree.op).toBe('AND')
      expect(tree.left?.kind).toBe('comparison')
      expect(tree.right?.kind).toBe('comparison')
    }
  })

  it('fixture3 — 산술 표현식', () => {
    const tree = parseDslText('volume > volume_ma(20) * 1.5')
    expect(tree.kind).toBe('comparison')
    if (tree.kind === 'comparison') {
      expect(tree.op).toBe('>')
      expect(tree.left?.kind).toBe('candle')
      expect(tree.right?.kind).toBe('arithmetic')
    }
  })

  it('prev_close 캔들 식별자를 산술 표현식에서 파싱한다', () => {
    const tree = parseDslText('open >= prev_close * 1.025')

    expect(tree.kind).toBe('comparison')
    if (tree.kind === 'comparison') {
      expect(tree.left).toEqual({ kind: 'candle', field: 'open' })
      expect(tree.right?.kind).toBe('arithmetic')
      if (tree.right?.kind === 'arithmetic') {
        expect(tree.right.left).toEqual({ kind: 'candle', field: 'prev_close' })
      }
    }
  })

  it('fixture4 — pattern_detected', () => {
    const tree = parseDslText('pattern_detected("hammer")')
    expect(tree.kind).toBe('pattern')
    if (tree.kind === 'pattern') {
      expect(tree.patternId).toBe('hammer')
      expect(tree.func).toBe('pattern_detected')
    }
  })

  it('fixture5 — OR 논리', () => {
    const tree = parseDslText('rsi(14) > 70 OR rsi(14) < 30')
    expect(tree.kind).toBe('logical')
    if (tree.kind === 'logical') {
      expect(tree.op).toBe('OR')
    }
  })
})

describe('parseDslText — 화이트리스트 위반 → throw', () => {
  it('미정의 함수 unknown_func() → ParseError', () => {
    expect(() => parseDslText('ma(5) crosses_above unknown_func()')).toThrow()
  })

  it('허용되지 않는 패턴 ID → ParseError', () => {
    expect(() => parseDslText('pattern_detected("not_a_real_pattern")')).toThrow()
  })
})

describe('parseDslText — trendline(id) 추세선 참조 (추세매매 Phase 2, ADR 0140)', () => {
  it('close crosses_above trendline(123) → comparison(candle close, crosses_above, trendline 123)', () => {
    const tree = parseDslText('close crosses_above trendline(123)')
    expect(tree.kind).toBe('comparison')
    if (tree.kind === 'comparison') {
      expect(tree.op).toBe('crosses_above')
      expect(tree.left?.kind).toBe('candle')
      expect(tree.right?.kind).toBe('trendline')
      if (tree.right?.kind === 'trendline') {
        expect(tree.right.trendlineId).toBe(123)
      }
    }
  })

  it('close crosses_below trendline(7) → 청산 방향도 파싱', () => {
    const tree = parseDslText('close crosses_below trendline(7)')
    expect(tree.kind).toBe('comparison')
    if (tree.kind === 'comparison' && tree.right?.kind === 'trendline') {
      expect(tree.op).toBe('crosses_below')
      expect(tree.right.trendlineId).toBe(7)
    }
  })

  it('AND 조합 안에서도 trendline 참조 파싱', () => {
    const tree = parseDslText('(close crosses_above trendline(5)) AND (rsi(14) < 70)')
    expect(tree.kind).toBe('logical')
  })

  it('소수 id → ParseError', () => {
    expect(() => parseDslText('close crosses_above trendline(1.5)')).toThrow()
  })

  it('id 없는 trendline() → ParseError', () => {
    expect(() => parseDslText('close crosses_above trendline()')).toThrow()
  })

  it('0 id → ParseError (양의 정수만)', () => {
    expect(() => parseDslText('close crosses_above trendline(0)')).toThrow()
  })
})

describe('parseDslTextToTree', () => {
  it('entry만 있는 경우', () => {
    const tree = parseDslTextToTree('rsi(14) < 30')
    expect(tree.entry).toBeDefined()
    expect(tree.exit).toBeUndefined()
  })

  it('빈 문자열은 undefined', () => {
    const tree = parseDslTextToTree('')
    expect(tree.entry).toBeUndefined()
  })
})
