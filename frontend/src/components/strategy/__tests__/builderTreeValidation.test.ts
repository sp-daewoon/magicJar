/**
 * builderTreeValidation 단위 테스트 — 빌더 고도화 B
 */
import { describe, expect, it } from 'vitest'
import { validateTreeRoot, validateBuilderTree } from '../builderTreeValidation'
import type { BlockNode, BlockTree } from '../../../lib/dsl/blockTree'

const completeComparison: BlockNode = {
  kind: 'comparison',
  op: '<',
  left: { kind: 'indicator', id: 'rsi', params: { p0: 14 } },
  right: { kind: 'literal', valueType: 'integer', value: '30' },
}

describe('validateTreeRoot', () => {
  it('루트 없음 — empty=true, issues 없음', () => {
    const r = validateTreeRoot(undefined, '매수')
    expect(r.empty).toBe(true)
    expect(r.complete).toBe(false)
    expect(r.issues).toHaveLength(0)
  })

  it('완성 비교 — complete=true, emptySlotCount 0', () => {
    const r = validateTreeRoot(completeComparison, '매수')
    expect(r.complete).toBe(true)
    expect(r.emptySlotCount).toBe(0)
    expect(r.issues).toHaveLength(0)
  })

  it('한쪽 슬롯 비어있음 — emptySlotCount 1, 경고 1', () => {
    const r = validateTreeRoot(
      { kind: 'comparison', op: '>', left: { kind: 'candle', field: 'close' } },
      '매수',
    )
    expect(r.empty).toBe(false)
    expect(r.complete).toBe(false)
    expect(r.emptySlotCount).toBe(1)
    expect(r.issues[0].message).toContain('1개')
  })

  it('논리 AND 양쪽 미완 — 빈 슬롯 합산', () => {
    const r = validateTreeRoot(
      {
        kind: 'logical',
        op: 'AND',
        left: { kind: 'comparison', op: '>', left: { kind: 'candle', field: 'close' } }, // right 빔(1)
        // right 통째로 빔(1)
      },
      '매수',
    )
    expect(r.emptySlotCount).toBe(2)
  })

  it('NOT operand 비어있음 — 1', () => {
    const r = validateTreeRoot({ kind: 'not' }, '매수')
    expect(r.emptySlotCount).toBe(1)
  })

  it('잎 노드(indicator) — 완성 취급', () => {
    const r = validateTreeRoot({ kind: 'indicator', id: 'ma', params: { p0: 20 } }, '매수')
    expect(r.complete).toBe(true)
  })
})

describe('validateBuilderTree', () => {
  it('탭(entry)에 해당하는 루트를 검증', () => {
    const tree: BlockTree = { entry: completeComparison }
    expect(validateBuilderTree(tree, 'entry').complete).toBe(true)
    expect(validateBuilderTree(tree, 'exit').empty).toBe(true)
  })
})
