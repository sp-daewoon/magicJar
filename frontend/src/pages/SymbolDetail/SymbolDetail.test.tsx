/**
 * SymbolDetail 페이지 테스트
 * 1. mock 데이터로 페이지 전체 렌더링 (종목명 + 코드 표시)
 * 2. LWC CandleChart + VolumeChart 컨테이너 마운트 확인
 * 3. WebSocket 시세 갱신 → 헤더 현재가 업데이트
 * 4. 로딩 스켈레톤 / 에러 메시지 / 빈 매매일지 상태
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import SymbolDetailPage from './index'
import { generateMockCandles } from '@/api/instruments'
import { generateMockJournalEntries } from '@/api/journal'
import type { TickMessage } from '@/api/types'

// ── STOMP 리스너 레지스트리 (모듈 스코프 공유) ───────────────────────────────
type MsgHandler = (msg: { body: string }) => void
const _stompListeners = new Map<string, MsgHandler>()

vi.mock('@/ws/stomp', () => ({
  stompService: {
    get state() {
      return 'CONNECTED'
    },
    connect: vi.fn(),
    disconnect: vi.fn(),
    subscribe: vi.fn((topic: string, handler: MsgHandler) => {
      _stompListeners.set(topic, handler)
      return () => _stompListeners.delete(topic)
    }),
    onStateChange: vi.fn((_listener: (s: string) => void) => {
      return () => {}
    }),
    publish: vi.fn(),
  },
  subscribeToTopic: vi.fn(),
}))

// ── API mocks ─────────────────────────────────────────────────────────────────
const mockGetInstrument = vi.fn()
const mockGetCandles = vi.fn()
const mockCandlesPage = vi.fn()
const mockListJournal = vi.fn()
const mockGetQuote = vi.fn()

vi.mock('@/api/instruments', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/instruments')>()
  return {
    ...original,
    instrumentsApi: {
      get: (...args: unknown[]) => mockGetInstrument(...args),
      candles: (...args: unknown[]) => mockGetCandles(...args),
      // T19-T6: infinite scroll hook 사용
      candlesPage: (...args: unknown[]) => mockCandlesPage(...args),
      // T2-R4: quote 메서드 mock 추가 (stale/source/session 포함 응답)
      quote: (...args: unknown[]) => mockGetQuote(...args),
    },
  }
})

vi.mock('@/api/journal', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/journal')>()
  return {
    ...original,
    journalApiV2: {
      list: (...args: unknown[]) => mockListJournal(...args),
    },
  }
})

// ── 헬퍼 ────────────────────────────────────────────────────────────────────
function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
    },
  })
}

function renderPage(code = '005930') {
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <MemoryRouter initialEntries={[`/symbol/${code}`]}>
        <Routes>
          <Route path="/symbol/:code" element={<SymbolDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// ── 테스트 ───────────────────────────────────────────────────────────────────
describe('SymbolDetail 페이지', () => {
  beforeEach(() => {
    _stompListeners.clear()
    mockGetInstrument.mockResolvedValue({ code: '005930', name: '삼성전자', market: 'KOSPI' })
    mockGetCandles.mockResolvedValue(generateMockCandles('005930', 30))
    // T19-T6: candlesPage mock (infinite scroll)
    mockCandlesPage.mockResolvedValue(generateMockCandles('005930', 30))
    mockListJournal.mockResolvedValue(generateMockJournalEntries('005930', 3))
    // T2-R4: quote mock 기본값 (LIVE, not stale)
    mockGetQuote.mockResolvedValue({
      code: '005930',
      price: 70_000,
      volume: 10_000_000,
      source: 'LIVE',
      stale: false,
      asOf: new Date().toISOString(),
      session: 'Open',
    })
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  // ── 시나리오 1: 정상 렌더링 ──────────────────────────────────────────────
  it('종목명·코드·시장 구분이 렌더링된다', async () => {
    renderPage('005930')

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })
    expect(screen.getByText('005930')).toBeInTheDocument()
    expect(screen.getByText('KOSPI')).toBeInTheDocument()
  })

  // ── 시나리오 2: LWC 차트 컨테이너 마운트 ─────────────────────────────────
  it('TradingChart 컨테이너가 DOM에 존재한다', async () => {
    const { container } = renderPage('005930')

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })

    // TradingChart(height=480) div[style*="height"] 1개 이상
    const heightDivs = container.querySelectorAll('div[style*="height"]')
    expect(heightDivs.length).toBeGreaterThanOrEqual(1)
  })

  // ── 시나리오 3: WebSocket 시세 갱신 ──────────────────────────────────────
  it('STOMP 시세 메시지 수신 시 현재가가 화면에 반영된다', async () => {
    renderPage('005930')

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })

    // 백엔드 TickEvent 정합 (토픽: /topic/market.tick.{code})
    const tickMsg: TickMessage = {
      code: '005930',
      at: new Date().toISOString(),
      price: 75_000,
      volume: 12_000_000,
      source: 'KIS_WS',
    }

    act(() => {
      const handler = _stompListeners.get('/topic/market.tick.005930')
      handler?.({ body: JSON.stringify(tickMsg) })
    })

    await waitFor(() => {
      // ₩75,000 → 한국 로케일: "75,000" 포함
      expect(screen.getByText(/75,000/)).toBeInTheDocument()
    })
  })

  // ── 시나리오 4a: 로딩 스켈레톤 ───────────────────────────────────────────
  it('종목 정보 로딩 중 animate-pulse 스켈레톤이 표시된다', () => {
    mockGetInstrument.mockReturnValue(new Promise(() => {})) // never resolves

    const { container } = renderPage('005930')
    const skeletons = container.querySelectorAll('.animate-pulse')
    expect(skeletons.length).toBeGreaterThan(0)
  })

  // ── 시나리오 4b: 에러 상태 ───────────────────────────────────────────────
  it('종목 조회 실패 시 에러 메시지가 표시된다', async () => {
    mockGetInstrument.mockRejectedValue({ status: 404, message: 'Not Found' })

    renderPage('INVALID')

    // 컴포넌트가 retry: 1 사용 → 1차 실패 + 재시도 + 2차 실패 = 시간 여유 필요
    await waitFor(
      () => {
        expect(screen.getByText(/종목 정보를 불러오지 못했습니다/)).toBeInTheDocument()
      },
      { timeout: 5000 },
    )
  })

  // ── 시나리오 4c: 빈 매매일지 ─────────────────────────────────────────────
  it('매매 기록이 없을 때 빈 상태 메시지가 표시된다', async () => {
    mockListJournal.mockResolvedValue([])

    renderPage('005930')

    await waitFor(() => {
      expect(screen.getByText(/매매 기록이 없습니다/)).toBeInTheDocument()
    })
  })
})
