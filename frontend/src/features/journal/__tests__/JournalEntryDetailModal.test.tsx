/**
 * JournalEntryDetailModal 단위 테스트 — 2 케이스
 *
 * ADR 0072 §9-4 + spec §3-3 정합:
 *   1. entry display + notes 표시
 *   2. ESC 키 → onClose 호출
 *
 * useJournalEntry, useWriteJournalNote는 vi.mock으로 제어
 */
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { JournalEntryDetailModal } from '../components/JournalEntryDetailModal'

// hook mocks
vi.mock('../hooks/useJournalEntry', () => ({
  useJournalEntry: () => ({
    data: {
      id: 1001,
      eventType: 'ORDER_FILLED',
      instrumentCode: '005930',
      instrumentName: '삼성전자',
      occurredAt: '2026-05-14T14:32:18+09:00',
      summary: '체결: 삼성전자 매수 100주 @ 75,000원',
      realizedPnl: '150000.00',
      payload: { side: 'BUY' },
      notes: [
        {
          id: 501,
          noteText: '이번 매매는 RSI 과매도 진입',
          createdAt: '2026-05-14T14:40:00+09:00',
          updatedAt: '2026-05-14T14:40:00+09:00',
        },
      ],
    },
    isLoading: false,
    isError: false,
  }),
}))

vi.mock('../hooks/useWriteJournalNote', () => ({
  useWriteJournalNote: () => ({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
  }),
}))

function renderModal(onClose = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <JournalEntryDetailModal entryId={1001} userId={12345678} onClose={onClose} />
    </QueryClientProvider>,
  )
}

describe('JournalEntryDetailModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('1. entry 기본 정보 + 메모 표시', () => {
    renderModal()
    // 이벤트 타입 label
    expect(screen.getByText('체결')).toBeInTheDocument()
    // 요약
    expect(screen.getByText('체결: 삼성전자 매수 100주 @ 75,000원')).toBeInTheDocument()
    // 메모
    expect(screen.getByText('이번 매매는 RSI 과매도 진입')).toBeInTheDocument()
    // 손익
    expect(screen.getByText('+150,000원')).toBeInTheDocument()
  })

  it('2. ESC 키 → onClose 호출', () => {
    const onClose = vi.fn()
    renderModal(onClose)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
