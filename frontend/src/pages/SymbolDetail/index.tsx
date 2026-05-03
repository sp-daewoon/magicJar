/**
 * SymbolDetail 페이지 — /symbol/:code
 *
 * 구성:
 *  1. SymbolHeader — 종목명 + WebSocket 실시간 시세
 *  2. 좌측 메타 패널 + 우측 차트 2열 레이아웃 (T20-T7)
 *     - 메타: 시가총액·재무·밸류에이션·주주·배당
 *     - 차트: 봉 단위 선택 + TradingChart (LWC)
 *  3. AI 뉴스 감성 분석
 *  4. JournalTable — 해당 종목 매매일지 리스트
 */
import { useState, useEffect } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import TradingChart from '@/components/chart/TradingChart'
import RealtimeTickChart from '@/components/chart/RealtimeTickChart'
import IntervalSelector from '@/components/chart/IntervalSelector'
import IndicatorPanel from '@/components/chart/IndicatorPanel'
import InstrumentDetailPanel from '@/components/symbol/InstrumentDetailPanel'
import SymbolHeader from './SymbolHeader'
import JournalTable from './JournalTable'
import NewsAnalysisCard from './NewsAnalysisCard'
import { instrumentsApi } from '@/api/instruments'
import { journalApiV2 } from '@/api/journal'
import { useSymbolQuote } from '@/hooks/useSymbolQuote'
import { useWsConnectionState } from '@/hooks/useWsConnectionState'
import { useCandles } from '@/hooks/useCandles'
import { useInstrumentDetail } from '@/hooks/useInstrumentDetail'
import { useSearchHistory } from '@/hooks/useSearchHistory'
import { useUiStore } from '@/stores/ui'
import type { ClientInterval } from '@/lib/candleAggregator'

export default function SymbolDetailPage() {
  const { code } = useParams<{ code: string }>()
  const symbolCode = (code ?? '').toUpperCase()
  const [interval, setInterval] = useState<ClientInterval>('DAY')
  // 화면에 보일 봉 수 (사용자가 input으로 변경 가능). 기본 100봉.
  const [viewportSize, setViewportSize] = useState<number>(100)
  const [activeIndicators, setActiveIndicators] = useState<Set<string>>(
    () => new Set(['SMA-5', 'SMA-20']), // 기본 활성: 단/중기 이평
  )

  // 검색 기록 — 직접 URL 접근 시에도 기록 추가
  const { addToHistory } = useSearchHistory()

  // SettingsModal 열기 (전역 ui store)
  const openSettings = useUiStore((s) => s.openSettings)

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

  // 직접 URL 접근 시 검색 기록에 추가
  useEffect(() => {
    if (instrument) {
      addToHistory({
        code: instrument.code,
        name: instrument.name,
        market: instrument.market,
      })
    }
  }, [instrument, addToHistory])

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

  // 종목 상세 메타 (T20-T7)
  const {
    data: instrumentDetail,
    isLoading: detailLoading,
    isError: detailError,
  } = useInstrumentDetail(symbolCode)

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

      {/* 메인 레이아웃: 좌측 메타 패널 + 우측 차트 (T20-T7) */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[280px_1fr]">
        {/* 좌측: 종목 상세 메타 */}
        <div className="order-2 lg:order-1">
          <InstrumentDetailPanel
            detail={instrumentDetail}
            isLoading={detailLoading}
            isError={detailError}
            onOpenSettings={openSettings}
          />
        </div>

        {/* 우측: 메인 차트 */}
        <div className="order-1 lg:order-2">
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
                    <span className="text-xs text-destructive">데이터 오류 — 백엔드 응답 확인 필요</span>
                  )}
                  {!candlesLoading && !candlesError && (
                    <>
                      {candles.length === 0 ? (
                        <span className="text-xs font-normal text-muted-foreground">데이터 없음</span>
                      ) : (
                        <span className="flex items-center gap-1 text-xs font-normal text-muted-foreground">
                          뷰
                          <input
                            type="number"
                            min={10}
                            max={candles.length}
                            value={viewportSize}
                            onChange={(e) => {
                              const n = Math.max(10, Math.min(candles.length, Number(e.target.value) || 100))
                              setViewportSize(n)
                            }}
                            className="w-16 rounded border border-input bg-background px-1 py-0.5 text-xs tabular-nums"
                            aria-label="화면에 보일 봉 수"
                          />
                          봉 / 로드: <span className="tabular-nums">{candles.length}</span>개
                          {candlesFetchingMore && <span className="text-primary">⏳</span>}
                          {candlesHasMore && !candlesFetchingMore && <span title="좌측 스크롤 시 추가 fetch">↺</span>}
                        </span>
                      )}
                    </>
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
              <span>분봉 데이터 없음 — 장 시작 후 누적 예정</span>
              <span className="text-xs">
                일/주/월/년은 일봉 DB에서 집계, 분봉은 백엔드 backfill 또는 실시간 누적 필요.
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
                viewportSize={viewportSize}
              />
            </div>
          )}
        </CardContent>
      </Card>
        </div>{/* 우측 chart div 닫기 */}
      </div>{/* grid 레이아웃 닫기 */}

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
