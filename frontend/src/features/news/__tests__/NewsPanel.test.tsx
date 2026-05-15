/**
 * NewsPanel 단위 테스트 — 4 케이스
 *
 * ADR 0075 spec §2-3 단위 테스트 매트릭스:
 *   1. 빈 응답 graceful — "최근 7일간 뉴스가 없습니다" 표시
 *   2. items 5건 render — 각 카드 제목 표시
 *   3. 페이지네이션 — totalPages > 1 시 "더 보기" 버튼 표시
 *   4. 페이지네이션 — 마지막 페이지면 "더 보기" 버튼 미표시
 *
 * STOMP 0 검증: useSymbolNews mock에 STOMP client 없음.
 * mock 합성 0: 빈 응답 시 "데이터 없음" 한국어 표시.
 */
import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { NewsPanel } from '../components/NewsPanel'
import type { SymbolNewsListResponse } from '../api/newsApi'

// useSymbolNews hook mock
vi.mock('../hooks/useSymbolNews', () => ({
  useSymbolNews: vi.fn(),
}))

import { useSymbolNews } from '../hooks/useSymbolNews'
const mockUseSymbolNews = vi.mocked(useSymbolNews)

// ──────────────────────────────────────────────
// 헬퍼 — NewsPanel 래퍼
// ──────────────────────────────────────────────

function renderNewsPanel(code = '005930') {
  return render(<NewsPanel code={code} />)
}

function makeResponse(overrides: Partial<SymbolNewsListResponse> = {}): SymbolNewsListResponse {
  return {
    stockCode: '005930',
    items: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    ...overrides,
  }
}

function makeNewsItems(count: number) {
  return Array.from({ length: count }, (_, i) => ({
    id: i + 1,
    source: 'NAVER',
    title: `뉴스 제목 ${i + 1}`,
    bodySummary: `요약 ${i + 1}`,
    url: `https://n.news.naver.com/article/${i + 1}`,
    publishedAt: '2026-05-15T09:00:00+09:00',
    sentiment: null,
  }))
}

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('NewsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('빈 응답 시 "최근 7일간 뉴스가 없습니다" 한국어 표시 (mock 합성 0 LOCK)', () => {
    mockUseSymbolNews.mockReturnValue({
      data: makeResponse(),
      isLoading: false,
      isError: false,
    } as ReturnType<typeof useSymbolNews>)

    renderNewsPanel()

    expect(screen.getByText('최근 7일간 뉴스가 없습니다.')).toBeInTheDocument()
    // NAVER_OPEN_API_CLIENT_ID 안내 표시
    expect(screen.getByText(/NAVER_OPEN_API_CLIENT_ID/)).toBeInTheDocument()
  })

  it('뉴스 5건 응답 시 5개 제목 모두 렌더링', () => {
    const items = makeNewsItems(5)
    mockUseSymbolNews.mockReturnValue({
      data: makeResponse({ items, totalElements: 5, totalPages: 1 }),
      isLoading: false,
      isError: false,
    } as ReturnType<typeof useSymbolNews>)

    renderNewsPanel()

    for (let i = 1; i <= 5; i++) {
      expect(screen.getByText(`뉴스 제목 ${i}`)).toBeInTheDocument()
    }
  })

  it('totalPages > 1 시 "더 보기" 버튼 표시', () => {
    const items = makeNewsItems(20)
    mockUseSymbolNews.mockReturnValue({
      data: makeResponse({ items, totalElements: 40, totalPages: 2 }),
      isLoading: false,
      isError: false,
    } as ReturnType<typeof useSymbolNews>)

    renderNewsPanel()

    expect(screen.getByRole('button', { name: '더 보기' })).toBeInTheDocument()
  })

  it('마지막 페이지(totalPages=1)에서는 "더 보기" 버튼 미표시', () => {
    const items = makeNewsItems(3)
    mockUseSymbolNews.mockReturnValue({
      data: makeResponse({ items, totalElements: 3, totalPages: 1 }),
      isLoading: false,
      isError: false,
    } as ReturnType<typeof useSymbolNews>)

    renderNewsPanel()

    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })
})
