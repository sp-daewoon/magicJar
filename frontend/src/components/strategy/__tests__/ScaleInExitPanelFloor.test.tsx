/**
 * ScaleInExitPanel floor 필드/토글 테스트 (ADR 0128 amend R3 갭①·②)
 *
 * 검증:
 *  1. scale-in enabled 시 floor% 입력·자동청산 토글 항상 노출
 *  2. floor 값 미설정(null)이면 useScaleInDefaults 값(서버 → 실패 시 fallback 15)으로 prefill
 *  3. 자동청산 토글 OFF=알람만 설명 / ON=amber 자동청산 경고
 *  4. ScaleInDto floor 필드(stopLossFloorPct·floorAutoLiquidate) 직렬화 — 사용자 입력이 onScaleInChange로 전달
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { ScaleInDto } from '../../../api/strategy'
import { ScaleInExitPanel } from '../ScaleInExitPanel'

const server = setupServer(
  http.get('*/strategies/scale-in-defaults', () => HttpResponse.json({ floorDefaultPct: 15 })),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const BASE_SCALE_IN: ScaleInDto = {
  totalSlices: 3,
  sliceWeights: null,
  reentryTrigger: 'SIGNAL_REPEAT',
  priceDropPct: null,
  minSliceIntervalSec: 60,
  stopLossFloorPct: null,
  floorAutoLiquidate: false,
}

function renderPanel(scaleIn: ScaleInDto, onScaleInChange = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <ScaleInExitPanel
        scaleInEnabled
        onScaleInEnabledChange={vi.fn()}
        scaleIn={scaleIn}
        onScaleInChange={onScaleInChange}
        manualWeights={false}
        onManualWeightsChange={vi.fn()}
        partialExitsEnabled={false}
        onPartialExitsEnabledChange={vi.fn()}
        partialExits={[]}
        onPartialExitsChange={vi.fn()}
      />
    </QueryClientProvider>,
  )
  return onScaleInChange
}

describe('ScaleInExitPanel — floor 필드 (ADR 0128 amend R3)', () => {
  it('scale-in enabled 시 floor% 입력과 자동청산 토글을 항상 노출한다', () => {
    renderPanel(BASE_SCALE_IN)
    expect(screen.getByLabelText('catastrophic floor 퍼센트')).toBeInTheDocument()
    expect(screen.getByLabelText('floor 도달 시 자동 청산')).toBeInTheDocument()
  })

  it('floor 미설정(null)이면 default(15)로 prefill — onScaleInChange로 주입', () => {
    const onChange = renderPanel(BASE_SCALE_IN)
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ stopLossFloorPct: 15 }))
  })

  it('floor 값이 이미 있으면 prefill로 덮어쓰지 않는다', () => {
    const onChange = renderPanel({ ...BASE_SCALE_IN, stopLossFloorPct: 8 })
    // stopLossFloorPct 를 변경하는 호출이 없어야 한다(8 유지)
    const floorPrefillCalls = onChange.mock.calls.filter(
      ([next]) => (next as ScaleInDto).stopLossFloorPct !== 8,
    )
    expect(floorPrefillCalls).toHaveLength(0)
    expect((screen.getByLabelText('catastrophic floor 퍼센트') as HTMLInputElement).value).toBe('8')
  })

  it('floor% 0(비활성)은 prefill 대상에서 제외한다', () => {
    const onChange = renderPanel({ ...BASE_SCALE_IN, stopLossFloorPct: 0 })
    const overwrote = onChange.mock.calls.some(([next]) => (next as ScaleInDto).stopLossFloorPct !== 0)
    expect(overwrote).toBe(false)
  })

  it('자동청산 OFF면 알람만 설명, ON이면 자동 청산 경고를 표시한다', () => {
    const { rerender } = renderReRenderable({ ...BASE_SCALE_IN, stopLossFloorPct: 15, floorAutoLiquidate: false })
    expect(screen.getByText(/알람만 — 청산은 수동 판단/)).toBeInTheDocument()

    rerender({ ...BASE_SCALE_IN, stopLossFloorPct: 15, floorAutoLiquidate: true })
    expect(screen.getByText(/자동 전량 청산합니다/)).toBeInTheDocument()
  })

  it('floor% 입력 변경이 stopLossFloorPct 로 직렬화된다', () => {
    const onChange = renderPanel({ ...BASE_SCALE_IN, stopLossFloorPct: 15 })
    fireEvent.change(screen.getByLabelText('catastrophic floor 퍼센트'), { target: { value: '12' } })
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ stopLossFloorPct: 12 }))
  })

  it('자동청산 토글 변경이 floorAutoLiquidate 로 직렬화된다', () => {
    const onChange = renderPanel({ ...BASE_SCALE_IN, stopLossFloorPct: 15, floorAutoLiquidate: false })
    fireEvent.click(screen.getByLabelText('floor 도달 시 자동 청산'))
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ floorAutoLiquidate: true }))
  })
})

// rerender 헬퍼 — 같은 QueryClient로 토글 OFF→ON 전환 검증
function renderReRenderable(initial: ScaleInDto) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const ui = (scaleIn: ScaleInDto) => (
    <QueryClientProvider client={qc}>
      <ScaleInExitPanel
        scaleInEnabled
        onScaleInEnabledChange={vi.fn()}
        scaleIn={scaleIn}
        onScaleInChange={vi.fn()}
        manualWeights={false}
        onManualWeightsChange={vi.fn()}
        partialExitsEnabled={false}
        onPartialExitsEnabledChange={vi.fn()}
        partialExits={[]}
        onPartialExitsChange={vi.fn()}
      />
    </QueryClientProvider>
  )
  const result = render(ui(initial))
  return { rerender: (next: ScaleInDto) => result.rerender(ui(next)) }
}
