/**
 * MasterFileSection 컴포넌트 단위 테스트
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MasterFileSection } from './MasterFileSection'

const mockSync = vi.fn().mockResolvedValue({ runId: 'run-001', status: 'QUEUED' })
const mockGetStatus = vi.fn().mockResolvedValue({
  runId: 'run-001',
  status: 'COMPLETED',
  processedCount: 2500,
  totalCount: 2500,
  completedAt: '2026-05-02T10:05:00+09:00',
})

vi.mock('@/api/auth', async () => {
  const actual = await vi.importActual<typeof import('@/api/auth')>('@/api/auth')
  return {
    ...actual,
    masterApi: {
      sync: (...args: unknown[]) => mockSync(...args),
      getStatus: (...args: unknown[]) => mockGetStatus(...args),
    },
  }
})

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

describe('MasterFileSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('4개 시장 체크박스 렌더링', () => {
    render(<MasterFileSection />, { wrapper })
    expect(screen.getByText(/KOSPI/)).toBeInTheDocument()
    expect(screen.getByText(/KOSDAQ/)).toBeInTheDocument()
    expect(screen.getByText(/ETF/)).toBeInTheDocument()
    expect(screen.getByText(/ETN/)).toBeInTheDocument()
  })

  it('기본으로 4개 시장 모두 체크', () => {
    render(<MasterFileSection />, { wrapper })
    const checkboxes = screen.getAllByRole('checkbox')
    expect(checkboxes).toHaveLength(4)
    checkboxes.forEach((cb) => expect(cb).toBeChecked())
  })

  it('수집 버튼 렌더링', () => {
    render(<MasterFileSection />, { wrapper })
    expect(screen.getByRole('button', { name: '마스터파일 수집' })).toBeInTheDocument()
  })

  it('시장 선택 해제 토글', () => {
    render(<MasterFileSection />, { wrapper })
    const checkboxes = screen.getAllByRole('checkbox')
    fireEvent.click(checkboxes[0]) // KOSPI 해제
    expect(checkboxes[0]).not.toBeChecked()
  })

  it('모두 해제 시 수집 버튼 disabled', () => {
    render(<MasterFileSection />, { wrapper })
    const checkboxes = screen.getAllByRole('checkbox')
    checkboxes.forEach((cb) => fireEvent.click(cb))
    const btn = screen.getByRole('button', { name: '마스터파일 수집' })
    expect(btn).toBeDisabled()
  })

  it('수집 버튼 클릭 시 상태가 IDLE → QUEUED 이상으로 전환', async () => {
    render(<MasterFileSection />, { wrapper })
    // 초기: 수집 버튼 활성 상태
    const btn = screen.getByRole('button', { name: '마스터파일 수집' })
    expect(btn).toBeEnabled()

    fireEvent.click(btn)

    // mockSync가 resolve되면 진행 상태 섹션이 나타남
    await waitFor(
      () => {
        // '수집 상태' 패널이 등장하면 sync 호출이 시작된 것
        expect(mockSync).toHaveBeenCalledTimes(1)
      },
      { timeout: 3000 },
    )
  })
})
