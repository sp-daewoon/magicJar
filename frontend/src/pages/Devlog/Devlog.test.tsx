/**
 * Devlog 페이지 테스트
 * 1. 탭 3개 렌더링 확인 (일지·하네스·빌드/CI)
 * 2. useDevlogList — 정상 응답 시 목록 표시
 * 3. useDevlogList — API 실패 시 mock 데이터 + 경고 메시지
 * 4. 일자 선택 시 iframe URL 설정
 * 5. 검색 필터링
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import DevlogPage from './index'
import type { DevlogListResponse } from '@/api/types'

/* ------------------------------------------------------------------ */
/* API mock                                                             */
/* ------------------------------------------------------------------ */

const mockDevlogList = vi.fn<[], Promise<DevlogListResponse>>()

vi.mock('@/api/client', () => ({
  devlogApi: {
    list: () => mockDevlogList(),
    htmlUrl: (date: string) => `/api/devlog/${date}`,
  },
}))

/* ------------------------------------------------------------------ */
/* 테스트 헬퍼                                                           */
/* ------------------------------------------------------------------ */

function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
}

function renderPage() {
  const qc = makeQueryClient()
  return render(
    <QueryClientProvider client={qc}>
      <DevlogPage />
    </QueryClientProvider>,
  )
}

const SAMPLE_ENTRIES: DevlogListResponse = {
  entries: [
    {
      date: '2026-05-03',
      dayOfWeek: '일',
      mtime: '2026-05-03T20:00:00+09:00',
      sizeBytes: 12800,
      summary: '테스트 일지 요약 A',
    },
    {
      date: '2026-05-02',
      dayOfWeek: '토',
      mtime: '2026-05-02T22:00:00+09:00',
      sizeBytes: 9600,
      summary: '테스트 일지 요약 B',
    },
  ],
}

/* ------------------------------------------------------------------ */
/* 테스트                                                                */
/* ------------------------------------------------------------------ */

describe('DevlogPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('T22-T3: 탭 3개 렌더링', async () => {
    mockDevlogList.mockResolvedValue(SAMPLE_ENTRIES)
    renderPage()
    expect(screen.getByRole('button', { name: '일지' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '하네스' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '빌드/CI' })).toBeInTheDocument()
  })

  it('T22-T3: 기본 탭은 "일지"', async () => {
    mockDevlogList.mockResolvedValue(SAMPLE_ENTRIES)
    renderPage()
    // 일지 탭 콘텐츠: 검색 입력 존재
    await waitFor(() => {
      expect(screen.getByPlaceholderText('일지 검색...')).toBeInTheDocument()
    })
  })

  it('T22-T4: 정상 응답 시 일지 목록 표시', async () => {
    mockDevlogList.mockResolvedValue(SAMPLE_ENTRIES)
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('2026-05-03')).toBeInTheDocument()
      expect(screen.getByText('2026-05-02')).toBeInTheDocument()
    })
  })

  it('T22-T4: API 실패 시 mock 데이터 + 경고 메시지', async () => {
    mockDevlogList.mockRejectedValue(new Error('Network Error'))
    renderPage()
    await waitFor(() => {
      // API 미연결 경고
      expect(screen.getByText(/API 미연결/)).toBeInTheDocument()
      // mock 데이터 표시 (가장 최신 날짜)
      expect(screen.getByText('2026-05-03')).toBeInTheDocument()
    })
  })

  it('T22-T4: 일자 선택 시 iframe src 설정', async () => {
    mockDevlogList.mockResolvedValue(SAMPLE_ENTRIES)
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('2026-05-03')).toBeInTheDocument()
    })

    // 첫 번째 일지 선택
    fireEvent.click(screen.getByText('2026-05-03'))

    await waitFor(() => {
      const iframe = document.querySelector('iframe')
      expect(iframe).not.toBeNull()
      expect(iframe?.src).toContain('/api/devlog/2026-05-03')
    })
  })

  it('T22-T4: 검색어 필터링', async () => {
    mockDevlogList.mockResolvedValue(SAMPLE_ENTRIES)
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('2026-05-03')).toBeInTheDocument()
    })

    // "요약 B" 검색
    fireEvent.change(screen.getByPlaceholderText('일지 검색...'), {
      target: { value: '요약 B' },
    })

    await waitFor(() => {
      // 2026-05-02는 "요약 B" 포함 → 표시
      expect(screen.getByText('2026-05-02')).toBeInTheDocument()
      // 2026-05-03은 "요약 A" → 미표시
      expect(screen.queryByText('2026-05-03')).toBeNull()
    })
  })

  it('T22-T5: 하네스 탭 클릭 시 placeholder 표시', async () => {
    mockDevlogList.mockResolvedValue(SAMPLE_ENTRIES)
    renderPage()

    fireEvent.click(screen.getByRole('button', { name: '하네스' }))
    await waitFor(() => {
      expect(screen.getByText(/에이전트 활동/)).toBeInTheDocument()
      expect(screen.getByText(/다음 사이클/)).toBeInTheDocument()
    })
  })

  it('T22-T5: 빌드/CI 탭 클릭 시 placeholder 표시', async () => {
    mockDevlogList.mockResolvedValue(SAMPLE_ENTRIES)
    renderPage()

    fireEvent.click(screen.getByRole('button', { name: '빌드/CI' }))
    await waitFor(() => {
      expect(screen.getByText(/GitHub Actions/)).toBeInTheDocument()
    })
  })
})
