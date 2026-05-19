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
 * ADR 0062 채택 (2026-05-11): 5m·15m·30m·90m·1h 합산 5종 활성화
 *   - AggregateBackfillRunner 작동 전까지 빈 배열 → 합산별 전용 안내 표시
 *   - feedback_no_mock_data.md 정합 — mock 합성 절대 금지
 *
 * T-IND PR-33 R3 확장 (W2):
 * - 지표 패널 버튼 (우측 상단)
 * - IndicatorPanel 슬라이드인 패널
 * - IndicatorOverlay — 선택된 지표 LWC overlay
 * - 지표별 오류 토스트 (개별 위젯 오류, 페이지 전체 중단 X)
 *
 * T-PAT PR-40 R3 확장 (W2):
 * - 패턴 토글 버튼 (차트 헤더에 배치)
 * - PatternToggleMenu — 57 catalog picker 슬라이드인 패널
 * - PatternMarker — LWC 차트에 BULL/BEAR 마커 overlay
 * - 패턴 0건 시 "패턴 데이터 없음" 명시 (feedback_no_mock_data.md 정합)
 */
import { useMemo, useState, useCallback } from 'react'
import type { IChartApi, ISeriesApi } from 'lightweight-charts'
import { useSymbolCandles } from '../../api/hooks/useSymbolCandles'
import { useCandleRefresh } from '../../api/hooks/useCandleRefresh'
import type { CandleInterval, CandleDto, CandleMinutePayload } from '../../api/symbol'
import type { LiveTick } from './LightweightChart'
import { usePatterns } from '../../api/hooks/usePatterns'
import { useToast } from '../../components/ui/ToastProvider'
import { PatternMarker } from '../../components/chart/PatternMarker'
import { PatternToggleMenu, usePatternSelection } from '../../components/chart/PatternToggleMenu'
import { IntervalToggle } from './IntervalToggle'
import { LightweightChart } from './LightweightChart'
import { IndicatorPanel } from './IndicatorPanel'
import { IndicatorOverlay } from './IndicatorOverlay'

/** ADR 0062: 합산(precomputed) 인터벌 여부 — 빈 응답 시 전용 안내 메시지 구분 */
const AGGREGATED_INTERVALS: ReadonlySet<CandleInterval> = new Set<CandleInterval>([
  '5m',
  '15m',
  '30m',
  '90m',
  '1h',
])

interface SymbolChartProps {
  code: string
  realtimeCandle?: CandleMinutePayload | null
  /**
   * STOMP tick — 마지막 bar close·high·low 실시간 update
   * SymbolDetailPage에서 /topic/market.tick.{code} 수신 후 전달
   */
  liveTick?: LiveTick | null
}

