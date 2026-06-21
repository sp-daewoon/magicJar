/**
 * useMediaQuery — CSS media query 매칭 여부를 JS로 구독 (빌더 고도화 D)
 *
 * Tailwind 의 hidden/lg:flex 클래스만으로는 두 레이아웃이 모두 DOM에 마운트되어
 * 무거운 컴포넌트가 중복 렌더된다. 빌더는 데스크톱/모바일 레이아웃이 BlockPalette·Canvas를
 * 각각 마운트하므로, 실제로 보이는 한쪽만 마운트하도록 JS 미디어쿼리로 분기한다.
 *
 * SSR·jsdom 등 matchMedia 부재 환경에서는 defaultValue 를 반환한다(테스트는 데스크톱 기준).
 */
import { useEffect, useState } from 'react'

export function useMediaQuery(query: string, defaultValue = false): boolean {
  const [matches, setMatches] = useState<boolean>(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return defaultValue
    return window.matchMedia(query).matches
  })

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    const mql = window.matchMedia(query)
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches)
    setMatches(mql.matches)
    mql.addEventListener('change', handler)
    return () => mql.removeEventListener('change', handler)
  }, [query])

  return matches
}
