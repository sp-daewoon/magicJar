/**
 * IndicatorPicker 컴포넌트 단위 테스트
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { IndicatorPicker } from './IndicatorPicker'
import type { IndicatorInstance } from '@/api/strategy'

describe('IndicatorPicker', () => {
  it('지표 없을 때 안내 문구 표시', () => {
    render(<IndicatorPicker indicators={[]} onChange={vi.fn()} />)
    expect(screen.getByText(/지표를 추가하면/)).toBeInTheDocument()
  })

  it('+ 추가 버튼 클릭 → onChange 호출 (sma 기본)', () => {
    const onChange = vi.fn()
    render(<IndicatorPicker indicators={[]} onChange={onChange} />)
    fireEvent.click(screen.getByText('+ 추가'))
    expect(onChange).toHaveBeenCalledTimes(1)
    const called = onChange.mock.calls[0][0] as IndicatorInstance[]
    expect(called[0].type).toBe('sma')
  })

  it('지표 추가 후 목록에 배지 표시', () => {
    const indicators: IndicatorInstance[] = [
      { id: 'i1', type: 'sma', alias: 'sma_5', params: { period: 5 } },
    ]
    render(<IndicatorPicker indicators={indicators} onChange={vi.fn()} />)
    // select option + badge 둘 다 있음 — getAllByText로 확인
    const elements = screen.getAllByText('단순이동평균(SMA)')
    expect(elements.length).toBeGreaterThanOrEqual(1)
    // Badge(div)가 포함되는지 확인
    const hasBadge = elements.some((el) => el.tagName !== 'OPTION')
    expect(hasBadge).toBe(true)
  })

  it('alias 편집 → onChange 호출', () => {
    const onChange = vi.fn()
    const indicators: IndicatorInstance[] = [
      { id: 'i1', type: 'sma', alias: 'sma_5', params: { period: 5 } },
    ]
    render(<IndicatorPicker indicators={indicators} onChange={onChange} />)
    const aliasInput = screen.getByDisplayValue('sma_5')
    fireEvent.change(aliasInput, { target: { value: 'sma_fast' } })
    expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({ alias: 'sma_fast' }),
    ])
  })

  it('삭제 버튼 클릭 → 해당 지표 제거', () => {
    const onChange = vi.fn()
    const indicators: IndicatorInstance[] = [
      { id: 'i1', type: 'sma', alias: 'sma_5', params: { period: 5 } },
    ]
    render(<IndicatorPicker indicators={indicators} onChange={onChange} />)
    const deleteBtn = screen.getByLabelText('sma_5 삭제')
    fireEvent.click(deleteBtn)
    expect(onChange).toHaveBeenCalledWith([])
  })

  it('EMA 선택 후 추가 → type=ema', () => {
    const onChange = vi.fn()
    render(<IndicatorPicker indicators={[]} onChange={onChange} />)
    const select = screen.getByLabelText('지표 종류 선택')
    fireEvent.change(select, { target: { value: 'ema' } })
    fireEvent.click(screen.getByText('+ 추가'))
    const called = onChange.mock.calls[0][0] as IndicatorInstance[]
    expect(called[0].type).toBe('ema')
  })
})
