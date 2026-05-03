/**
 * SettingsModal 컴포넌트 단위 테스트
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SettingsModal } from './SettingsModal'

// useAuth hook mock — 백엔드 미연결 환경에서 안정적 테스트
vi.mock('@/hooks/useAuth', async () => {
  const actual = await vi.importActual<typeof import('@/hooks/useAuth')>('@/hooks/useAuth')
  return {
    ...actual,
    useAuthStatus: () => ({
      data: {
        authenticated: false,
        mode: null,
        accountNo: null,
        hasAppKey: false,
        hasAppSecret: false,
        lastLoginAt: null,
      },
      isError: false,
      isLoading: false,
    }),
    useLoginMutation: () => ({
      mutateAsync: vi.fn().mockResolvedValue({ authenticated: true, mode: 'vps', message: '로그인 성공' }),
      isPending: false,
    }),
    useChangeMode: () => ({
      mutateAsync: vi.fn().mockResolvedValue({ mode: 'vps', message: '모드 전환 완료' }),
    }),
  }
})

// masterApi mock
vi.mock('@/api/auth', async () => {
  const actual = await vi.importActual<typeof import('@/api/auth')>('@/api/auth')
  return {
    ...actual,
    masterApi: {
      sync: vi.fn().mockResolvedValue({ runId: 'run-001', status: 'QUEUED' }),
      getStatus: vi.fn().mockResolvedValue({ runId: 'run-001', status: 'COMPLETED', processedCount: 100, totalCount: 100 }),
    },
  }
})

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

describe('SettingsModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('open=false 시 렌더링 안 됨', () => {
    render(<SettingsModal open={false} onClose={vi.fn()} />, { wrapper })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('open=true 시 모달 렌더링', () => {
    render(<SettingsModal open={true} onClose={vi.fn()} />, { wrapper })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('설정')).toBeInTheDocument()
  })

  it('기본 탭은 인증', () => {
    render(<SettingsModal open={true} onClose={vi.fn()} />, { wrapper })
    expect(screen.getByText('인증 상태')).toBeInTheDocument()
  })

  it('닫기 버튼 클릭 시 onClose 호출', () => {
    const onClose = vi.fn()
    render(<SettingsModal open={true} onClose={onClose} />, { wrapper })
    fireEvent.click(screen.getByLabelText('닫기'))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('ESC 키 누르면 onClose 호출', async () => {
    const onClose = vi.fn()
    render(<SettingsModal open={true} onClose={onClose} />, { wrapper })
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('탭 전환 — 종목 마스터파일 탭', () => {
    render(<SettingsModal open={true} onClose={vi.fn()} />, { wrapper })
    fireEvent.click(screen.getByText('종목 마스터파일'))
    expect(screen.getByText('마스터파일 수집')).toBeInTheDocument()
  })

  it('탭 전환 — 기타 탭', () => {
    render(<SettingsModal open={true} onClose={vi.fn()} />, { wrapper })
    fireEvent.click(screen.getByText('기타'))
    expect(screen.getByText(/추가 설정은 향후/)).toBeInTheDocument()
  })
})
