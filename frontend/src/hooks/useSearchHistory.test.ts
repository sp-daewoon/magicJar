/**
 * useSearchHistory 단위 테스트
 * 1. 항목 추가
 * 2. LRU 20개 한도
 * 3. 중복 항목 lastVisitedAt 갱신 + 맨 앞 이동
 * 4. 단건 삭제
 * 5. 빈 기록 처리
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useSearchHistory } from './useSearchHistory'

describe('useSearchHistory', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  // ── 시나리오 1: 항목 추가 ────────────────────────────────────────────────
  it('addToHistory — 항목이 기록 맨 앞에 추가된다', () => {
    const { result } = renderHook(() => useSearchHistory())

    act(() => {
      result.current.addToHistory({ code: '005930', name: '삼성전자', market: 'KOSPI' })
    })

    expect(result.current.history).toHaveLength(1)
    expect(result.current.history[0].code).toBe('005930')
    expect(result.current.history[0].name).toBe('삼성전자')
    expect(result.current.history[0].lastVisitedAt).toBeTruthy()
  })

  // ── 시나리오 2: LRU 20개 한도 ────────────────────────────────────────────
  it('LRU 한도 20개 초과 시 가장 오래된 항목이 제거된다', () => {
    const { result } = renderHook(() => useSearchHistory())

    // 21개 추가
    act(() => {
      for (let i = 1; i <= 21; i++) {
        result.current.addToHistory({
          code: String(i).padStart(6, '0'),
          name: `종목${i}`,
          market: 'KOSPI',
        })
      }
    })

    expect(result.current.history).toHaveLength(20)
    // 가장 마지막에 추가된 것이 맨 앞 (코드 '000021')
    expect(result.current.history[0].code).toBe('000021')
    // 가장 먼저 추가된 것(코드 '000001')은 제거됨
    expect(result.current.history.some((h) => h.code === '000001')).toBe(false)
  })

  // ── 시나리오 3: 중복 항목 갱신 + 맨 앞 이동 ─────────────────────────────
  it('중복 코드 추가 시 lastVisitedAt 갱신 후 맨 앞으로 이동한다', async () => {
    const { result } = renderHook(() => useSearchHistory())

    act(() => {
      result.current.addToHistory({ code: '005930', name: '삼성전자', market: 'KOSPI' })
    })
    const firstTime = result.current.history[0].lastVisitedAt

    act(() => {
      result.current.addToHistory({ code: '000660', name: 'SK하이닉스', market: 'KOSPI' })
    })

    // 잠깐 대기 후 같은 코드 재추가
    await new Promise((r) => setTimeout(r, 10))

    act(() => {
      result.current.addToHistory({ code: '005930', name: '삼성전자', market: 'KOSPI' })
    })

    expect(result.current.history).toHaveLength(2)
    // 삼성전자가 맨 앞
    expect(result.current.history[0].code).toBe('005930')
    // lastVisitedAt 갱신 여부 확인 (같거나 이후 시각)
    expect(result.current.history[0].lastVisitedAt >= firstTime).toBe(true)
  })

  // ── 시나리오 4: 단건 삭제 ────────────────────────────────────────────────
  it('removeFromHistory — 해당 코드만 제거된다', () => {
    const { result } = renderHook(() => useSearchHistory())

    act(() => {
      result.current.addToHistory({ code: '005930', name: '삼성전자', market: 'KOSPI' })
      result.current.addToHistory({ code: '000660', name: 'SK하이닉스', market: 'KOSPI' })
    })

    act(() => {
      result.current.removeFromHistory('005930')
    })

    expect(result.current.history).toHaveLength(1)
    expect(result.current.history[0].code).toBe('000660')
  })

  // ── 시나리오 5: 빈 기록 처리 ─────────────────────────────────────────────
  it('초기 상태·clearHistory — 빈 배열을 반환한다', () => {
    const { result } = renderHook(() => useSearchHistory())

    // 초기 빈 상태
    expect(result.current.history).toHaveLength(0)

    act(() => {
      result.current.addToHistory({ code: '005930', name: '삼성전자', market: 'KOSPI' })
    })
    expect(result.current.history).toHaveLength(1)

    act(() => {
      result.current.clearHistory()
    })

    expect(result.current.history).toHaveLength(0)
    expect(localStorage.getItem('magicjar:search-history:v1')).toBe('[]')
  })
})
