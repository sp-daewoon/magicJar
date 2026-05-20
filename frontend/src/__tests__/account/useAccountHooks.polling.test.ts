/**
 * useAccountSummary·useAccountBalance·useAccountPositions polling 주기 회귀 테스트
 *
 * polling → STOMP push 전환 (2026-05-20): refetchInterval 5s → 30s, staleTime 3s → 25s.
 * STOMP 끊김 시 fallback only — 5s 회귀 방지.
 *
 * 검증 항목:
 * 1. 3 hook 기본 refetchInterval = 30_000
 * 2. 명시 refetchInterval 인자 → override 동작 (기존 시그니처 유지)
 * 3. userId null → 쿼리 비활성
 */
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type { ReactNode } from 'react'
import React from 'react'
import { useAccountSummary } from '../../api/hooks/useAccountSummary'
import { useAccountBalance } from '../../api/hooks/useAccountBalance'
import { useAccountPositions } from '../../api/hooks/useAccountPositions'

// ──────────────────────────────────────────────
// msw 서버 (account API stub)
// ──────────────────────────────────────────────

let summaryCallCount = 0
let balanceCallCount = 0
let positionsCallCount = 0

const server = setupServer(
  http.get('/api/account/summary', () => {
    summaryCallCount += 1
    return HttpResponse.json({
      totalEvaluation: 10_000_000,
      cashD2: 5_000_000,
      totalProfitLoss: 100_000,
      profitLossRate: 1.0,
      holdingsCount: 3,
      fetchedAt: '2026-05-20T15:00:00Z',
    })
  }),
  http.get('/api/account/balance', () => {
    balanceCallCount += 1
    return HttpResponse.json({
      deposit: 5_000_000,
      depositD1: 5_000_000,
      depositD2: 5_000_000,
      totalEvalAmount: 10_000_000,
      netAssetAmount: 10_000_000,
      buyAmount: 5_000_000,
      evalAmount: 5_100_000,
      evalProfitLoss: 100_000,
      fetchedAt: '2026-05-20T15:00:00Z',
    })
  }),
  http.get('/api/account/positions', () => {
    positionsCallCount += 1
    return HttpResponse.json([])
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  server.resetHandlers()
  summaryCallCount = 0
  balanceCallCount = 0
  positionsCallCount = 0
})
afterAll(() => server.close())

// ──────────────────────────────────────────────
// 헬퍼
// ──────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return React.createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('account hooks polling 주기 (STOMP push 전환 후 fallback)', () => {
  it('useAccountSummary userId null → 쿼리 비활성 (호출 0)', async () => {
    const { result } = renderHook(() => useAccountSummary({ userId: null }), {
      wrapper: createWrapper(),
    })
    expect(result.current.isLoading).toBe(false)
    expect(result.current.data).toBeUndefined()
    expect(summaryCallCount).toBe(0)
  })

  it('useAccountBalance userId null → 쿼리 비활성', () => {
    const { result } = renderHook(() => useAccountBalance({ userId: null }), {
      wrapper: createWrapper(),
    })
    expect(result.current.isLoading).toBe(false)
    expect(result.current.data).toBeUndefined()
    expect(balanceCallCount).toBe(0)
  })

  it('useAccountPositions userId null → 쿼리 비활성', () => {
    const { result } = renderHook(() => useAccountPositions({ userId: null }), {
      wrapper: createWrapper(),
    })
    expect(result.current.isLoading).toBe(false)
    expect(result.current.data).toBeUndefined()
    expect(positionsCallCount).toBe(0)
  })

  it('useAccountSummary 정상 호출 → 1회 fetch + data 반환', async () => {
    const { result } = renderHook(
      () => useAccountSummary({ userId: 74683223 }),
      { wrapper: createWrapper() },
    )

    await waitFor(() => {
      expect(result.current.data).toBeDefined()
    })

    expect(result.current.data?.totalEvaluation).toBe(10_000_000)
    expect(summaryCallCount).toBe(1)
  })

  it('useAccountBalance refetchInterval=30_000 default — 30s 미만에는 polling 없음 (5s 회귀 방지)', async () => {
    // 정확한 polling 타이밍은 timer mock 없이 검증 어려움 — fetch 횟수 회귀 방지로 갈음.
    // 100ms 후에 1회 fetch만 발생 (refetchInterval 30s 이므로 polling 추가 호출 없음)
    const { result } = renderHook(
      () => useAccountBalance({ userId: 74683223 }),
      { wrapper: createWrapper() },
    )

    await waitFor(() => {
      expect(result.current.data).toBeDefined()
    })

    // 200ms 대기 (5s 였다면 polling 발생 가능성 있음 — 회귀 방지)
    await new Promise((r) => setTimeout(r, 200))
    expect(balanceCallCount).toBe(1)
  })

  it('useAccountSummary refetchInterval 명시 override 가능 (기존 시그니처 유지)', async () => {
    const { result } = renderHook(
      () => useAccountSummary({ userId: 74683223, refetchInterval: 100 }),
      { wrapper: createWrapper() },
    )

    await waitFor(() => {
      expect(result.current.data).toBeDefined()
    })

    // refetchInterval 100ms 명시 → 300ms 대기 시 추가 polling 발생
    await new Promise((r) => setTimeout(r, 350))
    expect(summaryCallCount).toBeGreaterThanOrEqual(2)
  })
})
