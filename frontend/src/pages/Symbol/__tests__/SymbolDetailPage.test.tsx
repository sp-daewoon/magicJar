/**
 * SymbolDetailPage smoke test — PR-10a (룰 4 정합: smoke 1건)
 *
 * spec §11-2 정합:
 * - vitest mock useSymbol + useSymbolCandles → 카드 렌더링 검증
 * - msw로 GET /api/symbols/{code} mock 응답
 * - SymbolHeader · SymbolPriceCard · SymbolMetaCard 렌더링 확인
 * - HTTP 503 에러 시 SymbolError 표시 확인
 *
 * 주의: LWC v4 (lightweight-charts)는 jsdom 환경에서 canvas 미지원 → chart 영역은 mock
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SymbolDetailPage } from '../SymbolDetailPage'

// ──────────────────────────────────────────────
// LWC canvas mock (jsdom 미지원 — graceful)
// ──────────────────────────────────────────────

vi.mock('../LightweightChart', () => ({
  LightweightChart: () => <div data-testid="mock-lightweight-chart" aria-label="종목 차트" />,
}))

// ──────────────────────────────────────────────
// STOMP mock (WebSocket 미지원 환경)
// ──────────────────────────────────────────────

vi.mock('../../../lib/stomp', () => ({
  useStompTopic: vi.fn(),
  isStompConnected: () => false,
}))

// ──────────────────────────────────────────────
// msw 서버 설정
// ──────────────────────────────────────────────

const mockSymbolData = {
  code: '005930',
  name: '삼성전자',
  market: 'KOSPI',
  industry: '전기전자',
  currentPrice: '60900',
  previousClose: '60500',
  change: '400',
  changeRate: '0.66',
  high: '61000',
  low: '60750',
  open: '60800',
  volume: 12345678,
  marketCap: '4800000',
  per: '12.5',
  pbr: '1.3',
  eps: '4872',
  bps: '46850',
  asOf: '2026-05-07T14:23:45.123Z',
  source: 'live' as const,
  stale: false,
}

const mockCandlesData = {
  code: '005930',
  interval: '1d',
  candles: [
    { ts: '2026-05-06T15:30:00Z', open: '60500', high: '61200', low: '60400', close: '60900', volume: 12345678 },
  ],
  hasMore: false,
}

const server = setupServer(
  http.get('/api/symbols/005930', () => {
    return HttpResponse.json(mockSymbolData)
  }),

  http.get('/api/symbols/005930/candles', () => {
    return HttpResponse.json(mockCandlesData)
  }),

  // orderbook subscribe — graceful (백엔드 미완 시 무시)
  http.post('/api/symbols/005930/orderbook/subscribe', () => {
    return HttpResponse.json({ subscribed: true, slot: 'live' })
  }),

  // 잘못된 코드 → 404
  http.get('/api/symbols/999999', () => {
    return HttpResponse.json(
      { error: 'DATA_UNAVAILABLE', message: '종목 데이터를 가져올 수 없습니다' },
      { status: 503 },
    )
  }),

  http.get('/api/symbols/999999/candles', () => {
    return HttpResponse.json(
      { error: 'DATA_UNAVAILABLE', message: '종목 데이터를 가져올 수 없습니다' },
      { status: 503 },
    )
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// ──────────────────────────────────────────────
// 헬퍼
// ──────────────────────────────────────────────

function renderSymbolDetailPage(code: string) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/symbol/${code}`]}>
        <Routes>
          <Route path="/symbol/:code" element={<SymbolDetailPage />} />
          <Route path="/watchlist" element={<div>관심종목 페이지</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('SymbolDetailPage smoke', () => {
  it('종목 데이터 로드 후 헤더·현재가 카드·메타 카드가 표시된다', async () => {
    renderSymbolDetailPage('005930')

    // 로딩 스피너 표시 후 데이터 로드
    await waitFor(() => {
      // 종목명 헤더
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })

    // 종목 코드
    expect(screen.getByText('(005930)')).toBeInTheDocument()

    // 시장 배지
    expect(screen.getByText('KOSPI')).toBeInTheDocument()

    // 산업 배지
    expect(screen.getByText('전기전자')).toBeInTheDocument()

    // 현재가 (천 단위 콤마 포맷: 60,900)
    expect(screen.getByText('60,900')).toBeInTheDocument()

    // 메타 카드: PER
    expect(screen.getByText('PER')).toBeInTheDocument()
    expect(screen.getByText('12.5')).toBeInTheDocument()

    // [관심종목으로] 버튼 (aria-label: "관심종목 목록으로 돌아가기")
    expect(screen.getByRole('button', { name: /관심종목 목록으로 돌아가기/ })).toBeInTheDocument()
  })

  it('잘못된 종목 코드 진입 시 SymbolError가 표시된다 (DoD #12)', async () => {
    renderSymbolDetailPage('999999')

    // retry: false 설정이지만 axios 에러 응답 처리 대기
    await waitFor(
      () => {
        expect(screen.getByText('종목 정보를 찾을 수 없습니다')).toBeInTheDocument()
      },
      { timeout: 5000 },
    )

    // 종목 코드 표시
    expect(screen.getByText('999999')).toBeInTheDocument()
  })
})
