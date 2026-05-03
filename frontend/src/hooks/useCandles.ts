/**
 * useCandles — 분봉/일봉 infinite scroll hook (T19-T6)
 *
 * 설계:
 *   - TanStack Query useInfiniteQuery로 페이지(cursor) 기반 추가 fetch
 *   - pageParam = before cursor (첫 fetch는 undefined = 최신부터)
 *   - 좌측 스크롤 끝 감지:
 *       LWC IChartApi.timeScale().subscribeVisibleTimeRangeChange 콜백에서
 *       표시 범위의 from이 전체 데이터 첫 봉 근처에 도달하면 fetchNextPage 트리거
 *   - 중복 호출 차단: isFetchingNextPage 가드
 *   - 종료 조건: 마지막 페이지 응답 길이 < limit (서버 T19-T4 계약)
 *   - 클라이언트 합성(M15/M30/H1): resolveInterval.transform을 flatMap 후 적용
 *
 * 사용법:
 *   const { candles, fetchOlderCandles, hasMore, isLoading } = useCandles(code, interval)
 *
 * TradingChart 연동:
 *   chart.timeScale().subscribeVisibleTimeRangeChange(range => {
 *     if (range && range.from <= candles[scrollThreshold].time) fetchOlderCandles()
 *   })
 */
import { useInfiniteQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { instrumentsApi } from '@/api/instruments'
import { resolveInterval, type ClientInterval } from '@/lib/candleAggregator'
import type { CandleDto } from '@/api/types'

export const CANDLES_PAGE_LIMIT = 200

/** infinite scroll 트리거 임계값: 남은 봉이 TRIGGER_THRESHOLD개 이하이면 추가 fetch */
export const SCROLL_TRIGGER_THRESHOLD = 30

export interface UseCandlesResult {
  /** 합성 완료 후 정렬된 전체 캔들 (오름차순) */
  candles: CandleDto[]
  /** 좌측 스크롤 끝 도달 시 호출 — 이전 페이지 fetch 트리거 */
  fetchOlderCandles: () => void
  /** 더 불러올 데이터 있으면 true */
  hasMore: boolean
  /** 초기 로딩 중 */
  isLoading: boolean
  /** 추가 페이지 fetch 중 */
  isFetchingMore: boolean
  /** 에러 여부 */
  isError: boolean
}

/**
 * 캔들 배열에서 oldest 봉의 cursor 값 추출.
 * - D1: 'YYYY-MM-DD' 문자열
 * - 분봉: epoch second를 string으로 변환
 */
function oldestCursor(candles: CandleDto[]): string | undefined {
  if (candles.length === 0) return undefined
  const t = candles[0].time
  return typeof t === 'number' ? String(t) : String(t)
}

export function useCandles(code: string, interval: ClientInterval): UseCandlesResult {
  const { serverInterval, transform } = resolveInterval(interval)
  const isTick = interval === 'TICK'

  const {
    data,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    isLoading,
    isError,
  } = useInfiniteQuery({
    queryKey: ['candles-infinite', code, serverInterval],
    queryFn: async ({ pageParam }: { pageParam?: string }) => {
      return instrumentsApi.candlesPage(code, serverInterval, pageParam, CANDLES_PAGE_LIMIT)
    },
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage: CandleDto[]) => {
      // 마지막 페이지 길이 < limit → 더 이상 없음
      if (lastPage.length < CANDLES_PAGE_LIMIT) return undefined
      // 다음 cursor = 마지막 페이지의 oldest 봉 cursor
      return oldestCursor(lastPage)
    },
    enabled: !!code && !isTick,
    staleTime: 60_000,
  })

  // 모든 페이지 병합 후 합성 (오름차순 시간순 정렬 보장)
  const candles = useMemo<CandleDto[]>(() => {
    if (!data) return []
    // pages는 최신→과거 순서로 쌓임 → 역순 평탄화 후 오름차순 정렬
    const allRaw = data.pages
      .slice()
      .reverse()
      .flat()
    // 시간순 정렬
    const sorted = [...allRaw].sort((a, b) => {
      const ta = typeof a.time === 'number' ? a.time : a.time
      const tb = typeof b.time === 'number' ? b.time : b.time
      return String(ta).localeCompare(String(tb))
    })
    // 중복 제거 (by time key)
    const dedupMap = new Map<string | number, CandleDto>()
    for (const c of sorted) {
      dedupMap.set(c.time, c)
    }
    const deduped = [...dedupMap.values()]
    // 클라이언트 합성 (M15/M30/H1 등)
    return transform(deduped)
  }, [data, transform])

  const fetchOlderCandles = () => {
    if (!isFetchingNextPage && hasNextPage) {
      void fetchNextPage()
    }
  }

  return {
    candles,
    fetchOlderCandles,
    hasMore: hasNextPage ?? false,
    isLoading,
    isFetchingMore: isFetchingNextPage,
    isError,
  }
}
