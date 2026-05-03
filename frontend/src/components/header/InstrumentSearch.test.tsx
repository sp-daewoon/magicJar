/**
 * InstrumentSearch 컴포넌트 테스트
 * 1. debounce 후 검색 결과 드롭다운 렌더
 * 2. 키보드 ↑↓·Enter 네비게이션 → 종목 선택 → navigate 호출
 * 3. Esc 키로 드롭다운 닫기
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import InstrumentSearch from './InstrumentSearch'
import type { SearchResult } from '@/api/types'

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

const MOCK_RESULTS: SearchResult[] = [
  { code: '005930', name: '삼성전자', market: 'KOSPI' },
  { code: '000660', name: 'SK하이닉스', market: 'KOSPI' },
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
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  // ── 시나리오 1: debounce 후 드롭다운 렌더 ─────────────────────────────────
  it('입력 후 debounce가 지나면 검색 결과가 드롭다운에 표시된다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })

    renderComponent()

    const input = screen.getByRole('searchbox', { name: '종목 검색' })
    // 직접 change 이벤트로 입력 (fake timer 환경에서 userEvent.type 대신)
    act(() => {
      input.focus()
      Object.defineProperty(input, 'value', { value: '삼성', writable: true })
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })

    // React onChange를 트리거하려면 fireEvent 사용
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
})
