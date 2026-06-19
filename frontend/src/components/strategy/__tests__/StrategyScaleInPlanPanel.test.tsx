/**
 * StrategyScaleInPlanPanel 렌더 테스트 (ADR 0128 R3 · 분할 매수 계획 UI)
 *
 * 사용자(최고 관리자) "예상 매수 계획을 전략에서 꼭 볼 수 있게" 강조 화면 검증:
 *  1. 회차별 계획 표 — 목표 수량 + 진입 조건(triggerDescription) 렌더
 *  2. 0% 회차 제외 — 서버가 0% 제외한 slices만 내려주면 그대로 표시(추가 0% row 0)
 *  3. 예상 손절가 + floor 알람선 표기 — floor는 "알람만 · 자동 청산 아님" 명시
 *  4. 미진입 미리보기 — targetQuantity null이면 "기준가 대기" + "미진입 (미리보기)"
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { StrategyScaleInPlanPanel } from '../StrategyScaleInPlanPanel'

// 보유 중 — 박제 base·평단으로 목표 수량 산출 (3회차, 0% 회차는 서버가 이미 제외)
const HELD_PLAN = {
  strategyId: 7,
  code: '005930',
  held: true,
  filledLots: 1,
  avgPrice: '70000.0000',
  baseBudget: '1000000.0000',
  referencePrice: '70000',
  totalSlices: 3,
  effectiveSlices: 3,
  slices: [
    { sliceIndex: 0, displayOrder: 1, weightPct: '50', targetQuantity: 7, triggerDescription: '1차 진입 — BUY 시그널 발화 시 즉시' },
    { sliceIndex: 1, displayOrder: 2, weightPct: '30', targetQuantity: 4, triggerDescription: '2차 — 시그널 재발화 시' },
    { sliceIndex: 2, displayOrder: 3, weightPct: '20', targetQuantity: 2, triggerDescription: '3차 — 시그널 재발화 시' },
  ],
  expectedStopLossPrice: '66500.0000',
  fixedStopLossPct: '5.00',
  floorPct: '30.00',
  floorPrice: '49000.0000',
}

// 미진입 — 목표 수량 null + 0% 회차 제외(원본 4회차 중 0% 1개 제외 → displayOrder 1·2·3, sliceIndex 비연속)
const PREVIEW_PLAN = {
  strategyId: 8,
  code: null,
  held: false,
  filledLots: 0,
  avgPrice: null,
  baseBudget: null,
  referencePrice: null,
  totalSlices: 4,
  effectiveSlices: 3,
  slices: [
    { sliceIndex: 0, displayOrder: 1, weightPct: '50', targetQuantity: null, triggerDescription: '1차 진입 — BUY 시그널 발화 시 즉시' },
    { sliceIndex: 2, displayOrder: 2, weightPct: '30', targetQuantity: null, triggerDescription: '2차 — 시그널 재발화 시' },
    { sliceIndex: 3, displayOrder: 3, weightPct: '20', targetQuantity: null, triggerDescription: '3차 — 시그널 재발화 시' },
  ],
  expectedStopLossPrice: null,
  fixedStopLossPct: '5.00',
  floorPct: '30.00',
  floorPrice: null,
}

const server = setupServer(
  http.get('/api/strategies/7/scalein-plan', () => HttpResponse.json(HELD_PLAN)),
  http.get('/api/strategies/8/scalein-plan', () => HttpResponse.json(PREVIEW_PLAN)),
)

beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

function renderPanel(strategyId: string, currentPrice?: number | null) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <StrategyScaleInPlanPanel strategyId={strategyId} currentPrice={currentPrice} />
    </QueryClientProvider>,
  )
}

describe('StrategyScaleInPlanPanel — 보유 중(목표 수량 산출)', () => {
  it('회차별 목표 수량 + 진입 조건을 표시한다', async () => {
    renderPanel('7', 70000)
    expect(await screen.findByText('7주')).toBeInTheDocument()
    expect(screen.getByText('4주')).toBeInTheDocument()
    expect(screen.getByText('2주')).toBeInTheDocument()
    expect(screen.getByText('1차 진입 — BUY 시그널 발화 시 즉시')).toBeInTheDocument()
    expect(screen.getByText('2차 — 시그널 재발화 시')).toBeInTheDocument()
  })

  it('3회차만 렌더(서버가 0% 회차 제외) — 회차 row 정확히 3개', async () => {
    renderPanel('7', 70000)
    const list = await screen.findByLabelText('회차별 분할 매수 계획')
    expect(list.querySelectorAll('li')).toHaveLength(3)
  })

  it('예상 손절가(−5%) + floor 알람선(−30%)을 구분 표기한다', async () => {
    renderPanel('7', 70000)
    expect(await screen.findByText('66,500원')).toBeInTheDocument() // 예상 손절가
    expect(screen.getByText('49,000원')).toBeInTheDocument() // floor 도달가
    expect(screen.getByText(/알람만 · 자동 청산 아님/)).toBeInTheDocument()
  })

  it('진행 회차 헤더를 표시한다 (1/3회차)', async () => {
    renderPanel('7', 70000)
    expect(await screen.findByText('진행 1/3회차')).toBeInTheDocument()
  })
})

describe('StrategyScaleInPlanPanel — 미진입 미리보기', () => {
  it('목표 수량 미산출(기준가 대기) + "미진입 (미리보기)" 표기', async () => {
    renderPanel('8')
    expect(await screen.findByText('미진입 (미리보기)')).toBeInTheDocument()
    // 회차 3개 모두 "기준가 대기"(목표 수량 null) — 손절가도 기준가 대기
    await waitFor(() => {
      expect(screen.getAllByText('기준가 대기').length).toBeGreaterThanOrEqual(3)
    })
  })

  it('floor 알람선 라벨(−30%)은 평단 미정이어도 비율은 표시한다', async () => {
    renderPanel('8')
    expect(await screen.findByText('(−30%)')).toBeInTheDocument()
  })
})
