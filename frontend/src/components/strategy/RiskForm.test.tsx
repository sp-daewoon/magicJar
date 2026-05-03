/**
 * RiskForm 컴포넌트 단위 테스트
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { RiskForm } from './RiskForm'
import type { RiskConfig } from '@/api/strategy'

const emptyRisk: RiskConfig = { stopLoss: '', takeProfit: '', trailingStop: '' }

describe('RiskForm', () => {
  it('렌더링 — 3개 필드 표시', () => {
    render(<RiskForm risk={emptyRisk} onChange={vi.fn()} />)
    expect(screen.getByLabelText('손절 (%)')).toBeInTheDocument()
    expect(screen.getByLabelText('익절 (%)')).toBeInTheDocument()
    expect(screen.getByLabelText('트레일링 스탑 (%)')).toBeInTheDocument()
  })

  it('손절 입력 → onChange 호출', () => {
    const onChange = vi.fn()
    render(<RiskForm risk={emptyRisk} onChange={onChange} />)
    fireEvent.change(screen.getByLabelText('손절 (%)'), { target: { value: '5' } })
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ stopLoss: '5' }))
  })

  it('익절 입력 → onChange 호출', () => {
    const onChange = vi.fn()
    render(<RiskForm risk={emptyRisk} onChange={onChange} />)
    fireEvent.change(screen.getByLabelText('익절 (%)'), { target: { value: '10' } })
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ takeProfit: '10' }))
  })

  it('리스크 요약 — stopLoss + takeProfit 설정 시 표시', () => {
    const risk: RiskConfig = { stopLoss: '5', takeProfit: '10', trailingStop: '' }
    render(<RiskForm risk={risk} onChange={vi.fn()} />)
    expect(screen.getByText('손절 -5%')).toBeInTheDocument()
    expect(screen.getByText('익절 +10%')).toBeInTheDocument()
  })

  it('리스크 요약 — 빈 값 시 요약 섹션 미표시', () => {
    render(<RiskForm risk={emptyRisk} onChange={vi.fn()} />)
    // 요약 섹션의 "손절 -X%" 형태는 없어야 함 (라벨 "손절 (%)"는 있음)
    expect(screen.queryByText(/손절 -/)).not.toBeInTheDocument()
    expect(screen.queryByText(/익절 \+/)).not.toBeInTheDocument()
  })
})
