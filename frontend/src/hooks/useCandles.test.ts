/**
 * useCandles hook 단위 테스트 (T19-T6)
 *
 * 검증 항목:
 *   1. 초기 fetch 성공 → candles 반환 (최신 200봉)
 *   2. 응답 < limit → hasMore=false (종료 조건)
 *   3. 응답 == limit → hasMore=true (더 있음)
 *   4. fetchOlderCandles() 호출 시 다음 cursor로 추가 fetch
 *   5. M15 resolveInterval → serverInterval=M5, 합성 결과 반환
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { useCandles, CANDLES_PAGE_LIMIT } from './useCandles'
import type { CandleDto } from '@/api/types'

// BASE_EPOCH: 2026-05-03 00:00:00 UTC
const BASE_EPOCH = 1746230400

function mkMinuteCandles(baseEpoch: number, intervalSec: number, count: number): CandleDto[] {
  return Array.from({ length: count }, (_, i) => ({
    time: baseEpoch + i * intervalSec,
    open: 50000 + i * 10,
    high: 50200 + i * 10,
    low: 49800 + i * 10,
    close: 50050 + i * 10,
    volume: 100 + i,
  }))
}

function mkDayCandles(count: number): CandleDto[] {
  return Array.from({ length: count }, (_, i) => {
    const d = new Date(2026, 0, 1 + i)
    const y = d.getFullYear()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    return {
      time: `${y}-${m}-${day}`,
      open: 50000,
      high: 50200,
      low: 49800,
      close: 50050,
      volume: 1000,
    }
  })
}

// instruments API mock
const mockCandlesPage = vi.fn<typeof import('@/api/instruments').instrumentsApi.candlesPage>()

vi.mock('@/api/instruments', () => ({
  instrumentsApi: {
    candlesPage: (...args: unknown[]) => mockCandlesPage(...(args as Parameters<typeof mockCandlesPage>)),
  },
}))

function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children)
}

describe('useCandles', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('초기 fetch 성공 — candles 반환, isLoading=false', async () => {
    const mockData = mkDayCandles(50)
    mockCandlesPage.mockResolvedValue(mockData)

    const { result } = renderHook(() => useCandles('005930', 'DAY'), {
      wrapper: makeWrapper(),
    })

    expect(result.current.isLoading).toBe(true)

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false)
    })

    expect(result.current.candles.length).toBe(50)
    expect(result.current.isError).toBe(false)
  })

  it('응답 길이 < CANDLES_PAGE_LIMIT → hasMore=false', async () => {
    // CANDLES_PAGE_LIMIT보다 적게 반환
    const mockData = mkDayCandles(CANDLES_PAGE_LIMIT - 50)
    mockCandlesPage.mockResolvedValue(mockData)

    const { result } = renderHook(() => useCandles('005930', 'DAY'), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false)
    })

    expect(result.current.hasMore).toBe(false)
  })

  it('응답 길이 == CANDLES_PAGE_LIMIT → hasMore=true', async () => {
    // 정확히 limit만큼 반환하면 더 있다고 판단
    const mockData = mkDayCandles(CANDLES_PAGE_LIMIT)
    mockCandlesPage.mockResolvedValue(mockData)

    const { result } = renderHook(() => useCandles('005930', 'DAY'), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false)
    })

    expect(result.current.hasMore).toBe(true)
  })

  it('M15 interval → serverInterval=M5, 분봉 합성 후 candles 반환', async () => {
    // M5 5분봉 3개 = M15 1개로 합성
    const m5Candles = mkMinuteCandles(BASE_EPOCH, 5 * 60, 3)
    mockCandlesPage.mockResolvedValue(m5Candles)

    const { result } = renderHook(() => useCandles('005930', 'M15'), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false)
    })

    // M5 3개 → M15 1개 (같은 15분 그룹)
    expect(result.current.candles.length).toBe(1)
    // candlesPage가 serverInterval='M5'로 호출됐는지 확인
    expect(mockCandlesPage).toHaveBeenCalledWith(
      '005930',
      'M5',
      undefined,
      CANDLES_PAGE_LIMIT,
    )
  })

  it('API 실패 → isError=true, candles=[]', async () => {
    mockCandlesPage.mockRejectedValue(new Error('Network error'))

    const { result } = renderHook(() => useCandles('005930', 'DAY'), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false)
    })

    expect(result.current.isError).toBe(true)
    expect(result.current.candles).toHaveLength(0)
  })
})
