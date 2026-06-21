/**
 * StrategyRunStatusSummary 집계 강화 테스트 (list-ux-uplift)
 *
 * 검증:
 *  - floor 알람(자동청산 제외) 전략 수 · 분할 진행 전략 수 집계 칩 노출
 *  - 집계 칩 클릭 시 onJumpToFilter 호출
 */
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { SigStrategyDto } from '../../../api/strategy'

const strategies: SigStrategyDto[] = [
  // 자동매매중 · 분할 3회 · floor 알람
  { id: '1', userId: 1, name: 'A', codes: ['1'], intervals: ['1d'], status: 'ACTIVE', createdAt: '',
    scaleIn: { totalSlices: 3, stopLossFloorPct: 15, floorAutoLiquidate: false } },
  // 매수차단 · 분할 2회 · floor 자동청산(알람 집계 제외)
  { id: '2', userId: 1, name: 'B', codes: ['2'], intervals: ['1d'], status: 'BUY_BLOCKED', createdAt: '',
    scaleIn: { totalSlices: 2, stopLossFloorPct: 20, floorAutoLiquidate: true } },
  // 중지 · 단일 진입
  { id: '3', userId: 1, name: 'C', codes: ['3'], intervals: ['1d'], status: 'PAUSED', createdAt: '' },
]

vi.mock('../../../api/hooks/useRuntimeFlags', () => ({
  useRuntimeFlags: () => ({ data: { mode: 'MOCK', killSwitchEnabled: false }, isLoading: false }),
}))
vi.mock('../../../api/hooks/useOrders', () => ({
  useOrders: () => ({ data: { content: [] }, isError: false }),
}))
vi.mock('../../../api/hooks/useRiskConfig', () => ({
  useRiskConfig: () => ({ data: { maxPositionAmount: 1000000, maxPositionPercent: 10 } }),
}))
vi.mock('../../../api/hooks/useRiskEvents', () => ({
  useRiskEvents: () => ({ data: { pages: [{ content: [] }] }, isError: false }),
}))
vi.mock('../../../api/hooks/useSignal', () => ({
  useSignals: () => ({ data: { pages: [{ content: [] }] }, isError: false }),
}))
vi.mock('../../../api/hooks/useSigStrategy', () => ({
  useSigStrategies: () => ({
    data: { pages: [{ content: strategies }] },
    isLoading: false,
  }),
}))

import { MemoryRouter } from 'react-router-dom'
import { StrategyRunStatusSummary } from '../StrategyRunStatusSummary'

function renderSummary(onJumpToFilter = vi.fn()) {
  render(
    <MemoryRouter>
      <StrategyRunStatusSummary userId={1} onJumpToFilter={onJumpToFilter} />
    </MemoryRouter>,
  )
  return { onJumpToFilter }
}

describe('StrategyRunStatusSummary 집계 칩', () => {
  it('floor 알람 1건(자동청산 제외) · 분할 진행 2건 집계를 표시한다', () => {
    renderSummary()
    expect(screen.getByText('floor 알람 1')).toBeInTheDocument()
    expect(screen.getByText('분할 진행 2')).toBeInTheDocument()
    expect(screen.getByText('자동매매중 1')).toBeInTheDocument()
    expect(screen.getByText('매수차단 1')).toBeInTheDocument()
    expect(screen.getByText('중지 1')).toBeInTheDocument()
  })

  it('자동매매중 칩 클릭 시 onJumpToFilter("ACTIVE")', async () => {
    const user = userEvent.setup()
    const { onJumpToFilter } = renderSummary()
    await user.click(screen.getByRole('button', { name: '자동매매중 1' }))
    expect(onJumpToFilter).toHaveBeenCalledWith('ACTIVE')
  })

  it('매수차단 칩 클릭 시 onJumpToFilter("BUY_BLOCKED")', async () => {
    const user = userEvent.setup()
    const { onJumpToFilter } = renderSummary()
    await user.click(screen.getByRole('button', { name: '매수차단 1' }))
    expect(onJumpToFilter).toHaveBeenCalledWith('BUY_BLOCKED')
  })
})
