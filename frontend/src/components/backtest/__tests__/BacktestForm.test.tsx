import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { BacktestForm } from '../BacktestForm'
import type { BacktestRunRequest } from '../../../api/backtest'

let lastBacktestBody: BacktestRunRequest | null = null

const instrumentFixtures = [
  { code: '005930', name: '삼성전자', market: 'KOSPI', sector: '전기전자', score: 1.0 },
  { code: '000660', name: 'SK하이닉스', market: 'KOSPI', sector: '반도체', score: 1.0 },
]

const server = setupServer(
  http.get('/api/strategies', () =>
    HttpResponse.json({
      content: [
        {
          id: 7,
          userId: 1,
          name: 'rsi-breakout',
          nameKo: 'RSI 돌파',
          codes: [],
          intervals: ['1d'],
          status: 'ACTIVE',
          createdAt: '2026-06-06T00:00:00Z',
        },
      ],
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
      last: true,
    }),
  ),
  http.get('/api/instruments/search', ({ request }) => {
    const query = (new URL(request.url).searchParams.get('q') ?? '').trim()
    const results = instrumentFixtures.filter((item) =>
      item.name.includes(query) || item.code.includes(query),
    )
    return HttpResponse.json({
      query,
      results,
      totalMatched: results.length,
      limit: 10,
    })
  }),
  http.post('/api/backtests', async ({ request }) => {
    lastBacktestBody = await request.json() as BacktestRunRequest
    return HttpResponse.json({
      runId: 43,
      status: 'SUCCESS',
      trades: [],
      positions: [],
      finalCapital: '10000000',
      performance: null,
      abortReason: null,
      durationMs: 12,
    }, { status: 201 })
  }),
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  lastBacktestBody = null
})
afterAll(() => server.close())

function renderForm(onSuccess = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <BacktestForm userId={1} onSuccess={onSuccess} />
    </QueryClientProvider>,
  )
  return { onSuccess }
}

describe('BacktestForm', () => {
  it('종목명 자동완성으로 여러 종목을 선택하면 universe에는 6자리 코드 배열만 보낸다', async () => {
    const user = userEvent.setup()
    const { onSuccess } = renderForm()

    await screen.findByRole('option', { name: /RSI 돌파/ })
    await user.selectOptions(screen.getByLabelText('전략 선택'), '7')

    const input = screen.getByRole('combobox', { name: '종목 검색' })
    await user.type(input, '삼성')
    await user.click(await screen.findByRole('option', { name: /삼성전자/ }))

    await waitFor(() => expect(input).toHaveValue(''))
    await user.type(input, '하이닉스')
    await user.click(await screen.findByRole('option', { name: /SK하이닉스/ }))

    expect(screen.getByRole('button', { name: '삼성전자 제거' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'SK하이닉스 제거' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '백테스트 실행' }))

    await waitFor(() => expect(lastBacktestBody).not.toBeNull())
    expect(lastBacktestBody?.strategyId).toBe(7)
    expect(lastBacktestBody?.universe).toEqual(['005930', '000660'])
    expect(onSuccess).toHaveBeenCalled()
  })
})
