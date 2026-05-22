import type { BusinessDay, UTCTimestamp } from 'lightweight-charts'

type ChartTime = UTCTimestamp | BusinessDay | string

export function toBusinessDay(date: string): BusinessDay {
  const [year, month, day] = date.split('-').map(Number)
  return { year, month, day }
}

export function formatPerformanceChartDate(time: ChartTime): string {
  if (typeof time === 'string') return time
  if (typeof time === 'number') {
    return new Intl.DateTimeFormat('sv-SE', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      timeZone: 'Asia/Seoul',
    }).format(new Date(time * 1000))
  }

  return `${time.year}-${String(time.month).padStart(2, '0')}-${String(time.day).padStart(2, '0')}`
}
