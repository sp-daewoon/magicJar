/**
 * SymbolDetail 페이지 — /symbol/:code
 *
 * 구성:
 *  1. SymbolHeader — 종목명 + WebSocket 실시간 시세
 *  2. 메인 차트 — LWC CandlestickSeries + 볼륨 히스토그램 (ADR 0022)
 *  3. JournalTable — 해당 종목 매매일지 리스트
 */
import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import CandleChart from '@/components/chart/CandleChart'
import VolumeChart from '@/components/chart/VolumeChart'
import SymbolHeader from './SymbolHeader'
import JournalTable from './JournalTable'
import { instrumentsApi } from '@/api/instruments'
import { journalApiV2 } from '@/api/journal'
import { useSymbolQuote } from '@/hooks/useSymbolQuote'
import { useWsConnectionState } from '@/hooks/useWsConnectionState'

export default function SymbolDetailPage() {
  const { code } = useParams<{ code: string }>()
  const symbolCode = (code ?? '').toUpperCase()

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

  // 캔들 데이터 조회
  const {
    data: candles = [],
    isLoading: candlesLoading,
    isError: candlesError,
  } = useQuery({
    queryKey: ['candles', symbolCode, 'DAY'],
    queryFn: () => instrumentsApi.candles(symbolCode, 'DAY'),
    enabled: !!symbolCode,
    staleTime: 60_000,
  })

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

      {/* 메인 차트 — 캔들 + 볼륨 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between text-base">
            <span>일봉 차트</span>
            {candlesLoading && (
              <span className="text-xs text-muted-foreground">로딩 중...</span>
            )}
            {candlesError && (
              <span className="text-xs text-destructive">차트 데이터 오류 (mock 사용 중)</span>
            )}
            {!candlesLoading && (
              <span className="text-xs text-muted-foreground">
                {candles.length}개 봉
                {import.meta.env.VITE_USE_MOCK === 'true' && ' (mock)'}
              </span>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0 pb-2">
          {candlesLoading ? (
            <div className="flex h-72 items-center justify-center">
              <div className="h-64 w-full animate-pulse rounded bg-gray-100 mx-4" />
            </div>
          ) : (
            <>
              <CandleChart
                candles={candles.map((c) => ({
                  // LWC Time은 'YYYY-MM-DD' 문자열 또는 unix seconds 모두 지원
                  // CandleDto.time은 'YYYY-MM-DD' 형식 → as unknown as number 캐스팅으로 전달
                  time: c.time as unknown as number,
                  open: c.open,
                  high: c.high,
                  low: c.low,
                  close: c.close,
                }))}
                height={320}
                className="px-2"
              />
              <VolumeChart
                candles={candles}
                height={90}
                className="px-2"
              />
            </>
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
