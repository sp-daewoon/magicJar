/**
 * SymbolDetail 페이지 — /symbol/:code
 *
 * 구성:
 *  1. SymbolHeader — 종목명 + WebSocket 실시간 시세
 *  2. 봉 단위 선택 (일/주/월/년/분봉)
 *  3. TradingChart — 단일 LWC chart + 볼륨 overlay + crosshair OHLC tooltip (ADR 0022)
 *  4. JournalTable — 해당 종목 매매일지 리스트
 */
import { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import TradingChart from '@/components/chart/TradingChart'
import IntervalSelector from '@/components/chart/IntervalSelector'
import IndicatorPanel from '@/components/chart/IndicatorPanel'
import SymbolHeader from './SymbolHeader'
import JournalTable from './JournalTable'
import { instrumentsApi } from '@/api/instruments'
import { journalApiV2 } from '@/api/journal'
import { useSymbolQuote } from '@/hooks/useSymbolQuote'
import { useWsConnectionState } from '@/hooks/useWsConnectionState'
import { resolveInterval, type ClientInterval } from '@/lib/candleAggregator'

export default function SymbolDetailPage() {
  const { code } = useParams<{ code: string }>()
  const symbolCode = (code ?? '').toUpperCase()
  const [interval, setInterval] = useState<ClientInterval>('DAY')
  const [activeIndicators, setActiveIndicators] = useState<Set<string>>(
    () => new Set(['SMA-5', 'SMA-20']), // 기본 활성: 단/중기 이평
  )
  const { serverInterval, transform } = resolveInterval(interval)

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

  // 캔들 데이터 조회 — 백엔드는 D1/M1/M5/M15/M30/H1만 — W/MO/Y는 클라이언트 집계
  const {
    data: rawCandles = [],
    isLoading: candlesLoading,
    isError: candlesError,
  } = useQuery({
    queryKey: ['candles', symbolCode, serverInterval],
    queryFn: () => instrumentsApi.candles(symbolCode, serverInterval),
    enabled: !!symbolCode,
    staleTime: 60_000,
  })

  const candles = transform(rawCandles)

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
                  {import.meta.env.VITE_USE_MOCK === 'true' && ' (mock)'}
                </span>
              )}
            </div>
            <IntervalSelector value={interval} onChange={setInterval} />
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0 pb-2">
          {candlesLoading ? (
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
              />
            </div>
          )}
        </CardContent>
      </Card>

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
