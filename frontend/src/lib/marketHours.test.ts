import { describe, it, expect } from 'vitest'
import { getMarketStatus, isMarketOpen, marketStatusLabel } from './marketHours'

// KST 시각으로 Date 생성 — toISOString이 UTC 기준이니 KST 09:00 = UTC 00:00.
function kstAt(dateStr: string, hh: number, mm: number): Date {
  return new Date(`${dateStr}T${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}:00+09:00`)
}

describe('getMarketStatus', () => {
  it('평일 09:30 KST → OPEN', () => {
    // 2026-05-04(월) 09:30 KST
    const s = getMarketStatus(kstAt('2026-05-04', 9, 30))
    expect(s.state).toBe('OPEN')
  })

  it('평일 15:30 KST 정각 → AFTER_HOURS', () => {
    const s = getMarketStatus(kstAt('2026-05-04', 15, 30))
    expect(s.state).toBe('AFTER_HOURS')
  })

  it('평일 08:30 KST → PRE_OPEN', () => {
    const s = getMarketStatus(kstAt('2026-05-04', 8, 30))
    expect(s.state).toBe('PRE_OPEN')
  })

  it('일요일 14:00 → WEEKEND', () => {
    // 2026-05-03 일
    const s = getMarketStatus(kstAt('2026-05-03', 14, 0))
    expect(s.state).toBe('WEEKEND')
  })

  it('근로자의 날(2026-05-01 금) 14:00 → HOLIDAY', () => {
    const s = getMarketStatus(kstAt('2026-05-01', 14, 0))
    expect(s.state).toBe('HOLIDAY')
    if (s.state === 'HOLIDAY') expect(s.reason).toContain('근로자')
  })

  it('어린이날(2026-05-05 화) 10:00 → HOLIDAY', () => {
    const s = getMarketStatus(kstAt('2026-05-05', 10, 0))
    expect(s.state).toBe('HOLIDAY')
  })

  it('금요일 16:00 → AFTER_HOURS, 다음 개장은 다음 영업일', () => {
    // 2026-05-08 금, 5/9~10 주말 → 5/11 월
    const s = getMarketStatus(kstAt('2026-05-08', 16, 0))
    expect(s.state).toBe('AFTER_HOURS')
    if (s.state === 'AFTER_HOURS') {
      const fmt = new Intl.DateTimeFormat('en-US', {
        timeZone: 'Asia/Seoul',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
      })
      expect(fmt.format(s.nextOpenAt)).toContain('05/11')
    }
  })
})

describe('isMarketOpen', () => {
  it('OPEN 상태일 때 true', () => {
    expect(isMarketOpen(kstAt('2026-05-04', 10, 0))).toBe(true)
  })
  it('휴장일 false', () => {
    expect(isMarketOpen(kstAt('2026-05-05', 10, 0))).toBe(false)
  })
})

describe('marketStatusLabel', () => {
  it('각 상태별 라벨 길이 ≤ 10자', () => {
    const states = [
      kstAt('2026-05-04', 10, 0),  // OPEN
      kstAt('2026-05-04', 8, 30),  // PRE_OPEN
      kstAt('2026-05-04', 16, 0),  // AFTER_HOURS
      kstAt('2026-05-03', 12, 0),  // WEEKEND
    ]
    for (const t of states) {
      const label = marketStatusLabel(getMarketStatus(t))
      expect(label.length).toBeLessThanOrEqual(10)
    }
  })
})
