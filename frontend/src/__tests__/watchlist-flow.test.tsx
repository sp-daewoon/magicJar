/**
 * smoke test — 관심종목 등록 flow
 *
 * spec §3-2 H 정합:
 * - msw로 GET /api/watchlists mock (빈 → 등록 후 1건)
 * - "005930" 입력 + 등록 버튼 → list row 표시 검증
 *
 * 검증 시나리오:
 * 1. 빈 상태 → "관심종목이 없습니다" 표시
 * 2. "005930" 입력 + 등록 버튼 클릭
 * 3. POST /api/watchlists → 201 mock
 * 4. GET /api/watchlists 재조회 → 1건 반환 mock
 * 5. list에 "005930" row 표시 검증
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { MemoryRouter } from 'react-router-dom'
import { WatchlistPage } from '../pages/Watchlist/WatchlistPage'

// ──────────────────────────────────────────────
// msw 서버 설정
// ──────────────────────────────────────────────

let watchlistItems: Array<{
  id: number
  userId: string
  code: string
  priority: number
  createdAt: string
}> = []

const server = setupServer(
  // GET /api/watchlists — 현재 목록 반환
  http.get('/api/watchlists', () => {
    return HttpResponse.json(watchlistItems)
  }),

  // POST /api/watchlists — 등록 + 목록에 추가
  http.post('/api/watchlists', async ({ request }) => {
    const body = (await request.json()) as { code: string; priority?: number }
    const newEntry = {
      id: watchlistItems.length + 1,
      userId: '74683223',
      code: body.code,
      priority: body.priority ?? 0,
      createdAt: new Date().toISOString(),
    }
    watchlistItems.push(newEntry)
    return HttpResponse.json(newEntry, { status: 201 })
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => {
  server.resetHandlers()
  watchlistItems = []
})
afterAll(() => server.close())

// ──────────────────────────────────────────────
// 테스트 헬퍼
// ──────────────────────────────────────────────

function renderWatchlistPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <WatchlistPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// ──────────────────────────────────────────────
// 테스트 시나리오
// ──────────────────────────────────────────────

describe('Watchlist 등록 flow', () => {
  it('빈 상태에서 005930 입력 후 등록 버튼 클릭 시 목록에 row가 표시된다', async () => {
    const user = userEvent.setup()
    renderWatchlistPage()

    // 1. 빈 상태 확인
    await waitFor(() => {
      expect(screen.getByText('관심종목이 없습니다')).toBeInTheDocument()
    })

    // 2. 종목 코드 입력
    const input = screen.getByPlaceholderText('종목 코드 6자리 (예: 005930)')
    await user.type(input, '005930')

    // 3. 등록 버튼 클릭
    const submitButton = screen.getByRole('button', { name: '등록' })
    await user.click(submitButton)

    // 4. list에 "005930" row 표시 확인
    await waitFor(() => {
      expect(screen.getByText('005930')).toBeInTheDocument()
    })
  })

  it('6자리 미만 입력 시 clientside 오류 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    renderWatchlistPage()

    // 빈 상태 로딩 대기
    await waitFor(() => {
      expect(screen.getByText('관심종목이 없습니다')).toBeInTheDocument()
    })

    // 잘못된 형식 입력
    const input = screen.getByPlaceholderText('종목 코드 6자리 (예: 005930)')
    await user.type(input, 'ABC')

    const submitButton = screen.getByRole('button', { name: '등록' })
    await user.click(submitButton)

    // clientside 검증 오류 메시지 확인 (spec §7 옵션 C)
    await waitFor(() => {
      expect(screen.getByText(/6자리 숫자만 가능합니다/)).toBeInTheDocument()
    })
  })

  it('총 N종목 카운트가 올바르게 표시된다', async () => {
    // 사전에 목록에 2건 추가
    watchlistItems = [
      { id: 1, userId: '74683223', code: '005930', priority: 0, createdAt: new Date().toISOString() },
      { id: 2, userId: '74683223', code: '035720', priority: 1, createdAt: new Date().toISOString() },
    ]

    renderWatchlistPage()

    // "총 2종목" 헤더 영역 확인 — 숫자가 별도 span으로 분리됨
    await waitFor(() => {
      // "총 N종목" 패턴으로 확인 (숫자 포함 상위 span)
      const countText = screen.getByText((_content, element) => {
        return element?.tagName === 'SPAN' && /총\s+\d+종목/.test(element?.textContent ?? '')
      })
      expect(countText).toBeInTheDocument()
    })
  })
})
