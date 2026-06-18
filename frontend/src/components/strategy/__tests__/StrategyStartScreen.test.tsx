/**
 * StrategyStartScreen 테스트 (spec 2026-06-18 §10)
 *
 * 검증:
 * - 템플릿·내 전략 렌더 (API 응답 그대로, 개수 하드코딩 0)
 * - 카드 클릭 → onPick 호출
 * - 즐겨찾기 토글 → POST/DELETE 발사 + invalidate
 * - 검색 필터
 * - 빈 응답 → "빈 캔버스로" 노출 (mock 0)
 * - GET 실패 → 폴백 노출 (빌더 접근 비차단)
 * - 말로 설명 / 빈 캔버스 버튼
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, expect, it, beforeAll, afterAll, afterEach, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { StrategyStartScreen } from '../StrategyStartScreen'

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

const mineItem = {
  id: 7,
  name: 'my-strategy',
  nameKo: '나의 단타 전략',
  tf: '1d',
  dslEntry: 'close > ma(20)',
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
      myStrategies: [mineItem],
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
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  favoriteCalls.length = 0
  vi.restoreAllMocks()
})
afterAll(() => server.close())

function renderScreen(overrides: Partial<Parameters<typeof StrategyStartScreen>[0]> = {}) {
  const onPick = vi.fn()
  const onBlank = vi.fn()
  const onDescribe = vi.fn()
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <StrategyStartScreen
        userId={1}
        onPick={onPick}
        onBlank={onBlank}
        onDescribe={onDescribe}
        {...overrides}
      />
    </QueryClientProvider>,
  )
  return { onPick, onBlank, onDescribe }
}

describe('StrategyStartScreen', () => {
  it('템플릿·내 전략 카드 렌더 (개수는 API 응답 그대로)', async () => {
    renderScreen()
    expect(await screen.findByText('FVG 매수 후보')).toBeInTheDocument()
    expect(screen.getByText('나의 단타 전략')).toBeInTheDocument()
    // 검증 템플릿 헤더에 개수(1)가 API 응답 그대로
    expect(screen.getByText(/검증 템플릿/)).toBeInTheDocument()
    expect(screen.getByText('(1)')).toBeInTheDocument()
  })

  it('카드 클릭 → onPick 호출 (해당 item 전달)', async () => {
    const { onPick } = renderScreen()
    const card = await screen.findByLabelText('FVG 매수 후보 불러오기')
    fireEvent.click(card)
    expect(onPick).toHaveBeenCalledTimes(1)
    expect(onPick.mock.calls[0][0]).toMatchObject({ id: 101, name: 'fvg-bullish-reclaim' })
  })

  it('즐겨찾기 추가 토글 → POST 발사', async () => {
    renderScreen()
    const star = await screen.findByLabelText('FVG 매수 후보 즐겨찾기 추가')
    fireEvent.click(star)
    await waitFor(() => expect(favoriteCalls).toContainEqual({ method: 'POST', id: '101' }))
  })

  it('즐겨찾기 해제 토글 → DELETE 발사', async () => {
    renderScreen()
    const star = await screen.findByLabelText('나의 단타 전략 즐겨찾기 해제')
    fireEvent.click(star)
    await waitFor(() => expect(favoriteCalls).toContainEqual({ method: 'DELETE', id: '7' }))
  })

  it('검색 필터 — 매칭 안 되면 카드 숨김', async () => {
    renderScreen()
    await screen.findByText('FVG 매수 후보')
    fireEvent.change(screen.getByLabelText('전략 검색'), { target: { value: 'FVG' } })
    expect(screen.getByText('FVG 매수 후보')).toBeInTheDocument()
    expect(screen.queryByText('나의 단타 전략')).not.toBeInTheDocument()
  })

  it('빈 응답 → 빈 캔버스 안내 (mock 0)', async () => {
    server.use(
      http.get('/api/strategies/start', () =>
        HttpResponse.json({ templates: [], myStrategies: [], favoriteIds: [] }),
      ),
    )
    const { onBlank } = renderScreen()
    expect(await screen.findByText(/아직 템플릿·전략이 없습니다/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('빈 캔버스로 직접 시작'))
    expect(onBlank).toHaveBeenCalled()
  })

  it('GET 실패 → 폴백 노출 + 빈 캔버스 버튼', async () => {
    server.use(
      http.get('/api/strategies/start', () => new HttpResponse(null, { status: 500 })),
    )
    const { onBlank } = renderScreen()
    expect(await screen.findByText(/시작 화면을 불러오지 못했습니다/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('빈 캔버스로 직접 시작하기'))
    expect(onBlank).toHaveBeenCalled()
  })

  it('말로 설명 / 빈 캔버스 입구 버튼', async () => {
    const { onDescribe, onBlank } = renderScreen()
    await screen.findByText('FVG 매수 후보')
    fireEvent.click(screen.getByText('💬 말로 설명해서 만들기'))
    expect(onDescribe).toHaveBeenCalled()
    fireEvent.click(screen.getByText('빈 캔버스로 직접'))
    expect(onBlank).toHaveBeenCalled()
  })
})
