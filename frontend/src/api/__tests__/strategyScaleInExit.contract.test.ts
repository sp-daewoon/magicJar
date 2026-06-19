/**
 * scale-in/partial-exit + 전략별 포지션 API 계약 테스트 (MSW) — ADR 0110·0111 (분할매수 R3-a)
 *
 * 1. sigStrategyApi.create — scaleIn/partialExits를 요청 본문에 포함, 미사용 시 키 생략(하위호환)
 * 2. sigStrategyApi.get — 응답의 scaleIn/partialExits 정규화
 * 3. strategyPositionApi.get — 전략별 포지션 조회 + 보유 0건 빈 배열
 */
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { sigStrategyApi } from '../strategy'
import { strategyPositionApi } from '../strategyPosition'

let lastCreateBody: Record<string, unknown> | null = null

const server = setupServer(
  http.post('/api/strategies', async ({ request }) => {
    lastCreateBody = (await request.json()) as Record<string, unknown>
    return HttpResponse.json(
      {
        id: 7,
        userId: 1,
        name: 's',
        codes: ['005930'],
        intervals: ['1d'],
        status: 'PAUSED',
        scaleIn: lastCreateBody.scaleIn ?? null,
        partialExits: lastCreateBody.partialExits ?? null,
        createdAt: '2026-06-05T00:00:00Z',
      },
      { status: 201 },
    )
  }),
  http.get('/api/strategies/7', () =>
    HttpResponse.json({
      id: 7,
      userId: 1,
      name: 's',
      codes: ['005930'],
      intervals: ['1d'],
      status: 'PAUSED',
      scaleIn: { totalSlices: 3, sliceWeights: [50, 30, 20], reentryTrigger: 'PRICE_DROP_PCT', priceDropPct: -3, minSliceIntervalSec: 120 },
      partialExits: [{ triggerProfitPct: 3, exitWeightPct: 50 }],
      createdAt: '2026-06-05T00:00:00Z',
    }),
  ),
  http.get('/api/strategies/7/position', () =>
    HttpResponse.json([
      {
        strategyId: 7,
        userId: '1',
        code: '005930',
        codeName: '삼성전자',
        quantity: 10,
        avgPrice: 70000,
        filledLots: 2,
        realizedPnl: 15000,
        isClosed: false,
        updatedAt: '2026-06-05T09:30:00Z',
      },
    ]),
  ),
  http.get('/api/strategies/8/position', () => HttpResponse.json([])),
  // 분할 매수 계획 (ADR 0128 R3) — currentPrice 전달 시 목표 수량 계산 mock
  http.get('/api/strategies/7/scalein-plan', ({ request }) => {
    const cp = new URL(request.url).searchParams.get('currentPrice')
    const hasPrice = cp !== null
    return HttpResponse.json({
      strategyId: 7,
      code: '005930',
      held: true,
      filledLots: 1,
      avgPrice: '70000.0000',
      baseBudget: '1000000.0000',
      referencePrice: hasPrice ? cp : '70000',
      totalSlices: 3,
      effectiveSlices: 3,
      slices: [
        { sliceIndex: 0, displayOrder: 1, weightPct: '50', targetQuantity: hasPrice ? 7 : null, triggerDescription: '1차 진입 — BUY 시그널 발화 시 즉시' },
        { sliceIndex: 1, displayOrder: 2, weightPct: '30', targetQuantity: hasPrice ? 4 : null, triggerDescription: '2차 — 시그널 재발화 시' },
        { sliceIndex: 2, displayOrder: 3, weightPct: '20', targetQuantity: hasPrice ? 2 : null, triggerDescription: '3차 — 시그널 재발화 시' },
      ],
      expectedStopLossPrice: '66500.0000',
      fixedStopLossPct: '5.00',
      floorPct: '30.00',
      floorPrice: '49000.0000',
    })
  }),
  // 분할 미설정 전략 — 서버 400
  http.get('/api/strategies/9/scalein-plan', () =>
    HttpResponse.json({ message: '분할 매수가 설정되지 않은 전략입니다: id=9' }, { status: 400 }),
  ),
)

