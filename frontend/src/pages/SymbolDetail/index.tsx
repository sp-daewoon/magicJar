/**
 * SymbolDetail 페이지 — /symbol/:code
 *
 * 구성:
 *  1. SymbolHeader — 종목명 + WebSocket 실시간 시세
 *  2. 봉 단위 선택 (일/주/월/년/분봉)
 *  3. TradingChart — 단일 LWC chart + 볼륨 overlay + crosshair OHLC tooltip + infinite scroll (T19-T6)
 *  4. JournalTable — 해당 종목 매매일지 리스트
 */
import { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import TradingChart from '@/components/chart/TradingChart'
import RealtimeTickChart from '@/components/chart/RealtimeTickChart'
import IntervalSelector from '@/components/chart/IntervalSelector'
import IndicatorPanel from '@/components/chart/IndicatorPanel'
import SymbolHeader from './SymbolHeader'
import JournalTable from './JournalTable'
import NewsAnalysisCard from './NewsAnalysisCard'
import { instrumentsApi } from '@/api/instruments'
import { journalApiV2 } from '@/api/journal'
import { useSymbolQuote } from '@/hooks/useSymbolQuote'
import { useWsConnectionState } from '@/hooks/useWsConnectionState'
import { useCandles } from '@/hooks/useCandles'
import type { ClientInterval } from '@/lib/candleAggregator'

export default function SymbolDetailPage() {
  const { code } = useParams<{ code: string }>()
  const symbolCode = (code ?? '').toUpperCase()
  const [interval, setInterval] = useState<ClientInterval>('DAY')
  const [activeIndicators, setActiveIndicators] = useState<Set<string>>(
    () => new Set(['SMA-5', 'SMA-20']), // 기본 활성: 단/중기 이평
  )

  // 종목 정보 조회
  const {
    data: instrument,
    isLoading: instrumentLoading,
    isError: instrumentError,
  } = useQuery({
    queryKey: ['instrument', symbolCode],
    queryFn: () => instrumentsApi.get(symbolCode),
    enabled: !!symbolCode,
    retry: 1,
  })

  const isTick = interval === 'TICK'

  // infinite scroll 캔들 hook (T19-T6)
  // - 첫 fetch: 최신 200봉 (before=undefined)
  // - 좌측 스크롤 끝 → fetchOlderCandles() 자동 추가 fetch
  // - M15/M30/H1: 내부적으로 M5 서버 fetch 후 클라 합성 (T19-T5)
  const {
    candles,
    fetchOlderCandles,
    hasMore: candlesHasMore,
    isLoading: candlesLoading,
    isFetchingMore: candlesFetchingMore,
    isError: candlesError,
  } = useCandles(symbolCode, interval)

  // 매매일지 조회
  const {
    data: journalEntries = [],
    isLoading: journalLoading,
    isError: journalError,
  } = useQuery({
    queryKey: ['journal', symbolCode],
    queryFn: () => journalApiV2.list({ code: symbolCode, limit: 20 }),
    enabled: !!symbolCode,
    staleTime: 30_000,
  })

  // WebSocket 실시간 시세
  const quote = useSymbolQuote(symbolCode)
  const wsState = useWsConnectionState()

  if (!symbolCode) {
    return (
      <div className="flex h-64 items-center justify-center text-muted-foreground">
        종목 코드가 없습니다.
      </div>
    )
  }

  if (instrumentError) {
    return (
      <div className="space-y-4">
        <Link to="/" className="text-sm text-primary hover:underline">
          ← 대시보드로
        </Link>
        <div className="flex h-64 items-center justify-center rounded-lg border border-destructive/30 bg-destructive/5 text-destructive">
          종목 정보를 불러오지 못했습니다. 코드를 확인하세요: {symbolCode}
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {/* 뒤로가기 */}
      <Link to="/" className="text-sm text-muted-foreground hover:underline">
        ← 대시보드
      </Link>

      {/* 헤더 — 종목명 + 실시간 시세 */}
      <Card>
        <CardContent className="pt-4">
          <SymbolHeader
            instrument={instrument ?? null}
            quote={quote}
            wsState={wsState}
            isLoading={instrumentLoading}
          />
        </CardContent>
      </Card>

      {/* 메인 차트 — 단일 LWC chart + 볼륨 오버레이 + 봉 단위 선택 + crosshair OHLC tooltip */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="flex flex-col gap-2 text-base sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-center gap-2">
              <span>차트</span>
              {isTick ? (
                <span className="text-xs font-normal text-muted-foreground">
                  STOMP 실시간 틱 스트림
                </span>
              ) : (
                <>
                  {candlesLoading && (
                    <span className="text-xs text-muted-foreground">로딩 중...</span>
                  )}
                  {candlesError && (
                    <span className="text-xs text-destructive">데이터 오류 (mock fallback)</span>
                  )}
                  {!candlesLoading && !candlesError && (
                    <span className="text-xs font-normal text-muted-foreground">
                      {candles.length}개 봉
                      {candles.length === 0 && ' — 해당 봉 단위 시드 부재'}
                      {candlesHasMore && ' (더 있음)'}
                      {import.meta.env.VITE_USE_MOCK === 'true' && ' (mock)'}
                    </span>
                  )}
                </>
              )}
            </div>
            <IntervalSelector value={interval} onChange={setInterval} />
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0 pb-2">
          {isTick ? (
            <RealtimeTickChart code={symbolCode} height={480} className="px-2" />
          ) : candlesLoading ? (
            <div className="flex h-[480px] items-center justify-center">
              <div className="h-[440px] w-full animate-pulse rounded bg-gray-100 mx-4" />
            </div>
          ) : candles.length === 0 ? (
            <div className="flex h-[480px] flex-col items-center justify-center gap-2 text-sm text-muted-foreground">
              <span>해당 봉 단위 데이터가 아직 없습니다.</span>
              <span className="text-xs">
                일/주/월/년은 일봉 시드(V7)를 클라이언트 집계, 분봉은 백엔드 backfill 필요.
              </span>
            </div>
          ) : (
            <div className="space-y-2">
              <div className="px-2">
                <IndicatorPanel active={activeIndicators} onChange={setActiveIndicators} />
              </div>
              <TradingChart
                candles={candles}
                interval={interval}
                activeIndicators={activeIndicators}
                height={480}
                className="px-2"
                onScrollEnd={candlesHasMore ? fetchOlderCandles : undefined}
                isFetchingMore={candlesFetchingMore}
              />
            </div>
          )}
        </CardContent>
      </Card>

      {/* AI 뉴스 감성 분석 */}
      <NewsAnalysisCard code={symbolCode} />

      {/* 매매일지 */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">
            매매일지
            {!journalLoading && !journalError && (
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                {journalEntries.length}건
                {import.meta.env.VITE_USE_MOCK === 'true' && ' (mock)'}
              </span>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent className="px-4 pb-4 pt-2">
          <JournalTable
            entries={journalEntries}
            isLoading={journalLoading}
            isError={journalError}
          />
        </CardContent>
      </Card>
    </div>
  )
}
