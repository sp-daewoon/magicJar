/**
 * StrategyBuilderPage 통합 테스트 (spec 2026-06-18 §10 — 시작 화면 데이터화 R3)
 *
 * 검증:
 * - 진입 시 시작 화면 렌더 (빈 트리 + 미로드)
 * - "빈 캔버스로 직접" → 빌더(팔레트+캔버스) 진입
 * - 템플릿 카드 클릭 → 캔버스에 tree 로드 (DSL 파싱)
 * - 즐겨찾기 탭 — DB 데이터(favorite=true) 공급 + 해제 시 DELETE
 * - 시작 화면 "말로 설명" → AI 생성 모달
 * - 팔레트 선택/검증/저장 흐름 (빌더 진입 후)
 * - deeplink(?strategyId=) → 시작 화면 우회, 평가 흐름
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, expect, it, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { StrategyBuilderPage } from '../StrategyBuilderPage'
import { useOnboardingStore } from '../../../stores/onboarding'

const templateItem = {
  id: 101,
  name: 'fvg-bullish-reclaim',
  nameKo: 'FVG 매수 후보',
  tf: '3m',
  dslEntry: 'fvg(50).bullish_active == 1 AND close > fvg(50).bullish_mid',
  dslExit: 'close < fvg(50).bullish_bottom',
  takeProfitPct: 5,
  fixedStopLossPct: 3,
  favorite: false,
}

const favoriteMine = {
  id: 7,
  name: 'minervini-trend',
  nameKo: '미너비니 추세 템플릿',
  tf: '1d',
  dslEntry: 'close > ma(50) AND ma(50) > ma(150)',
  dslExit: null,
  takeProfitPct: null,
  fixedStopLossPct: null,
  favorite: true,
}

const favoriteCalls: Array<{ method: string; id: string }> = []

const server = setupServer(
  http.get('/api/strategies/start', () =>
    HttpResponse.json({
      templates: [templateItem],
      myStrategies: [favoriteMine],
      favoriteIds: [7],
    }),
  ),
  http.post('/api/strategies/:id/favorite', ({ params }) => {
    favoriteCalls.push({ method: 'POST', id: String(params.id) })
    return new HttpResponse(null, { status: 201 })
  }),
  http.delete('/api/strategies/:id/favorite', ({ params }) => {
    favoriteCalls.push({ method: 'DELETE', id: String(params.id) })
    return new HttpResponse(null, { status: 204 })
  }),
  http.get('/api/instruments/search', () =>
    HttpResponse.json({
      query: '005930',
      results: [
        { code: '005930', name: '삼성전자', market: 'KOSPI', sector: '전기전자', score: 1.0 },
      ],
      totalMatched: 1,
      limit: 10,
    }),
  ),
  http.post('/api/strategy/validate', () =>
    HttpResponse.json({
      astDepth: 2,
      nodeCount: 3,
      indicatorCallCount: 1,
      estimatedEvalTimeMs: 5,
      complexityClass: 'LOW',
    }),
  ),
)

beforeAll(() => server.listen())
beforeEach(() => {
  // 시작 화면 데이터·즐겨찾기 흡수가 userId를 요구
  useOnboardingStore.getState().setRegistered(1)
})
afterEach(() => {
  server.resetHandlers()
  window.localStorage.clear()
  favoriteCalls.length = 0
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

function renderWithRoute(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <StrategyBuilderPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** 시작 화면에서 "빈 캔버스로 직접" 클릭 → 빌더 진입 */
async function enterBuilder() {
  const blankBtn = await screen.findByText('빈 캔버스로 직접')
  fireEvent.click(blankBtn)
  await screen.findByLabelText('블록 팔레트')
}

describe('StrategyBuilderPage — 시작 화면', () => {
  it('진입 시 시작 화면(템플릿·내 전략) 렌더', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    expect(await screen.findByText('전략 만들기 시작')).toBeInTheDocument()
    expect(await screen.findByText('FVG 매수 후보')).toBeInTheDocument()
    expect(screen.getByText('미너비니 추세 템플릿')).toBeInTheDocument()
  })

  it('템플릿 카드 클릭 → 캔버스에 tree 로드 (DSL 파싱)', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    const card = await screen.findByLabelText('FVG 매수 후보 불러오기')
    fireEvent.click(card)
    await screen.findByLabelText('블록 팔레트')
    await waitFor(() => {
      expect((screen.getByLabelText('DSL 미리보기 (읽기 전용)') as HTMLTextAreaElement).value).toContain(
        'fvg',
      )
    })
  })

  it('"빈 캔버스로 직접" → 빌더 진입', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()
    expect(screen.getByText('매수 조건 (Entry)')).toBeInTheDocument()
    expect(screen.getByText(/팔레트에서 블록을 선택하거나/)).toBeInTheDocument()
  })

  it('시작 화면 "말로 설명" → AI 생성 모달', async () => {
    server.use(
      http.post('/api/strategies/ai-generate', () =>
        HttpResponse.json({ provider: 'claude-cli', generatedAt: '', warnings: [], items: [] }),
      ),
    )
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    fireEvent.click(await screen.findByText('💬 말로 설명해서 만들기'))
    expect(await screen.findByRole('dialog', { name: 'AI 전략 생성' })).toBeInTheDocument()
  })
})

