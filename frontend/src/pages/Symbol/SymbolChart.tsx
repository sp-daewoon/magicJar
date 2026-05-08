/**
 * SymbolChart — IntervalToggle + LightweightChart wrapper + 페이지네이션
 *
 * spec §6-2 §4 차트 series 데이터 흐름 LOCK:
 * - 인터벌 변경 시 useSymbolCandles re-fetch (queryKey에 interval 포함)
 * - 좌측 스크롤 끝 → fetchNextPage (before cursor 페이지네이션)
 * - candles 0건 → "캔들 데이터 수집 중..." skeleton (spec §10-1 graceful)
 * - hasMore=true → [더 보기] 버튼 표시
 *
 * ADR 0019 §2-3 cold-start: 0건이면 "데이터 수집 중 — 잠시 후 새로고침"
 * ADR 0029 §2-3: 1m interval STOMP candle.minute 실시간 갱신
 */
import { useMemo, useState, useCallback } from 'react'
import { useSymbolCandles } from '../../api/hooks/useSymbolCandles'
import type { CandleInterval, CandleDto, CandleMinutePayload } from '../../api/symbol'
import { IntervalToggle } from './IntervalToggle'
import { LightweightChart } from './LightweightChart'

interface SymbolChartProps {
  code: string
  realtimeCandle?: CandleMinutePayload | null
}

export function SymbolChart({ code, realtimeCandle }: SymbolChartProps) {
  const [interval, setInterval] = useState<CandleInterval>('1d')
  const {
    data,
    isLoading,
    isFetchingNextPage,
    fetchNextPage,
    hasNextPage,
    isError,
  } = useSymbolCandles(code, interval)

  // 모든 페이지 candle 합산 (ASC 정렬 유지)
  const allCandles = useMemo<CandleDto[]>(() => {
    if (!data) return []
    return data.pages.flatMap((page) => page.candles)
  }, [data])

  // STOMP 1m 실시간 캔들을 CandleDto로 변환
  const realtimeCandleDto = useMemo<CandleDto | null>(() => {
    if (!realtimeCandle || interval !== '1m') return null
    return {
      ts: realtimeCandle.ts,
      open: realtimeCandle.open,
      high: realtimeCandle.high,
      low: realtimeCandle.low,
      close: realtimeCandle.close,
      volume: realtimeCandle.volume,
    }
  }, [realtimeCandle, interval])

  const handleIntervalChange = useCallback((newInterval: CandleInterval) => {
    setInterval(newInterval)
  }, [])

  const handleScrollLeft = useCallback(() => {
    if (hasNextPage && !isFetchingNextPage) {
      void fetchNextPage()
    }
  }, [hasNextPage, isFetchingNextPage, fetchNextPage])

  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      {/* 헤더: 인터벌 토글 + 더 보기 버튼 */}
      <div className="flex items-center justify-between gap-3 mb-4">
        <IntervalToggle value={interval} onChange={handleIntervalChange} />
        {hasNextPage && (
          <button
            type="button"
            onClick={() => void fetchNextPage()}
            disabled={isFetchingNextPage}
            className="rounded-md bg-gray-800 px-2.5 py-1 text-xs text-gray-400 hover:bg-gray-700 hover:text-gray-200 disabled:cursor-not-allowed disabled:opacity-50 transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400"
            aria-label="이전 캔들 데이터 더 불러오기"
          >
            {isFetchingNextPage ? '로딩 중...' : '더 보기'}
          </button>
        )}
      </div>

      {/* 차트 본체 */}
      {isLoading ? (
        <div className="flex h-96 items-center justify-center">
          <div className="flex flex-col items-center gap-3">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-700 border-t-blue-500" />
            <p className="text-sm text-gray-500">차트 로딩 중...</p>
          </div>
        </div>
      ) : isError ? (
        <div className="flex h-96 items-center justify-center">
          <p className="text-sm text-red-400">차트 데이터를 불러올 수 없습니다.</p>
        </div>
      ) : allCandles.length === 0 ? (
        /* ADR 0019 §2-3 cold-start 미완 graceful */
        <div className="flex h-96 items-center justify-center">
          <div className="flex flex-col items-center gap-3 text-center">
            <div className="h-8 w-8 animate-pulse rounded-full bg-yellow-900/60" />
            <p className="text-sm text-yellow-400">캔들 데이터 수집 중</p>
            <p className="text-xs text-gray-500">
              종목 추가 후 처음 데이터를 수집하고 있습니다.
              <br />
              잠시 후 새로고침해 주세요.
            </p>
          </div>
        </div>
      ) : (
        <LightweightChart
          candles={allCandles}
          interval={interval}
          realtimeCandle={realtimeCandleDto}
          onScrollLeft={handleScrollLeft}
        />
      )}

      {/* 하단: 더 불러오기 로딩 인디케이터 */}
      {isFetchingNextPage && (
        <div className="mt-2 text-center">
          <span className="text-xs text-gray-500">이전 데이터 불러오는 중...</span>
        </div>
      )}
    </div>
  )
}
