/**
 * AuthSection 컴포넌트 단위 테스트
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
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
        lastLoginAt: '2026-05-02T10:00:00+09:00',
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

  it('계좌번호 마스킹 표시', () => {
    render(<AuthSection />, { wrapper })
    expect(screen.getByText('1234-XX-XX')).toBeInTheDocument()
  })

  it('App Key·App Secret 등록됨 표시', () => {
    render(<AuthSection />, { wrapper })
    const elements = screen.getAllByText('등록됨')
    expect(elements.length).toBeGreaterThanOrEqual(2)
  })

  it('로그인 폼 렌더링 — App Key·App Secret·계좌번호 입력 필드', () => {
    render(<AuthSection />, { wrapper })
    expect(screen.getByPlaceholderText('App Key를 입력하세요')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('App Secret을 입력하세요')).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/계좌번호/)).toBeInTheDocument()
  })

  it('앱 시크릿 입력 필드는 password type', () => {
    render(<AuthSection />, { wrapper })
    const secretInput = screen.getByPlaceholderText('App Secret을 입력하세요')
    expect(secretInput).toHaveAttribute('type', 'password')
  })

  it('로그인 버튼 — 빈 입력 시 disabled', () => {
    render(<AuthSection />, { wrapper })
    const loginBtn = screen.getByRole('button', { name: '로그인' })
    expect(loginBtn).toBeDisabled()
  })

  it('계좌번호 8자리 이상 입력 시 마스킹 미리보기 표시', () => {
    render(<AuthSection />, { wrapper })
    const input = screen.getByPlaceholderText(/계좌번호/)
    fireEvent.change(input, { target: { value: '12345678' } })
    // maskAccountNo('12345678') → '1234-XX-XX'
    // '표시 형식: 1234-XX-XX' 문구가 포함된 엘리먼트 존재 확인
    const preview = screen.getByText(/표시 형식:/)
    expect(preview).toBeInTheDocument()
    expect(preview.textContent).toContain('1234-XX-XX')
  })

  it('보안 경고 문구 표시', () => {
    render(<AuthSection />, { wrapper })
    expect(screen.getByText(/App Secret은 평문 전송됩니다/)).toBeInTheDocument()
  })
})
