/**
 * ScaleInPlanPreviewInline 단위 테스트 — 빌더 고도화 A
 *
 * 검증:
 * - 회차표 + 비중 렌더
 * - 0% 회차 제외
 * - floor 라벨(자동 청산 / 알람만) 분기
 * - 기준가 미입력 시 안내
 */
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ScaleInPlanPreviewInline } from '../ScaleInPlanPreviewInline'
import type { ScaleInDto } from '../../../api/strategy'

const base: ScaleInDto = {
  totalSlices: 3,
  sliceWeights: null,
  reentryTrigger: 'SIGNAL_REPEAT',
  priceDropPct: null,
  minSliceIntervalSec: 60,
  stopLossFloorPct: 15,
  floorAutoLiquidate: false,
}

describe('ScaleInPlanPreviewInline', () => {
  it('회차표 렌더 — 3분할 회차 3개 + 미리보기 라벨', () => {
    render(
      <ScaleInPlanPreviewInline scaleIn={base} manualWeights={false} previewEntryPrice="100000" stopLossPct="5" />,
    )
    const list = screen.getByLabelText('회차별 예상 매수 계획')
    expect(list.querySelectorAll('li')).toHaveLength(3)
    expect(screen.getByText('예상 매수 계획 (미리보기)')).toBeInTheDocument()
  })

  it('0% 회차 제외 — 60/0/40 → 2개만', () => {
    render(
      <ScaleInPlanPreviewInline
        scaleIn={{ ...base, sliceWeights: [60, 0, 40] }}
        manualWeights
        previewEntryPrice="100000"
        stopLossPct="5"
      />,
    )
    expect(screen.getByLabelText('회차별 예상 매수 계획').querySelectorAll('li')).toHaveLength(2)
  })

  it('floor 자동 청산 ON — "자동 청산" 라벨', () => {
    render(
      <ScaleInPlanPreviewInline
        scaleIn={{ ...base, floorAutoLiquidate: true }}
        manualWeights={false}
        previewEntryPrice="100000"
        stopLossPct="5"
      />,
    )
    expect(screen.getByText('자동 청산')).toBeInTheDocument()
  })

  it('floor 자동 청산 OFF — "알람만 · 자동 청산 아님"', () => {
    render(
      <ScaleInPlanPreviewInline scaleIn={base} manualWeights={false} previewEntryPrice="100000" stopLossPct="5" />,
    )
    expect(screen.getByText('알람만 · 자동 청산 아님')).toBeInTheDocument()
  })

  it('기준가 미입력 — 안내 표시', () => {
    render(<ScaleInPlanPreviewInline scaleIn={base} manualWeights={false} previewEntryPrice="" stopLossPct="5" />)
    expect(
      screen.getByText('위 미리보기 기준가를 입력하면 회차별 예상 진입가·손절가가 표시됩니다.'),
    ).toBeInTheDocument()
  })

  it('예상 손절가 표시 — 기준가 100000·손절 5% → 95,000원', () => {
    render(
      <ScaleInPlanPreviewInline scaleIn={base} manualWeights={false} previewEntryPrice="100000" stopLossPct="5" />,
    )
    expect(screen.getByText('95,000원')).toBeInTheDocument()
  })
})
