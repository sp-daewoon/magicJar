/**
 * MetadataForm 컴포넌트 단위 테스트
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MetadataForm } from './MetadataForm'
import type { StrategyMetadata } from '@/api/strategy'

const emptyMeta: StrategyMetadata = { name: '', description: '', tags: '', author: '' }

describe('MetadataForm', () => {
  it('렌더링 — 4개 필드 표시', () => {
    render(<MetadataForm metadata={emptyMeta} onChange={vi.fn()} />)
    expect(screen.getByLabelText(/전략 이름/)).toBeInTheDocument()
    expect(screen.getByLabelText('설명')).toBeInTheDocument()
    expect(screen.getByLabelText(/태그/)).toBeInTheDocument()
    expect(screen.getByLabelText('저자')).toBeInTheDocument()
  })

  it('이름 입력 → onChange 호출', () => {
    const onChange = vi.fn()
    render(<MetadataForm metadata={emptyMeta} onChange={onChange} />)
    fireEvent.change(screen.getByLabelText(/전략 이름/), { target: { value: '테스트 전략' } })
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ name: '테스트 전략' }))
  })

  it('태그 입력 → 배지 렌더링', () => {
    const meta: StrategyMetadata = { ...emptyMeta, tags: 'MA,모멘텀' }
    render(<MetadataForm metadata={meta} onChange={vi.fn()} />)
    expect(screen.getByText('MA')).toBeInTheDocument()
    expect(screen.getByText('모멘텀')).toBeInTheDocument()
  })

  it('빈 태그 → 배지 미표시', () => {
    render(<MetadataForm metadata={emptyMeta} onChange={vi.fn()} />)
    // MA·모멘텀 배지가 없어야 함
    expect(screen.queryByText('MA')).not.toBeInTheDocument()
  })
})
