/**
 * StrategyBuilderPage 모바일 레이아웃 테스트 — 빌더 고도화 D
 *
 * matchMedia 를 모바일(lg 미만)로 모킹하면 팔레트/조건/결과 탭 전환 레이아웃이 마운트된다.
 * - 모바일 탭(블록·조건·결과) 노출
 * - default = 조건 탭(캔버스)
 * - 결과 탭 전환 → 결과 패널 표시
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, fireEvent } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { StrategyBuilderPage } from '../StrategyBuilderPage'
import { useOnboardingStore } from '../../../stores/onboarding'

const server = setupServer(
  http.get('/api/strategies/start', () =>
    HttpResponse.json({ templates: [], myStrategies: [], favoriteIds: [] }),
  ),
  http.get('/api/strategies/scale-in-defaults', () => HttpResponse.json({ floorDefaultPct: 15 })),
  http.get('/api/instruments/search', () =>
    HttpResponse.json({ query: '', results: [], totalMatched: 0, limit: 10 }),
  ),
)

/** matchMedia 모킹 — (min-width: 1024px)=false → 모바일 레이아웃 */
function mockMobileViewport() {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches: false, // lg 미만
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }),
  })
}

beforeAll(() => server.listen())
beforeEach(() => {
  useOnboardingStore.getState().setRegistered(1)
  mockMobileViewport()
})
afterEach(() => {
  server.resetHandlers()
  vi.restoreAllMocks()
})
afterAll(() => server.close())

function Wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  )
}

async function enterBuilder() {
  fireEvent.click(await screen.findByText('빈 캔버스로 직접'))
}

describe('StrategyBuilderPage — 모바일 레이아웃 (D)', () => {
  it('모바일 탭(블록·조건·결과) 노출 + default 조건 탭(캔버스)', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()
    expect(screen.getByRole('button', { name: '블록' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '조건' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '결과' })).toBeInTheDocument()
    // default 조건 탭 — 캔버스(매수 조건 Entry) 노출
    expect(screen.getByText('매수 조건 (Entry)')).toBeInTheDocument()
  })

  it('블록 탭 전환 → 팔레트 노출', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()
    fireEvent.click(screen.getByRole('button', { name: '블록' }))
    expect(screen.getByLabelText('블록 팔레트')).toBeInTheDocument()
  })

  it('결과 탭 전환 → 결과 패널(전략 실행) 노출', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()
    fireEvent.click(screen.getByRole('button', { name: '결과' }))
    expect(screen.getByText('전략 실행')).toBeInTheDocument()
  })

  it('모바일에서는 데스크톱 3분할(aside)이 중복 마운트되지 않는다', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()
    // 캔버스는 단일 마운트 — 매수 조건 탭 버튼 1개만
    expect(screen.getAllByText('매수 조건 (Entry)')).toHaveLength(1)
  })
})