beforeAll(() => server.listen())
afterEach(() => {
  lastCreateBody = null
  server.resetHandlers()
})
afterAll(() => server.close())

describe('sigStrategyApi.create — scale-in/partial-exit 요청 본문', () => {
  it('scaleIn·partialExits를 본문에 포함한다', async () => {
    await sigStrategyApi.create({
      userId: 1,
      name: 's',
      entry: 'ma(5) crosses_above ma(20)',
      codes: ['005930'],
      intervals: ['1d'],
      scaleIn: { totalSlices: 3, sliceWeights: [50, 30, 20], reentryTrigger: 'PRICE_DROP_PCT', priceDropPct: -3, minSliceIntervalSec: 120 },
      partialExits: [{ triggerProfitPct: 3, exitWeightPct: 50 }],
    })
    expect(lastCreateBody?.scaleIn).toMatchObject({ totalSlices: 3, reentryTrigger: 'PRICE_DROP_PCT' })
    expect(lastCreateBody?.partialExits).toEqual([{ triggerProfitPct: 3, exitWeightPct: 50 }])
  })

  it('미사용(null) 시 scaleIn·partialExits 키를 생략한다 (하위호환)', async () => {
    await sigStrategyApi.create({
      userId: 1,
      name: 's',
      entry: 'ma(5) crosses_above ma(20)',
      codes: ['005930'],
      intervals: ['1d'],
      scaleIn: null,
      partialExits: null,
    })
    expect(lastCreateBody).not.toHaveProperty('scaleIn')
    expect(lastCreateBody).not.toHaveProperty('partialExits')
  })
})

describe('sigStrategyApi.get — 응답 정규화', () => {
  it('scaleIn·partialExits를 그대로 노출한다', async () => {
    const s = await sigStrategyApi.get('7')
    expect(s.scaleIn?.totalSlices).toBe(3)
    expect(s.partialExits).toHaveLength(1)
  })
})

describe('strategyPositionApi.get — 전략별 포지션', () => {
  it('보유 현황을 number로 정규화한다', async () => {
    const positions = await strategyPositionApi.get('7')
    expect(positions).toHaveLength(1)
    expect(positions[0]).toMatchObject({
      code: '005930',
      quantity: 10,
      avgPrice: 70000,
      filledLots: 2,
      realizedPnl: 15000,
    })
  })

  it('보유 0건이면 빈 배열 (mock 합성 0건)', async () => {
    const positions = await strategyPositionApi.get('8')
    expect(positions).toEqual([])
  })
})

describe('strategyPositionApi.getScaleInPlan — 분할 매수 계획 (ADR 0128 R3)', () => {
  it('BigDecimal(string) 응답을 number로 정규화한다', async () => {
    const plan = await strategyPositionApi.getScaleInPlan('7', 70000)
    expect(plan.strategyId).toBe(7)
    expect(plan.held).toBe(true)
    expect(plan.avgPrice).toBe(70000)
    expect(plan.baseBudget).toBe(1000000)
    expect(plan.expectedStopLossPrice).toBe(66500)
    expect(plan.fixedStopLossPct).toBe(5)
    expect(plan.floorPct).toBe(30)
    expect(plan.floorPrice).toBe(49000)
  })

  it('currentPrice 전달 시 회차별 목표 수량을 number로 받는다', async () => {
    const plan = await strategyPositionApi.getScaleInPlan('7', 70000)
    expect(plan.slices).toHaveLength(3)
    expect(plan.slices[0]).toMatchObject({ displayOrder: 1, weightPct: 50, targetQuantity: 7 })
    expect(plan.slices[2].targetQuantity).toBe(2)
  })

  it('currentPrice 미전달(미진입 미리보기) 시 목표 수량은 null', async () => {
    const plan = await strategyPositionApi.getScaleInPlan('7')
    expect(plan.slices.every((s) => s.targetQuantity === null)).toBe(true)
  })

  it('분할 미설정 전략은 400 → reject (호출 측 게이트 책임)', async () => {
    await expect(strategyPositionApi.getScaleInPlan('9')).rejects.toBeDefined()
  })
})
