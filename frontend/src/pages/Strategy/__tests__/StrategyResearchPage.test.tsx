import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { StrategyResearchPage } from '../StrategyResearchPage'
import { useOnboardingStore } from '../../../stores/onboarding'
import type {
  StrategyResearchAiDiscoveryRequestDto,
  StrategyResearchRequestDto,
} from '../../../api/strategyResearch'

vi.mock('lightweight-charts', () => ({
  createChart: () => ({
    addCandlestickSeries: () => ({
      setData: vi.fn(),
      setMarkers: vi.fn(),
    }),
    remove: vi.fn(),
    subscribeCrosshairMove: vi.fn(),
    timeScale: () => ({
      fitContent: vi.fn(),
      subscribeVisibleLogicalRangeChange: vi.fn(),
    }),
  }),
}))

let lastResearchBody: StrategyResearchRequestDto | null = null
let lastDiscoveryBody: StrategyResearchAiDiscoveryRequestDto | null = null

const server = setupServer(
  http.get('/api/instruments/search', ({ request }) => {
    const query = new URL(request.url).searchParams.get('q') ?? ''
    return HttpResponse.json({
      query,
      results: [
        { code: '005930', name: '삼성전자', market: 'KOSPI', sector: '전기전자', score: 1.0 },
      ],
      totalMatched: 1,
      limit: 10,
    })
  }),
  http.post('/api/strategy-research/proposals', async ({ request }) => {
    lastResearchBody = await request.json() as StrategyResearchRequestDto
    return HttpResponse.json({
      request: lastResearchBody,
      warnings: [],
      marketRegime: {
        type: 'UPTREND',
        trendPct: 1.2,
        volatilityPct: 2.1,
        explanation: '상승 추세',
      },
      candleCount: 1,
      candles: [
        {
          ts: '2026-06-02T00:00:00Z',
          open: 70000,
          high: 71000,
          low: 69000,
          close: 70500,
          volume: 1000,
        },
      ],
      candidates: [],
    })
  }),
  http.post('/api/strategy-research/ai-discovery', async ({ request }) => {
    lastDiscoveryBody = await request.json() as StrategyResearchAiDiscoveryRequestDto
    return HttpResponse.json({
      query: lastDiscoveryBody.query,
      market: lastDiscoveryBody.market ?? null,
      limit: lastDiscoveryBody.limit,
      provider: 'claude-cli-bridge',
      generatedAt: '2026-06-06T00:00:00Z',
      warnings: ['AI 후보는 검증용입니다.'],
      items: [
        {
          itemKey: 'CODE:005930',
          code: '005930',
          name: '삼성전자',
          market: 'KOSPI',
          sector: '전기전자',
          theme: '반도체',
          aiReason: '반도체와 AI 인프라 후보',
          themes: ['반도체', 'AI 인프라'],
          confidence: 0.82,
          matched: true,
          rawName: '삼성전자',
          rawCode: '005930',
          tradingHalt: false,
          managementStock: false,
          status: 'NEW',
        },
      ],
    })
  }),
  http.get('/api/strategy-research/ai-discovery/latest', () => {
    return HttpResponse.json({ report: null })
  }),
  http.patch('/api/strategy-research/ai-discovery/item-status', async ({ request }) => {
    const body = await request.json() as { itemKey: string; status: 'NEW' | 'KEPT' | 'DISMISSED' }
    return HttpResponse.json({
      query: '반도체 후보',
      market: null,
      limit: 6,
      provider: 'claude-cli-bridge',
      generatedAt: '2026-06-06T00:00:00Z',
      warnings: [],
      items: [
        {
          itemKey: body.itemKey,
          code: '005930',
          name: '삼성전자',
          market: 'KOSPI',
          sector: '전기전자',
          theme: '반도체',
          aiReason: '반도체와 AI 인프라 후보',
          themes: ['반도체', 'AI 인프라'],
          confidence: 0.82,
          matched: true,
          rawName: '삼성전자',
          rawCode: '005930',
          tradingHalt: false,
          managementStock: false,
          status: body.status,
        },
      ],
    })
  }),
  http.delete('/api/strategy-research/ai-discovery/latest', () => {
    return new HttpResponse(null, { status: 204 })
  }),
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  lastResearchBody = null
  lastDiscoveryBody = null
  window.localStorage.clear()
  act(() => {
    useOnboardingStore.setState({ registeredUserId: null })
  })
})
afterAll(() => server.close())

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/strategy/research']}>
        <StrategyResearchPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('StrategyResearchPage', () => {
  it('종목명 자동완성 선택 후 리서치 요청에는 6자리 코드만 보낸다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    const input = screen.getByRole('combobox', { name: '종목 검색' })
    await user.clear(input)
    await user.type(input, '삼성전자')

    await user.click(await screen.findByRole('option', { name: /삼성전자/ }))
    await user.click(screen.getByRole('button', { name: '리서치' }))

    await waitFor(() => expect(lastResearchBody).not.toBeNull())
    expect(lastResearchBody?.code).toBe('005930')
    expect(lastResearchBody?.userId).toBe('74683223')
  })

  it('AI 종목발굴 후보 선택 후 기존 리서치 요청으로 이어진다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    await user.click(screen.getByRole('button', { name: '발굴' }))
    expect(await screen.findByText('반도체와 AI 인프라 후보')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '선택' }))
    await user.click(screen.getByRole('button', { name: '리서치' }))

    await waitFor(() => expect(lastResearchBody).not.toBeNull())
    expect(lastDiscoveryBody?.userId).toBe('74683223')
    expect(lastResearchBody?.code).toBe('005930')
  })

  it('AI 종목발굴 진행 중 로딩 상태를 보여준다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    server.use(
      http.post('/api/strategy-research/ai-discovery', async ({ request }) => {
        lastDiscoveryBody = await request.json() as StrategyResearchAiDiscoveryRequestDto
        await new Promise((resolve) => window.setTimeout(resolve, 80))
        return HttpResponse.json({
          query: lastDiscoveryBody.query,
          market: lastDiscoveryBody.market ?? null,
          limit: lastDiscoveryBody.limit,
          provider: 'claude-cli-bridge',
          generatedAt: '2026-06-06T00:00:00Z',
          warnings: [],
          items: [
            {
              itemKey: 'CODE:005930',
              code: '005930',
              name: '삼성전자',
              market: 'KOSPI',
              sector: '전기전자',
              theme: '반도체',
              aiReason: '반도체와 AI 인프라 후보',
              themes: ['반도체'],
              confidence: 0.82,
              matched: true,
              rawName: '삼성전자',
              rawCode: '005930',
              tradingHalt: false,
              managementStock: false,
              status: 'NEW',
            },
          ],
        })
      }),
    )

    renderPage()

    await user.click(screen.getByRole('button', { name: '발굴' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Claude CLI가 후보를 찾는 중입니다')
    expect(screen.getByRole('button', { name: '발굴 중' })).toBeDisabled()
    expect(screen.getByLabelText('찾을 조건')).toBeDisabled()

    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
    expect(await screen.findByText('반도체와 AI 인프라 후보')).toBeInTheDocument()
  })

  it('AI 종목발굴 후보를 유지하거나 제외할 수 있다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })

    renderPage()

    await user.click(screen.getByRole('button', { name: '발굴' }))
    expect(await screen.findByText('반도체와 AI 인프라 후보')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '유지' }))
    expect(await screen.findByText('유지 해제')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '제외' }))
    await waitFor(() => expect(screen.queryByText('반도체와 AI 인프라 후보')).not.toBeInTheDocument())
  })

  it('유지 후보가 표시 개수보다 많으면 AI 발굴 요청을 보내지 않는다', async () => {
    const user = userEvent.setup()
    act(() => {
      useOnboardingStore.setState({ registeredUserId: 74683223 })
    })
    server.use(
      http.get('/api/strategy-research/ai-discovery/latest', () => {
        return HttpResponse.json({
          report: {
            query: '한국 주식중 가장 핫한 주식을 찾아줘',
            market: null,
            limit: 3,
            provider: 'claude-cli-bridge',
            generatedAt: '2026-06-06T00:00:00Z',
            warnings: [],
            items: [
              {
                itemKey: 'CODE:005930',
                code: '005930',
                name: '삼성전자',
                market: 'KOSPI',
                sector: '전기전자',
                theme: '반도체',
                aiReason: '첫 번째 유지 후보',
                themes: ['반도체'],
                confidence: 0.8,
                matched: true,
                rawName: '삼성전자',
                rawCode: '005930',
                tradingHalt: false,
                managementStock: false,
                status: 'KEPT',
              },
              {
                itemKey: 'CODE:042700',
                code: '042700',
                name: '한미반도체',
                market: 'KOSPI',
                sector: '기계',
                theme: 'HBM',
                aiReason: '두 번째 유지 후보',
                themes: ['HBM'],
                confidence: 0.8,
                matched: true,
                rawName: '한미반도체',
                rawCode: '042700',
                tradingHalt: false,
                managementStock: false,
                status: 'KEPT',
              },
              {
                itemKey: 'CODE:058470',
                code: '058470',
                name: '리노공업',
                market: 'KOSDAQ',
                sector: '반도체',
                theme: '테스트',
                aiReason: '세 번째 유지 후보',
                themes: ['테스트'],
                confidence: 0.8,
                matched: true,
                rawName: '리노공업',
                rawCode: '058470',
                tradingHalt: false,
                managementStock: false,
                status: 'KEPT',
              },
            ],
          },
        })
      }),
    )

    renderPage()

    expect(await screen.findByText('첫 번째 유지 후보')).toBeInTheDocument()
    const limitInput = screen.getByLabelText('표시 개수')
    await user.clear(limitInput)
    await user.type(limitInput, '2')
    await user.click(screen.getByRole('button', { name: '발굴' }))

    expect(await screen.findByText(/유지 후보가 3건이라 표시 개수는 최소 3건입니다/)).toBeInTheDocument()
    expect(lastDiscoveryBody).toBeNull()
  })
})
