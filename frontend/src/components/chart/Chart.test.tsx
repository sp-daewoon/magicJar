import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import Chart from './Chart'

// lightweight-charts는 실제 DOM 렌더링을 요구 → jsdom + canvas mock (setup.ts)
// createChart가 DOM에 의존하므로 실제 마운트 테스트 수행

describe('Chart 컴포넌트', () => {
  it('빈 data로 마운트 — 크래시 없음', () => {
    expect(() => {
      render(<Chart data={{}} height={300} />)
    }).not.toThrow()
  })

  it('캡처 버튼: onCapture 없을 때 미노출', () => {
    render(<Chart data={{}} height={300} />)
    expect(screen.queryByText('캡처')).not.toBeInTheDocument()
  })

  it('캡처 버튼: onCapture 있을 때 노출', () => {
    const onCapture = vi.fn()
    render(<Chart data={{}} height={300} onCapture={onCapture} />)
    expect(screen.getByText('캡처')).toBeInTheDocument()
  })

  it('틱 데이터로 마운트 — 크래시 없음', () => {
    const ticks = [
      { time: 1_700_000_000, value: 78500 },
      { time: 1_700_000_060, value: 78600 },
    ]
    expect(() => {
      render(<Chart data={{ ticks }} height={300} />)
    }).not.toThrow()
  })

  it('캔들 데이터로 마운트 — 크래시 없음', () => {
    const candles = [
      { time: 1_700_000_000, open: 78000, high: 79000, low: 77500, close: 78500 },
    ]
    expect(() => {
      render(<Chart data={{ candles }} height={300} />)
    }).not.toThrow()
  })
})
