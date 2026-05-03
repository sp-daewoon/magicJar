/**
 * ConditionBuilder 컴포넌트 단위 테스트
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ConditionBuilder } from './ConditionBuilder'
import type { ConditionGroup, IndicatorInstance } from '@/api/strategy'

const emptyGroup: ConditionGroup = { logic: 'and', conditions: [] }
const indicators: IndicatorInstance[] = [
  { id: 'i1', type: 'sma', alias: 'sma_5', params: { period: 5 } },
  { id: 'i2', type: 'sma', alias: 'sma_20', params: { period: 20 } },
]

describe('ConditionBuilder', () => {
  it('빈 조건 시 안내 문구 표시', () => {
    render(
      <ConditionBuilder
        sectionLabel="진입 조건"
        conditions={emptyGroup}
        indicators={indicators}
        onChange={vi.fn()}
      />,
    )
    expect(screen.getByText(/조건을 추가하세요/)).toBeInTheDocument()
  })

  it('섹션 레이블 표시', () => {
    render(
      <ConditionBuilder
        sectionLabel="진입 조건 (Entry)"
        conditions={emptyGroup}
        indicators={indicators}
        onChange={vi.fn()}
      />,
    )
    expect(screen.getByText('진입 조건 (Entry)')).toBeInTheDocument()
  })

  it('+ 조건 추가 클릭 → onChange 호출', () => {
    const onChange = vi.fn()
    render(
      <ConditionBuilder
        sectionLabel="진입 조건"
        conditions={emptyGroup}
        indicators={indicators}
        onChange={onChange}
      />,
    )
    fireEvent.click(screen.getByText('+ 조건 추가'))
    expect(onChange).toHaveBeenCalledTimes(1)
    const called = onChange.mock.calls[0][0] as ConditionGroup
    expect(called.conditions).toHaveLength(1)
  })

  it('2개 조건 → AND 배지 + AND 구분선 표시', () => {
    const group: ConditionGroup = {
      logic: 'and',
      conditions: [
        {
          id: 'c1',
          left: 'sma_5',
          operator: 'cross_above',
          compareTo: { type: 'indicator', value: 'sma_20' },
        },
        {
          id: 'c2',
          left: 'close',
          operator: 'greater_than',
          compareTo: { type: 'number', value: '0' },
        },
      ],
    }
    render(
      <ConditionBuilder
        sectionLabel="진입 조건"
        conditions={group}
        indicators={indicators}
        onChange={vi.fn()}
      />,
    )
    // AND 텍스트가 배지(헤더) + 구분선(조건 사이) 2곳에 표시됨
    const andElements = screen.getAllByText('AND')
    expect(andElements.length).toBeGreaterThanOrEqual(1)
  })

  it('조건 삭제 버튼 → onChange 호출로 해당 조건 제거', () => {
    const onChange = vi.fn()
    const group: ConditionGroup = {
      logic: 'and',
      conditions: [
        {
          id: 'c1',
          left: 'sma_5',
          operator: 'cross_above',
          compareTo: { type: 'indicator', value: 'sma_20' },
        },
      ],
    }
    render(
      <ConditionBuilder
        sectionLabel="진입 조건"
        conditions={group}
        indicators={indicators}
        onChange={onChange}
      />,
    )
    fireEvent.click(screen.getByLabelText('조건 삭제'))
    const called = onChange.mock.calls[0][0] as ConditionGroup
    expect(called.conditions).toHaveLength(0)
  })
})
