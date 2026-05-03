/**
 * InstrumentSearch 컴포넌트 테스트
 * 1. debounce 후 검색 결과 드롭다운 렌더
 * 2. 키보드 ↑↓·Enter 네비게이션 → 종목 선택 → navigate 호출
 * 3. Esc 키로 드롭다운 닫기
 * 4. Enter 키 + 결과 1건 → 자동 진입
 * 5. Enter 키 + 결과 다수 + 선택 없음 → 첫 결과 navigate
 * 6. 포커스 + query 없음 + 기록 있음 → 검색 기록 드롭다운 표시
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import InstrumentSearch from './InstrumentSearch'
import type { SearchResult } from '@/api/types'
import type { SearchHistoryItem } from '@/hooks/useSearchHistory'

// ── react-router-dom mock ────────────────────────────────────────────────────
const mockNavigate = vi.fn()
vi.mock('react-router-dom', async (importOriginal) => {
  const original = await importOriginal<typeof import('react-router-dom')>()
  return {
    ...original,
    useNavigate: () => mockNavigate,
  }
})

// ── API mock ─────────────────────────────────────────────────────────────────
const mockSearch = vi.fn()
vi.mock('@/api/instruments', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/instruments')>()
  return {
    ...original,
    instrumentsApi: {
      ...original.instrumentsApi,
      search: (...args: unknown[]) => mockSearch(...args),
    },
  }
})

// ── useSearchHistory mock ────────────────────────────────────────────────────
const mockAddToHistory = vi.fn()
const mockRemoveFromHistory = vi.fn()
const mockClearHistory = vi.fn()
let mockHistoryData: SearchHistoryItem[] = []

vi.mock('@/hooks/useSearchHistory', () => ({
  useSearchHistory: () => ({
    history: mockHistoryData,
    addToHistory: mockAddToHistory,
    removeFromHistory: mockRemoveFromHistory,
    clearHistory: mockClearHistory,
    getHistory: () => mockHistoryData,
  }),
}))

const MOCK_RESULTS: SearchResult[] = [
  { code: '005930', name: '삼성전자', market: 'KOSPI' },
  { code: '000660', name: 'SK하이닉스', market: 'KOSPI' },
]

const SINGLE_RESULT: SearchResult[] = [
  { code: '005930', name: '삼성전자', market: 'KOSPI' },
]

const MOCK_HISTORY: SearchHistoryItem[] = [
  { code: '005930', name: '삼성전자', market: 'KOSPI', lastVisitedAt: new Date().toISOString() },
  { code: '000660', name: 'SK하이닉스', market: 'KOSPI', lastVisitedAt: new Date().toISOString() },
]

function makeQc() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0, gcTime: 0 } } })
}

function renderComponent() {
  return render(
    <QueryClientProvider client={makeQc()}>
      <MemoryRouter>
        <InstrumentSearch />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('InstrumentSearch', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    mockSearch.mockResolvedValue(MOCK_RESULTS)
    mockAddToHistory.mockReset()
    mockRemoveFromHistory.mockReset()
    mockClearHistory.mockReset()
    mockHistoryData = []
    localStorage.clear()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  // ── 시나리오 1: debounce 후 드롭다운 렌더 ─────────────────────────────────
  it('입력 후 debounce가 지나면 검색 결과가 드롭다운에 표시된다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })

    renderComponent()

    const input = screen.getByRole('searchbox', { name: '종목 검색' })
    const { fireEvent } = await import('@testing-library/react')
    act(() => {
      fireEvent.focus(input)
      fireEvent.change(input, { target: { value: '삼성' } })
    })

    // debounce 전 — 아직 API 호출 안 됨
    expect(mockSearch).not.toHaveBeenCalled()

    // 300ms 이상 경과
    await act(async () => {
      vi.advanceTimersByTime(400)
    })

    await waitFor(() => {
      expect(mockSearch).toHaveBeenCalledWith('삼성', 10)
    })

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })

    vi.useRealTimers()
  })

  // ── 시나리오 2: 키보드 ↓·Enter → navigate ─────────────────────────────────
  it('ArrowDown + Enter 키로 첫 번째 결과를 선택하면 navigate가 호출된다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { fireEvent } = await import('@testing-library/react')

    renderComponent()

    const input = screen.getByRole('searchbox', { name: '종목 검색' })
    act(() => {
      fireEvent.focus(input)
      fireEvent.change(input, { target: { value: '삼성' } })
    })

    await act(async () => {
      vi.advanceTimersByTime(400)
    })

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })

    // ArrowDown → Enter
    act(() => {
      fireEvent.keyDown(input, { key: 'ArrowDown' })
    })
    act(() => {
      fireEvent.keyDown(input, { key: 'Enter' })
    })

    expect(mockNavigate).toHaveBeenCalledWith('/symbol/005930')

    vi.useRealTimers()
  })

  // ── 시나리오 3: Esc 키 드롭다운 닫기 ─────────────────────────────────────
  it('Esc 키를 누르면 드롭다운이 닫힌다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { fireEvent } = await import('@testing-library/react')

    renderComponent()

    const input = screen.getByRole('searchbox', { name: '종목 검색' })
    act(() => {
      fireEvent.focus(input)
      fireEvent.change(input, { target: { value: '삼성' } })
    })

    await act(async () => {
      vi.advanceTimersByTime(400)
    })

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })

    act(() => {
      fireEvent.keyDown(input, { key: 'Escape' })
    })

    await waitFor(() => {
      expect(screen.queryByText('삼성전자')).not.toBeInTheDocument()
    })

    vi.useRealTimers()
  })

  // ── 시나리오 4: Enter + 결과 1건 → 자동 진입 ─────────────────────────────
  it('Enter 키 + 결과 1건이면 자동으로 해당 종목 상세로 이동한다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mockSearch.mockResolvedValue(SINGLE_RESULT)
    const { fireEvent } = await import('@testing-library/react')

    renderComponent()

    const input = screen.getByRole('searchbox', { name: '종목 검색' })
    act(() => {
      fireEvent.focus(input)
      fireEvent.change(input, { target: { value: '삼성전자' } })
    })

    await act(async () => {
      vi.advanceTimersByTime(400)
    })

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })

    // 선택 없이 Enter
    act(() => {
      fireEvent.keyDown(input, { key: 'Enter' })
    })

    expect(mockNavigate).toHaveBeenCalledWith('/symbol/005930')

    vi.useRealTimers()
  })

  // ── 시나리오 5: Enter + 다수 결과 + 선택 없음 → 첫 결과 navigate ──────────
  it('Enter 키 + 다수 결과 + 선택 없음이면 첫 번째 결과로 이동한다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mockSearch.mockResolvedValue(MOCK_RESULTS)
    const { fireEvent } = await import('@testing-library/react')

    renderComponent()

    const input = screen.getByRole('searchbox', { name: '종목 검색' })
    act(() => {
      fireEvent.focus(input)
      fireEvent.change(input, { target: { value: '전자' } })
    })

    await act(async () => {
      vi.advanceTimersByTime(400)
    })

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })

    // 선택 없이 Enter (focusedIdx = -1)
    act(() => {
      fireEvent.keyDown(input, { key: 'Enter' })
    })

    // 첫 번째 결과(005930)로 navigate
    expect(mockNavigate).toHaveBeenCalledWith('/symbol/005930')

    vi.useRealTimers()
  })

  // ── 시나리오 6: 포커스 + query 없음 + 기록 있음 → 기록 dropdown 표시 ──────
  it('포커스 후 query가 없고 기록이 있으면 검색 기록 드롭다운이 표시된다', async () => {
    mockHistoryData = MOCK_HISTORY
    const { fireEvent } = await import('@testing-library/react')

    renderComponent()

    const input = screen.getByRole('searchbox', { name: '종목 검색' })
    act(() => {
      fireEvent.focus(input)
    })

    await waitFor(() => {
      expect(screen.getByRole('listbox', { name: '검색 기록' })).toBeInTheDocument()
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
      expect(screen.getByText('SK하이닉스')).toBeInTheDocument()
    })
  })
})
