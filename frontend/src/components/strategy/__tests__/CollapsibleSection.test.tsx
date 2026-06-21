/**
 * CollapsibleSection 단위 테스트 — 빌더 고도화 C
 */
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CollapsibleSection } from '../CollapsibleSection'

describe('CollapsibleSection', () => {
  it('defaultOpen=false — 접힘 상태, 내용 미표시', () => {
    render(
      <CollapsibleSection title="고급" defaultOpen={false}>
        <div>접힌 내용</div>
      </CollapsibleSection>,
    )
    expect(screen.queryByText('접힌 내용')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /고급/ })).toHaveAttribute('aria-expanded', 'false')
  })

  it('클릭 시 펼침 — 내용 표시', () => {
    render(
      <CollapsibleSection title="고급" defaultOpen={false}>
        <div>접힌 내용</div>
      </CollapsibleSection>,
    )
    fireEvent.click(screen.getByRole('button', { name: /고급/ }))
    expect(screen.getByText('접힌 내용')).toBeInTheDocument()
  })

  it('defaultOpen=true — 처음부터 펼침', () => {
    render(
      <CollapsibleSection title="필수" defaultOpen>
        <div>펼친 내용</div>
      </CollapsibleSection>,
    )
    expect(screen.getByText('펼친 내용')).toBeInTheDocument()
  })

  it('다시 클릭 시 접힘 토글', () => {
    render(
      <CollapsibleSection title="고급" defaultOpen>
        <div>토글 내용</div>
      </CollapsibleSection>,
    )
    const btn = screen.getByRole('button', { name: /고급/ })
    fireEvent.click(btn)
    expect(screen.queryByText('토글 내용')).not.toBeInTheDocument()
  })
})
