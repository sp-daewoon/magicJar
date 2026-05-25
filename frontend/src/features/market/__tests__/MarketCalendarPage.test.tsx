import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import apiClient from '../../../lib/axios'
import { MarketCalendarPage } from '../pages/MarketCalendarPage'

function iso(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function buildDays(from: string, to: string) {
  const days = []
  let cursor = new Date(`${from}T00:00:00`)
  const end = new Date(`${to}T00:00:00`)
  while (cursor <= end) {
    const date = iso(cursor)
    const isWeekend = cursor.getDay() === 0 || cursor.getDay() === 6
    days.push({
      date,
      isBusinessDay: date === '2026-05-25' ? false : !isWeekend,
      isHoliday: date === '2026-05-25',
      holidayName: date === '2026-05-25' ? '부처님오신날' : null,
      source: date === '2026-05-25' ? 'STATIC_SEED' : null,
      events:
        date === '2026-05-25'
          ? [
              {
                id: 1,
                date,
                market: 'KRX',
                eventType: 'IPO',
                title: '신규 상장',
                instrumentCode: '123456',
                description: null,
                source: 'MANUAL',
                createdAt: '2026-05-25T00:00:00Z',
                updatedAt: '2026-05-25T00:00:00Z',
              },
            ]
          : [],
    })
    cursor.setDate(cursor.getDate() + 1)
  }
  return days
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MarketCalendarPage />
    </QueryClientProvider>,
  )
}

describe('MarketCalendarPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 4, 25, 9, 0, 0))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('휴장일과 사용자 시장 이벤트를 월간 캘린더에 표시한다', async () => {
    vi.spyOn(apiClient, 'get').mockImplementation(async (_url, config) => {
      const params = config?.params as { from: string; to: string }
      return {
        data: {
          from: params.from,
          to: params.to,
          days: buildDays(params.from, params.to),
        },
      }
    })

    renderPage()

    await waitFor(() => {
      expect(screen.getAllByText('부처님오신날').length).toBeGreaterThan(0)
    })
    expect(screen.getAllByText('신규 상장').length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { name: '시장 캘린더' })).toBeInTheDocument()
  })
})
