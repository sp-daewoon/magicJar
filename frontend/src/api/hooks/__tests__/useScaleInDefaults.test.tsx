/**
 * useScaleInDefaults + ScaleInDto floor 직렬화 테스트 (ADR 0128 amend R3 갭①·②)
 *
 * 검증:
 *  1. GET /api/strategies/scale-in-defaults 성공 → floorDefaultPct 서버값 노출
 *  2. endpoint 실패 → graceful fallback 15 (데이터 합성 아님 · prefill 힌트)
 *  3. ScaleInDto floor 필드(stopLossFloorPct·floorAutoLiquidate)가 create 요청 body로 직렬화
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import type { ReactNode } from 'react'
import { sigStrategyApi, SCALE_IN_FLOOR_DEFAULT_PCT_FALLBACK, type ScaleInDto } from '../../strategy'
import { useScaleInDefaults } from '../useScaleInDefaults'

const server = setupServer()
beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

describe('useScaleInDefaults (ADR 0128 amend R3)', () => {
  it('성공 시 서버 floorDefaultPct 를 노출한다', async () => {
    server.use(
      http.get('*/strategies/scale-in-defaults', () => HttpResponse.json({ floorDefaultPct: 20 })),
    )
    const { result } = renderHook(() => useScaleInDefaults(), { wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.floorDefaultPct).toBe(20)
  })

  it('endpoint 실패 시 fallback 15 로 graceful prefill 힌트를 보장한다', async () => {
    server.use(
      http.get('*/strategies/scale-in-defaults', () => HttpResponse.json({ error: 'boom' }, { status: 500 })),
    )
    const { result } = renderHook(() => useScaleInDefaults(), { wrapper })
    // 로딩 중에도, 에러로 끝나도 fallback 15 가 항상 보장돼야 한다(prefill 힌트)
    expect(result.current.floorDefaultPct).toBe(SCALE_IN_FLOOR_DEFAULT_PCT_FALLBACK)
    await waitFor(() => expect(result.current.isError).toBe(true), { timeout: 5000 })
    expect(result.current.floorDefaultPct).toBe(SCALE_IN_FLOOR_DEFAULT_PCT_FALLBACK)
    expect(SCALE_IN_FLOOR_DEFAULT_PCT_FALLBACK).toBe(15)
  })
})

describe('ScaleInDto floor 직렬화 (ADR 0128 amend R3)', () => {
  it('create 요청에 stopLossFloorPct·floorAutoLiquidate 가 포함된다', async () => {
    let captured: Record<string, unknown> | null = null
    server.use(
      http.post('*/strategies', async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        captured = body
        return HttpResponse.json({ id: '1', userId: 1, name: 't', status: 'PAUSED', createdAt: '2026-06-21' })
      }),
    )
    const scaleIn: ScaleInDto = {
      totalSlices: 3,
      sliceWeights: null,
      reentryTrigger: 'SIGNAL_REPEAT',
      priceDropPct: null,
      minSliceIntervalSec: 60,
      stopLossFloorPct: 12,
      floorAutoLiquidate: true,
    }
    await sigStrategyApi.create({ userId: 1, name: 't', entry: 'rsi(14) < 30', scaleIn })
    const sentScaleIn = (captured as Record<string, unknown> | null)?.scaleIn as ScaleInDto | undefined
    expect(sentScaleIn?.stopLossFloorPct).toBe(12)
    expect(sentScaleIn?.floorAutoLiquidate).toBe(true)
  })
})
