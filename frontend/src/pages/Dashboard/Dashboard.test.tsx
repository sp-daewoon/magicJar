/**
 * Dashboard 페이지 테스트 (T21-T4 전면 교체 후)
 * 1. KOSPI·KOSDAQ 지수 카드 정상 렌더링
 * 2. 로딩 스켈레톤 표시
 * 3. 빈 데이터 시 fallback 메시지
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import DashboardPage from './index'
import type { MarketIndex } from '@/api/types'

// ── STOMP mock ────────────────────────────────────────────────────────────────
vi.mock('@/ws/stomp', () => ({
  stompService: {
    get state() { return 'DISCONNECTED' },
    connect: vi.fn(),
    disconnect: vi.fn(),
    subscribe: vi.fn(() => () => {}),
    onStateChange: vi.fn(() => () => {}),
    publish: vi.fn(),
  },
}))

// ── market API mock ────────────────────────────────────────────────────────────
const mockMarketIndex = vi.fn()
vi.mock('@/api/market', () => ({
  marketApi: {
    index: (...args: unknown[]) => mockMarketIndex(...args),
  },
}))

// ── LWC mock (chart는 jsdom에서 동작 불가) ─────────────────────────────────────
vi.mock('lightweight-charts', () => ({
  createChart: () => ({
    addLineSeries: () => ({ setData: vi.fn() }),
    timeScale: () => ({ fitContent: vi.fn() }),
    applyOptions: vi.fn(),
    remove: vi.fn(),
  }),
}))

const MOCK_INDICES: MarketIndex[] = [
  {
    code: 'KOSPI',
    name: 'KOSPI',
    currentValue: 2680.45,
    prevClose: 2651.80,
    changeAmount: 28.65,
    changePercent: 1.08,
    volume: null,
    session: null,
  },
  {
    code: 'KOSDAQ',
    name: '코스닥',
    currentValue: 856.32,
    prevClose: 848.10,
    changeAmount: 8.22,
    changePercent: 0.97,
    volume: null,
    session: null,
  },
]

function makeQc() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } })
}

function renderPage() {
  return render(
    <QueryClientProvider client={makeQc()}>
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('Dashboard 페이지 (T21-T4)', () => {
  beforeEach(() => {
    mockMarketIndex.mockReset()
  })

  // ── 시나리오 1: KOSPI·KOSDAQ 카드 렌더링 ─────────────────────────────────
  it('KOSPI·KOSDAQ 지수 카드가 등락률과 함께 렌더링된다', async () => {
    mockMarketIndex.mockResolvedValue(MOCK_INDICES)
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('KOSPI')).toBeInTheDocument()
      expect(screen.getByText('코스닥')).toBeInTheDocument()
    })

    // 현재값 표시 확인 (숫자 포맷)
    await waitFor(() => {
      expect(screen.getByText(/2,680/)).toBeInTheDocument()
    })

    // 등락률 색상 확인 — 양수는 빨간색 클래스
    await waitFor(() => {
      const redTexts = document.querySelectorAll('.text-red-500')
      expect(redTexts.length).toBeGreaterThan(0)
    })
  })

  // ── 시나리오 2: 로딩 스켈레톤 ──────────────────────────────────────────────
  it('데이터 로딩 중 animate-pulse 스켈레톤이 표시된다', () => {
    mockMarketIndex.mockReturnValue(new Promise(() => {})) // never resolves
    const { container } = renderPage()
    const skeletons = container.querySelectorAll('.animate-pulse')
    expect(skeletons.length).toBeGreaterThan(0)
  })

  // ── 시나리오 3: 빈 데이터 fallback ─────────────────────────────────────────
  it('지수 데이터가 없을 때 fallback 안내 메시지가 표시된다', async () => {
    mockMarketIndex.mockResolvedValue([])
    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/지수 데이터 없음/)).toBeInTheDocument()
    })
  })
})
