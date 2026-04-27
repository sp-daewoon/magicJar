import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import App from './App'

describe('App', () => {
  it('렌더링 — RouterProvider와 QueryClientProvider 포함', () => {
    render(<App />)
    // 네비게이션 링크가 렌더링되는지 확인
    expect(screen.getByText('magicJar')).toBeInTheDocument()
  })

  it('네비게이션 링크 5개 렌더링', () => {
    render(<App />)
    expect(screen.getByText('대시보드')).toBeInTheDocument()
    expect(screen.getByText('백테스트')).toBeInTheDocument()
    expect(screen.getByText('매매일지')).toBeInTheDocument()
    expect(screen.getByText('전략')).toBeInTheDocument()
    expect(screen.getByText('하네스')).toBeInTheDocument()
  })
})
