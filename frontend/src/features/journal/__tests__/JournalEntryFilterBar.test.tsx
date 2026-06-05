import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { JournalEntryFilterBar, type JournalEntryFilterState } from '../components/JournalEntryFilterBar'
import { useInstrumentSearch } from '../../../api/hooks/useInstrumentSearch'

vi.mock('../../../api/hooks/useInstrumentSearch', () => ({
  useInstrumentSearch: vi.fn(),
}))

const defaultFilter: JournalEntryFilterState = {
  from: '2026-06-06',
  to: '2026-05-30',
  instrumentCode: '',
  instrumentInput: '',
}

describe('JournalEntryFilterBar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useInstrumentSearch).mockReturnValue({
      results: [],
      isLoading: false,
      isError: false,
      isServerError: false,
    })
  })

  it('한글 종목명 자동완성 선택 시 표시값과 6자리 코드를 분리해서 전달한다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    vi.mocked(useInstrumentSearch).mockReturnValue({
      results: [
        { code: '005930', name: '삼성전자', market: 'KOSPI', sector: '전기전자', score: 1 },
      ],
      isLoading: false,
      isError: false,
      isServerError: false,
    })

    render(<JournalEntryFilterBar filter={defaultFilter} onChange={onChange} onReset={vi.fn()} />)

    await user.type(screen.getByRole('combobox', { name: '종목 검색' }), '삼성')

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByText('삼성전자').closest('button')!)

    expect(onChange).toHaveBeenLastCalledWith({
      ...defaultFilter,
      instrumentCode: '005930',
      instrumentInput: '삼성전자 (005930)',
    })
  })
})
