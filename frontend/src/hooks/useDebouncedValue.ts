/**
 * useDebouncedValue — 값 변화를 delay(ms) 후에 반영하는 디바운스 훅.
 *
 * 검색 입력처럼 타이핑마다 즉시 쿼리를 재시작하지 않고, 입력이 멎은 뒤
 * delay 만큼 지나면 한 번만 갱신하기 위해 쓴다. (전략 목록 검색 300ms)
 *
 * 룰 5: LLM provider import 0건
 */
import { useEffect, useState } from 'react'

export function useDebouncedValue<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState<T>(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])

  return debounced
}
