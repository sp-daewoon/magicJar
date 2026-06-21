/**
 * useDebouncedValue 단위 테스트 — 검색 입력 debounce (list-ux-uplift)
 */
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDebouncedValue } from '../useDebouncedValue'

describe('useDebouncedValue', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('초기값을 즉시 반환한다', () => {
    const { result } = renderHook(() => useDebouncedValue('a', 300))
    expect(result.current).toBe('a')
  })

  it('delay 경과 전에는 갱신하지 않고, 경과 후 마지막 값으로 한 번만 갱신한다', () => {
    const { result, rerender } = renderHook(({ v }) => useDebouncedValue(v, 300), {
      initialProps: { v: 'a' },
    })

    rerender({ v: 'ab' })
    rerender({ v: 'abc' })
    // 아직 debounce 미경과 → 초기값 유지
    expect(result.current).toBe('a')

    act(() => {
      vi.advanceTimersByTime(299)
    })
    expect(result.current).toBe('a')

    act(() => {
      vi.advanceTimersByTime(1)
    })
    // 마지막 값으로 한 번만 갱신
    expect(result.current).toBe('abc')
  })

  it('연속 입력 중 타이머가 리셋되어 마지막 입력 기준으로만 발화한다', () => {
    const { result, rerender } = renderHook(({ v }) => useDebouncedValue(v, 300), {
      initialProps: { v: '' },
    })

    rerender({ v: '삼' })
    act(() => vi.advanceTimersByTime(200))
    rerender({ v: '삼성' })
    act(() => vi.advanceTimersByTime(200))
    // 직전 입력에서 200ms만 지났으므로 아직 미발화
    expect(result.current).toBe('')

    act(() => vi.advanceTimersByTime(100))
    expect(result.current).toBe('삼성')
  })
})