export function SymbolChart({ code, realtimeCandle, liveTick }: SymbolChartProps) {
  const [interval, setInterval] = useState<CandleInterval>('1d')
  const {
    data,
    isLoading,
    isFetchingNextPage,
    fetchNextPage,
    hasNextPage,
    isError,
    latestBoundaryCandle,
  } = useSymbolCandles(code, interval)

  // 토스트
  const { showToast } = useToast()

  // 캔들 갱신 mutation
  const { refresh: refreshCandles, isPending: isRefreshing } = useCandleRefresh({
    code,
    // 기본 일/주/월/년봉 갱신 (분봉은 별도 STOMP로 실시간 처리)
    intervals: ['1d', '1wk', '1mo', '1y'],
    onSuccess: (added) => {
      showToast(
        added > 0
          ? `최신 캔들 ${added.toLocaleString('ko-KR')}건 추가됨`
          : '최신 캔들로 갱신 완료',
        'success',
      )
    },
    onError: (msg) => {
      showToast(`캔들 갱신 실패: ${msg}`, 'error')
    },
  })

  // 지표 관련 상태
  const [isPanelOpen, setIsPanelOpen] = useState(false)
  const [selectedIndicatorIds, setSelectedIndicatorIds] = useState<Set<string>>(new Set())
  const [indicatorErrors, setIndicatorErrors] = useState<Map<string, string>>(new Map())
  // chart instance를 useState로 lift (P0 fix 2026-05-19 chart-curator):
  //   useRef는 mutation 시 re-render trigger 안 함 → IndicatorOverlay·PatternMarker가
  //   chart 교체(StrictMode 이중 mount 또는 SymbolDetailPage 재진입)를 감지 못해
  //   disposed instance에 removeSeries() 호출 → LWC v4 "Object is disposed" throw.
  //   useState로 lift하면 setChartApi(null) → re-render trigger → child useEffect cleanup이
  //   chart=null로 진입해 series remove 시도 skip → race 원천 차단.
  //   chart-curator skill §함정 가드 (LWC dispose lifecycle 검증) 정합.
  const [chartApi, setChartApi] = useState<IChartApi | null>(null)

  // 패턴 관련 상태 (T-PAT PR-40)
  const [isPatternPanelOpen, setIsPatternPanelOpen] = useState(false)
  const { selectedIds: selectedPatternIds, onSelectionChange: handlePatternSelectionChange } =
    usePatternSelection()
  // candleSeries도 동일 사유로 useState lift (chartApi와 동일 dispose race risk).
  const [candleSeries, setCandleSeries] = useState<ISeriesApi<'Candlestick'> | null>(null)

  // 패턴 데이터 fetch
  const {
    data: patternData,
    isError: isPatternError,
    errorMessage: patternErrorMessage,
  } = usePatterns({
    code,
    interval,
    patternIds: selectedPatternIds,
    enabled: Boolean(code) && selectedPatternIds.size > 0,
  })

  const handleCandleSeriesReady = useCallback((series: ISeriesApi<'Candlestick'>) => {
    setCandleSeries(series)
  }, [])

  const handleCandleSeriesDestroy = useCallback(() => {
    setCandleSeries(null)
  }, [])

  // 모든 페이지 candle 합산 (ASC 정렬 유지).
  // useInfiniteQuery pages 순서: [0]=최신 200개, [1]=그 이전 200개, ...
  // 각 page.candles는 ASC지만 page 경계에서 ts 역행 → reverse 후 flatMap.
  // (dogfooding 결함 — page 합산 후 LWC v4 setData strict ASC 위반 → 차트 미렌더)
  const allCandles = useMemo<CandleDto[]>(() => {
    if (!data) return []
    return [...data.pages].reverse().flatMap((page) => page.candles)
  }, [data])

  // STOMP 실시간 캔들을 CandleDto로 변환
  // 우선순위: boundary closed candle(합산 5종) > 1m minute candle
  const realtimeCandleDto = useMemo<CandleDto | null>(() => {
    // ADR 0064 §2-C: 합산 5종 boundary closed candle
    if (latestBoundaryCandle) {
      return {
        ts: latestBoundaryCandle.ts,
        open: latestBoundaryCandle.open,
        high: latestBoundaryCandle.high,
        low: latestBoundaryCandle.low,
        close: latestBoundaryCandle.close,
        volume: latestBoundaryCandle.volume,
      } satisfies CandleDto
    }
    // ADR 0029 §2-3: 1m 분봉 실시간 candle (기존 동작 유지)
    if (!realtimeCandle || interval !== '1m') return null
    return {
      ts: realtimeCandle.ts,
      open: realtimeCandle.open,
      high: realtimeCandle.high,
      low: realtimeCandle.low,
      close: realtimeCandle.close,
      volume: realtimeCandle.volume,
    } satisfies CandleDto
  }, [latestBoundaryCandle, realtimeCandle, interval])

  const handleIntervalChange = useCallback((newInterval: CandleInterval) => {
    setInterval(newInterval)
    // 인터벌 변경 시 선택된 지표는 유지 (staleTime 경과 시 re-fetch)
  }, [])

  const handleScrollLeft = useCallback(() => {
    if (hasNextPage && !isFetchingNextPage) {
      void fetchNextPage()
    }
  }, [hasNextPage, isFetchingNextPage, fetchNextPage])

  const handleIndicatorToggle = useCallback((id: string) => {
    setSelectedIndicatorIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) {
        next.delete(id)
        // 오류도 제거
        setIndicatorErrors((errs) => {
          const nextErrs = new Map(errs)
          nextErrs.delete(id)
          return nextErrs
        })
      } else {
        next.add(id)
      }
      return next
    })
  }, [])

  const handleChartReady = useCallback((chart: IChartApi) => {
    setChartApi(chart)
  }, [])

  const handleChartDestroy = useCallback(() => {
    setChartApi(null)
  }, [])

  const handleOverlayErrors = useCallback((errors: Map<string, string>) => {
    setIndicatorErrors(new Map(errors))
  }, [])

  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      {/* 헤더: 인터벌 토글 + 지표·패턴 버튼 + 더 보기 */}
      <div className="flex items-center justify-between gap-3 mb-4">
        <div className="flex items-center gap-2">
          <IntervalToggle value={interval} onChange={handleIntervalChange} />

          {/* 지표 토글 버튼 */}
          <button
            type="button"
            onClick={() => setIsPanelOpen((prev) => !prev)}
            className={`flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 ${
              isPanelOpen || selectedIndicatorIds.size > 0
                ? 'bg-blue-600 text-white hover:bg-blue-700'
                : 'bg-gray-800 text-gray-400 hover:bg-gray-700 hover:text-gray-200'
            }`}
            aria-expanded={isPanelOpen}
            aria-label="지표 선택 패널 열기"
          >
            <svg className="h-3.5 w-3.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" d="M7 12l3-3 3 3 4-4M8 21l4-4 4 4M3 4h18M4 4h16v12a1 1 0 01-1 1H5a1 1 0 01-1-1V4z" />
            </svg>
            지표
            {selectedIndicatorIds.size > 0 && (
              <span className="rounded-full bg-white/20 px-1.5 py-0.5 text-[10px] leading-none">
                {selectedIndicatorIds.size}
              </span>
            )}
          </button>

          {/* 패턴 토글 버튼 (T-PAT PR-40) */}
          <button
            type="button"
            onClick={() => setIsPatternPanelOpen((prev) => !prev)}
            className={`flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-emerald-400 ${
              isPatternPanelOpen || selectedPatternIds.size > 0
                ? 'bg-emerald-700 text-white hover:bg-emerald-800'
                : 'bg-gray-800 text-gray-400 hover:bg-gray-700 hover:text-gray-200'
            }`}
            aria-expanded={isPatternPanelOpen}
            aria-label="패턴 선택 패널 열기"
          >
            <svg className="h-3.5 w-3.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" d="M9.75 3.104v5.714a2.25 2.25 0 01-.659 1.591L5 14.5M9.75 3.104c-.251.023-.501.05-.75.082m.75-.082a24.301 24.301 0 014.5 0m0 0v5.714a2.25 2.25 0 001.5 2.1M14.25 3.104c.251.023.501.05.75.082M19.5 9.5l-1.5 1.5" />
            </svg>
            패턴
            {selectedPatternIds.size > 0 && (
              <span className="rounded-full bg-white/20 px-1.5 py-0.5 text-[10px] leading-none">
                {selectedPatternIds.size}
              </span>
            )}
          </button>
        </div>

        <div className="flex items-center gap-2">
          {/* 캔들 갱신 버튼 — KIS에서 최신 일/주/월/년봉 재수집 */}
          <button
            type="button"
            onClick={() => refreshCandles()}
            disabled={isRefreshing || isLoading}
            className="flex items-center gap-1.5 rounded-md bg-gray-800 px-2.5 py-1 text-xs text-gray-400 hover:bg-gray-700 hover:text-gray-200 disabled:cursor-not-allowed disabled:opacity-50 transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400"
            aria-label="최신 캔들 데이터 갱신"
            title="KIS에서 최신 일/주/월/년봉을 재수집합니다"
          >
            {isRefreshing ? (
              <>
                <span
                  className="h-3 w-3 animate-spin rounded-full border-2 border-gray-600 border-t-blue-400 shrink-0"
                  aria-hidden="true"
                />
                갱신 중...
              </>
            ) : (
              <>
                <svg
                  className="h-3.5 w-3.5 shrink-0"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={2}
                  aria-hidden="true"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
                  />
                </svg>
                갱신
              </>
            )}
          </button>

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
      </div>

      {/* 패턴 오류 알림 (T-PAT PR-40 — 위젯 수준 오류, 페이지 전체 중단 X) */}
      {isPatternError && patternErrorMessage && (
        <div
          className="mb-3 flex items-center justify-between rounded-md bg-yellow-900/20 border border-yellow-800/40 px-3 py-1.5 text-xs text-yellow-400"
          role="alert"
          aria-live="polite"
        >
          <span>패턴: {patternErrorMessage}</span>
          <button
            type="button"
            onClick={() => setIsPatternPanelOpen(false)}
            className="ml-3 text-yellow-500 hover:text-yellow-300 transition-colors"
            aria-label="패턴 오류 닫기"
          >
            닫기
          </button>
        </div>
      )}

      {/* 패턴 데이터 없음 안내 (feedback_no_mock_data.md 정합 — 0건 시 명시) */}
      {selectedPatternIds.size > 0 && !isPatternError && patternData && patternData.matches.length === 0 && (
        <div
          className="mb-3 rounded-md bg-gray-800/60 border border-gray-700/40 px-3 py-1.5 text-xs text-gray-500"
          role="status"
          aria-live="polite"
        >
          선택한 패턴이 해당 기간에서 발견되지 않았습니다.
        </div>
      )}

      {/* 지표 오류 알림 (위젯 수준 오류 — 페이지 전체 중단 X) */}
      {indicatorErrors.size > 0 && (
        <div className="mb-3 space-y-1.5" role="alert" aria-live="polite">
          {[...indicatorErrors.entries()].map(([id, msg]) => (
            <div
              key={id}
              className="flex items-center justify-between rounded-md bg-red-900/20 border border-red-800/40 px-3 py-1.5 text-xs text-red-400"
            >
              <span>
                <span className="font-semibold">{id.toUpperCase()}</span>: {msg}
              </span>
              <button
                type="button"
                onClick={() => handleIndicatorToggle(id)}
                className="ml-3 text-red-500 hover:text-red-300 transition-colors"
                aria-label={`${id} 지표 해제`}
              >
                해제
              </button>
            </div>
          ))}
        </div>
      )}

      {/* 3열 레이아웃: 차트(좌) + 지표 패널(중) + 패턴 패널(우) */}
      <div className={`flex gap-4 ${isPanelOpen || isPatternPanelOpen ? 'items-start' : ''}`}>
        {/* 차트 본체 */}
        <div className="flex-1 min-w-0">
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
          ) : (
            /* ADR 0064 §2-C: 합산 인터벌은 빈 배열이어도 LWC 차트를 렌더링 — boundary closed STOMP
               수신 시 series.update()로 실시간 갱신. 빈 배열 안내는 오버레이로 표시.
               ADR 0062: AggregateBackfillRunner 미완 시 빈 배열 → "데이터 준비 중" 오버레이.
               ADR 0019 §2-3 cold-start graceful 유지. */
            <div className="relative">
              {allCandles.length === 0 && (
                <div
                  className="absolute inset-0 z-10 flex items-center justify-center bg-gray-900/80 rounded-lg"
                  role="status"
                  aria-live="polite"
                >
                  <div className="flex flex-col items-center gap-3 text-center max-w-xs">
                    <div className="h-8 w-8 animate-pulse rounded-full bg-yellow-900/60" />
                    {AGGREGATED_INTERVALS.has(interval) ? (
                      <>
                        <p className="text-sm text-yellow-400">{interval} 합산 데이터 준비 중</p>
                        <p className="text-xs text-gray-500">
                          1분봉 tick 데이터를 {interval}봉으로 집계하고 있습니다.
                          <br />
                          boundary 마감 시점에 자동 갱신됩니다.
                        </p>
                      </>
                    ) : (
                      <>
                        <p className="text-sm text-yellow-400">캔들 데이터 수집 중</p>
                        <p className="text-xs text-gray-500">
                          종목 추가 후 처음 데이터를 수집하고 있습니다.
                          <br />
                          잠시 후 새로고침해 주세요.
                        </p>
                      </>
                    )}
                  </div>
                </div>
              )}
              {/* ADR 0064 §2-C: STOMP 실시간 갱신 배지 (합산 인터벌 + 데이터 있음) */}
              {AGGREGATED_INTERVALS.has(interval) && allCandles.length > 0 && (
                <div className="absolute top-2 right-2 z-10 flex items-center gap-1.5 rounded-md bg-emerald-900/60 border border-emerald-700/50 px-2 py-1 text-xs text-emerald-400">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse" aria-hidden="true" />
                  실시간 갱신 중
                </div>
              )}
              <LightweightChart
                candles={allCandles}
                interval={interval}
                realtimeCandle={realtimeCandleDto}
                liveTick={liveTick}
                onScrollLeft={handleScrollLeft}
                onChartReady={handleChartReady}
                onChartDestroy={handleChartDestroy}
                onCandleSeriesReady={handleCandleSeriesReady}
                onCandleSeriesDestroy={handleCandleSeriesDestroy}
              />
            </div>
          )}

          {/* 하단: 더 불러오기 로딩 인디케이터 */}
          {isFetchingNextPage && (
            <div className="mt-2 text-center">
              <span className="text-xs text-gray-500">이전 데이터 불러오는 중...</span>
            </div>
          )}
        </div>

        {/* 지표 패널 (우측 슬라이드인) */}
        {isPanelOpen && (
          <div className="w-64 shrink-0 h-[400px]">
            <IndicatorPanel
              selectedIds={selectedIndicatorIds}
              onToggle={handleIndicatorToggle}
              onClose={() => setIsPanelOpen(false)}
            />
          </div>
        )}

        {/* 패턴 패널 (우측 슬라이드인 — T-PAT PR-40) */}
        {isPatternPanelOpen && (
          <div className="w-56 shrink-0 h-[400px]">
            <PatternToggleMenu
              selectedIds={selectedPatternIds}
              onSelectionChange={handlePatternSelectionChange}
              onClose={() => setIsPatternPanelOpen(false)}
            />
          </div>
        )}
      </div>

      {/* IndicatorOverlay — LWC 차트에 지표 series 추가 (DOM 렌더링 없음) */}
      <IndicatorOverlay
        chart={chartApi}
        code={code}
        interval={interval}
        selectedIds={selectedIndicatorIds}
        onErrorsChange={handleOverlayErrors}
      />

      {/* PatternMarker — LWC 차트에 패턴 마커 overlay (DOM 렌더링 없음 — T-PAT PR-40) */}
      <PatternMarker
        series={candleSeries}
        matches={patternData?.matches ?? []}
      />
    </div>
  )
}
