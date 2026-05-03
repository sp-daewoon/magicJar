/**
 * useInstrumentSearch — 종목 검색 자동완성 훅.
 * GET /api/instruments/search?q=...&limit=10
 *
 * - debounce 300ms 적용 (내부 setTimeout 구현, 외부 라이브러리 0)
 * - q.length < 1 이면 query 비활성화 (enabled: false)
 * - API 실패 시 빈 배열 반환 (instrumentsApi.search 내부에서 처리)
 */
import { useState, useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { instrumentsApi } from '@/api/instruments'
import type { SearchResult } from '@/api/types'

export interface UseInstrumentSearchResult {
  results: SearchResult[]
  isLoading: boolean
  isError: boolean
  query: string
  setQuery: (q: string) => void
  debouncedQuery: string
}

export function useInstrumentSearch(): UseInstrumentSearchResult {
  const [query, setQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')

  // debounce 300ms
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedQuery(query)
    }, 300)
    return () => clearTimeout(timer)
  }, [query])

  const { data = [], isLoading, isError } = useQuery<SearchResult[]>({
    queryKey: ['instrument-search', debouncedQuery],
    queryFn: () => instrumentsApi.search(debouncedQuery, 10),
    enabled: debouncedQuery.length >= 1,
    staleTime: 30_000,
    gcTime: 60_000,
  })

  return {
    results: data,
    isLoading,
    isError,
    query,
    setQuery,
    debouncedQuery,
  }
}