describe('StrategyBuilderPage — 빌더', () => {
  it('빈 캔버스 진입 후 안내 메시지', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()
    expect(screen.getByText(/팔레트에서 블록을 선택하거나/)).toBeInTheDocument()
  })

  it('팔레트 선택은 현재 트리의 첫 빈 슬롯을 채운다', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()

    fireEvent.click(screen.getByLabelText('연산자/상수'))
    fireEvent.click(screen.getByText('비교: >'))
    fireEvent.click(screen.getByLabelText('지표'))
    fireEvent.click(screen.getByText('이동평균(MA)'))
    fireEvent.click(screen.getByLabelText('캔들'))
    fireEvent.click(screen.getByText('종가 (Close)'))

    expect((screen.getByLabelText('DSL 미리보기 (읽기 전용)') as HTMLTextAreaElement).value).toBe('ma(20) > close')
  })

  it('자연어 입력 버튼 클릭 → 모달 오픈', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()
    fireEvent.click(screen.getByText('자연어로 입력하기'))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('저장 버튼 — 빈 entry 시 disabled', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()
    expect(screen.getByText('저장')).toBeDisabled()
  })

  it('전략 미저장 상태에서 "이 전략으로 종목 찾기" 버튼 미표시', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()
    expect(screen.queryByText('이 전략으로 종목 찾기')).not.toBeInTheDocument()
  })
})

describe('StrategyBuilderPage — 즐겨찾기 (DB)', () => {
  it('즐겨찾기 탭 — DB favorite=true 항목만 표시', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()
    fireEvent.click(screen.getByLabelText('즐겨찾기'))
    // favorite=true 인 미너비니 추세 템플릿만 노출, FVG(favorite=false)는 미노출
    expect(screen.getByRole('button', { name: '미너비니 추세 템플릿 적용' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'FVG 매수 후보 적용' })).not.toBeInTheDocument()
  })

  it('즐겨찾기 삭제 → DELETE /favorite 발사', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()
    fireEvent.click(screen.getByLabelText('즐겨찾기'))
    fireEvent.click(screen.getByRole('button', { name: '미너비니 추세 템플릿 즐겨찾기 삭제' }))
    await waitFor(() => expect(favoriteCalls).toContainEqual({ method: 'DELETE', id: '7' }))
  })

  it('즐겨찾기 탭에서 OB 매수 후보 만들기 가이드를 연다', async () => {
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await enterBuilder()
    fireEvent.click(screen.getByLabelText('즐겨찾기'))
    fireEvent.click(screen.getByRole('button', { name: '가이드' }))
    expect(screen.getByRole('dialog', { name: 'OB 매수 후보 만들기 가이드' })).toBeInTheDocument()
    expect(screen.getByText(/ob_bullish_active/)).toBeInTheDocument()
  })
})

describe('StrategyBuilderPage — localStorage 흡수', () => {
  it('localStorage 잔존 즐겨찾기(strategyId 식별 가능) → DB 흡수 후 키 제거', async () => {
    window.localStorage.setItem(
      'magicjar.strategyBuilder.customFavorites.v1',
      JSON.stringify([{ id: 7, label: '옛 즐겨찾기' }]),
    )
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await screen.findByText('전략 만들기 시작')
    await waitFor(() => expect(favoriteCalls).toContainEqual({ method: 'POST', id: '7' }))
    expect(window.localStorage.getItem('magicjar.strategyBuilder.customFavorites.v1')).toBeNull()
    expect(window.localStorage.getItem('magicjar.strategyBuilder.favoritesMigrated.v1')).toBe('1')
  })

  it('식별 불가 항목은 흡수하지 않는다', async () => {
    window.localStorage.setItem(
      'magicjar.strategyBuilder.customFavorites.v1',
      JSON.stringify([{ id: 'custom-12345', label: '내가 만든 트리' }]),
    )
    render(<StrategyBuilderPage />, { wrapper: Wrapper })
    await screen.findByText('전략 만들기 시작')
    await waitFor(() =>
      expect(window.localStorage.getItem('magicjar.strategyBuilder.favoritesMigrated.v1')).toBe('1'),
    )
    expect(favoriteCalls.some((c) => c.method === 'POST')).toBe(false)
  })
})

describe('StrategyBuilderPage — deeplink', () => {
  it('?strategyId= → 시작 화면 우회, 캔들 없으면 refresh 후 평가', async () => {
    let candleReads = 0
    let refreshCalls = 0
    let evaluateCalls = 0

    server.use(
      http.get('/api/strategies/:id', () =>
        HttpResponse.json({
          id: 42,
          userId: 1,
          name: 'candle_refresh_test',
          nameKo: '캔들 갱신 테스트',
          entry: 'close > ma(20)',
          exit: null,
          codes: ['005930'],
          intervals: ['1d'],
          status: 'PAUSED',
          createdAt: '2026-06-01T00:00:00+09:00',
        }),
      ),
      http.get('/api/symbols/:code/candles', () => {
        candleReads += 1
        return HttpResponse.json({
          candles:
            candleReads === 1
              ? []
              : [{ code: '005930', interval: '1d', ts: '2026-06-01T00:00:00+09:00', close: 293000 }],
        })
      }),
      http.post('/api/symbols/:code/candles/refresh', ({ request }) => {
        refreshCalls += 1
        expect(new URL(request.url).searchParams.get('intervals')).toBe('1d')
        return HttpResponse.json({ code: '005930', results: [] })
      }),
      http.post('/api/strategy/:id/evaluate', () => {
        evaluateCalls += 1
        return HttpResponse.json({ strategyId: 42, signal: 'HOLD', strength: 0, reason: {}, evalTimeMs: 1 })
      }),
    )

    renderWithRoute('/strategy/builder?strategyId=42')

    const evaluateButton = await screen.findByRole('button', { name: '평가 실행' })
    await waitFor(() => expect(evaluateButton).not.toBeDisabled())
    fireEvent.click(evaluateButton)

    await waitFor(() => {
      expect(refreshCalls).toBe(1)
      expect(evaluateCalls).toBe(1)
    })
  })
})
