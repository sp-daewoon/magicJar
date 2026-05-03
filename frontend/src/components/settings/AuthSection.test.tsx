/**
 * AuthSection 컴포넌트 단위 테스트 (3섹션 버전)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthSection } from './AuthSection'

vi.mock('@/hooks/useAuth', async () => {
  const actual = await vi.importActual<typeof import('@/hooks/useAuth')>('@/hooks/useAuth')
  return {
    ...actual,
    useAuthStatus: () => ({
      data: {
        authenticated: true,
        mode: 'vps',
        accountNo: '1234-XX-XX',
        hasAppKey: true,
        hasAppSecret: true,
        lastUpdatedAt: '2026-05-02T10:00:00+09:00',
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
      mutateAsync: vi.fn().mockResolvedValue({ mode: 'prod', message: '모드 전환 완료' }),
    }),
  }
})

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

describe('AuthSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('인증 상태 카드 렌더링', () => {
    render(<AuthSection />, { wrapper })
    expect(screen.getByText('인증 상태')).toBeInTheDocument()
    expect(screen.getByText('인증됨')).toBeInTheDocument()
  })

  it('모드 배지 표시 — vps는 모의', () => {
    render(<AuthSection />, { wrapper })
    expect(screen.getByText('모의')).toBeInTheDocument()
  })

  it('KIS 모의투자 카드 — App Key 입력 필드 존재', () => {
    render(<AuthSection />, { wrapper })
    const input = screen.getByLabelText('App Key', { selector: '#mock-app-key' })
    expect(input).toBeInTheDocument()
    expect(input).toHaveAttribute('type', 'text')
  })

  it('KIS 모의투자 카드 — App Secret 입력 필드는 password type', () => {
    render(<AuthSection />, { wrapper })
    const secretInput = screen.getByLabelText('App Secret', { selector: '#mock-app-secret' })
    expect(secretInput).toHaveAttribute('type', 'password')
  })

  it('KIS 실전투자 카드 — 실전 App Key 입력 필드 존재', () => {
    render(<AuthSection />, { wrapper })
    const input = screen.getByLabelText('App Key', { selector: '#prod-app-key' })
    expect(input).toBeInTheDocument()
  })

  it('DART 카드 — DART API Key 입력 필드 존재', () => {
    render(<AuthSection />, { wrapper })
    expect(screen.getByLabelText('DART API Key')).toBeInTheDocument()
  })

  it('모드 전환 — 모의/실전 라디오 버튼 존재', () => {
    render(<AuthSection />, { wrapper })
    expect(screen.getByText('매매 시 사용 모드')).toBeInTheDocument()
    expect(screen.getByDisplayValue('vps')).toBeInTheDocument()
    expect(screen.getByDisplayValue('prod')).toBeInTheDocument()
  })

  it('실전투자 카드 — 보안 경고 문구 표시', () => {
    render(<AuthSection />, { wrapper })
    expect(screen.getByText(/실전 키 등록 후 매매 모드를 실전으로/)).toBeInTheDocument()
  })

  it('DART 카드 — 금감원 안내 문구 표시', () => {
    render(<AuthSection />, { wrapper })
    expect(screen.getByText(/금감원 DART API 키/)).toBeInTheDocument()
  })
})
