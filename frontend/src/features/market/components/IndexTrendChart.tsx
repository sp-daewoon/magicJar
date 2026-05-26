/**
 * IndexTrendChart — market_index_snapshot 기반 지수 캔들 + MA overlay.
 *
 * Trend API의 MA 계산은 일봉 history가 부족하면 dataInsufficient=true일 수 있다.
 * 그래도 장중 snapshot 이 존재하면 캔들 자체는 표시해서 "현재 장이 움직이는지"를 바로 볼 수 있게 한다.
 */
import { useEffect, useMemo, useRef } from 'react'
import {
  createChart,
  type CandlestickData,
  type IChartApi,
  type ISeriesApi,
  type LineData,
  type UTCTimestamp,
} from 'lightweight-charts'
import { toNumberOrNull, type IndexCandleItem, type IndexTrendResponse } from '../../../api/market'

interface Props {
  trend: IndexTrendResponse | undefined
  candles?: IndexCandleItem[]
  isLoading?: boolean
  isError?: boolean
}

/** period → 라인 색상 (5개까지). 헌법 §1 색상 컨벤션 — 사용자 직관 정합. */
const PERIOD_COLORS = ['#60a5fa', '#a78bfa', '#f59e0b', '#ec4899', '#10b981']

interface TrendLineEntry {
  period: number
  value: number
}

function toTimestamp(ts: string): UTCTimestamp | null {
  const ms = Date.parse(ts)
  if (!Number.isFinite(ms)) return null
  return Math.floor(ms / 1000) as UTCTimestamp
}

function toCandleData(candle: IndexCandleItem): CandlestickData | null {
  const time = toTimestamp(candle.ts)
  const open = toNumberOrNull(candle.open)
  const high = toNumberOrNull(candle.high)
  const low = toNumberOrNull(candle.low)
  const close = toNumberOrNull(candle.close)
  if (time === null || open === null || high === null || low === null || close === null) {
    return null
  }
  return { time, open, high, low, close }
}

function normalizeCandles(candles: IndexCandleItem[]): CandlestickData[] {
  const byTime = new Map<number, CandlestickData>()
  candles.forEach((candle) => {
    const data = toCandleData(candle)
    if (!data) return
    byTime.set(Number(data.time), data)
  })
  return [...byTime.entries()]
    .sort(([a], [b]) => a - b)
    .map(([, data]) => data)
}

function fallbackLineRange(): [LineData['time'], LineData['time']] {
  const today = new Date()
  const past = new Date(today)
  past.setDate(today.getDate() - 30)
  return [past.toISOString().slice(0, 10), today.toISOString().slice(0, 10)]
}

function candleLineRange(candleData: CandlestickData[]): [LineData['time'], LineData['time']] {
  if (candleData.length === 0) return fallbackLineRange()
  const first = candleData[0].time as LineData['time']
  const lastCandle = candleData[candleData.length - 1]
  if (candleData.length === 1) {
    return [first, (Number(lastCandle.time) + 60) as UTCTimestamp]
  }
  return [first, lastCandle.time as LineData['time']]
}

export function IndexTrendChart({
  trend,
  candles = [],
  isLoading = false,
  isError = false,
}: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const seriesMapRef = useRef<Map<number, ISeriesApi<'Line'>>>(new Map())
  const isDisposedRef = useRef(false)

  const candleData = useMemo(() => normalizeCandles(candles), [candles])
  const trendLineEntries = useMemo<TrendLineEntry[]>(() => {
    if (!trend || trend.dataInsufficient) return []
    return trend.periodsUsed
      .map((period) => ({
        period,
        value: toNumberOrNull(trend.ma[String(period)]),
      }))
      .filter((entry): entry is TrendLineEntry => entry.value !== null)
  }, [trend])
  const lastClose = useMemo(() => toNumberOrNull(trend?.lastClose ?? null), [trend])
  const shouldDrawLastClose = lastClose !== null && (candleData.length > 0 || !trend?.dataInsufficient)
  const hasDisplayData = candleData.length > 0 || trendLineEntries.length > 0 || shouldDrawLastClose

  useEffect(() => {
    if (isLoading || isError || !hasDisplayData) return
    if (!containerRef.current) return
    isDisposedRef.current = false

    const chart = createChart(containerRef.current, {
      width: containerRef.current.clientWidth,
      height: 260,
      layout: {
        background: { color: 'transparent' },
        textColor: '#9ca3af',
      },
      grid: {
        vertLines: { color: '#1f2937' },
        horzLines: { color: '#1f2937' },
      },
      timeScale: {
        borderColor: '#374151',
        timeVisible: true,
        secondsVisible: false,
      },
      rightPriceScale: {
        borderColor: '#374151',
      },
      crosshair: {
        mode: 1,
      },
      localization: {
        locale: 'ko-KR',
      },
    })

    chartRef.current = chart

    const ro = new ResizeObserver((entries) => {
      if (isDisposedRef.current) return
      const entry = entries[0]
      if (entry && chartRef.current) {
        try {
          chartRef.current.applyOptions({ width: entry.contentRect.width })
        } catch {
          // graceful — disposed
        }
      }
    })
    ro.observe(containerRef.current)

    return () => {
      isDisposedRef.current = true
      candleSeriesRef.current = null
      seriesMapRef.current.clear()
      ro.disconnect()
      try {
        chart.remove()
      } catch {
        // graceful
      }
      chartRef.current = null
    }
  }, [hasDisplayData, isError, isLoading])

  // 데이터 갱신 — 실제 snapshot 캔들 + MA/lastClose 기준선.
  useEffect(() => {
    if (isDisposedRef.current) return
    const chart = chartRef.current
    if (!chart || !hasDisplayData) return

    try {
      if (candleSeriesRef.current) {
        try {
          chart.removeSeries(candleSeriesRef.current)
        } catch {
          // graceful
        }
        candleSeriesRef.current = null
      }
      seriesMapRef.current.forEach((s) => {
        try {
          chart.removeSeries(s)
        } catch {
          // graceful
        }
      })
      seriesMapRef.current.clear()

      if (candleData.length > 0) {
        const candleSeries = chart.addCandlestickSeries({
          upColor: '#26a69a',
          downColor: '#ef5350',
          borderUpColor: '#26a69a',
          borderDownColor: '#ef5350',
          wickUpColor: '#26a69a',
          wickDownColor: '#ef5350',
          priceLineVisible: true,
          lastValueVisible: true,
        })
        candleSeries.setData(candleData)
        candleSeriesRef.current = candleSeries
      }

      const [rangeStart, rangeEnd] = candleLineRange(candleData)

      // periodsUsed 순으로 색상 매핑.
      trendLineEntries.forEach(({ period, value }, idx) => {
        const color = PERIOD_COLORS[idx % PERIOD_COLORS.length]
        const series = chart.addLineSeries({
          color,
          lineWidth: 2,
          priceLineVisible: false,
          lastValueVisible: true,
          title: `MA${period}`,
        })
        const data: LineData[] = [
          { time: rangeStart, value },
          { time: rangeEnd, value },
        ]
        series.setData(data)
        seriesMapRef.current.set(period, series)
      })

      // lastClose 별도 series (회색 표식) — 사용자가 MA와 비교 가능.
      if (shouldDrawLastClose && lastClose !== null) {
        const closeSeries = chart.addLineSeries({
          color: '#e5e7eb',
          lineWidth: 3,
          priceLineVisible: true,
          lastValueVisible: true,
          title: '종가',
        })
        closeSeries.setData([
          { time: rangeStart, value: lastClose },
          { time: rangeEnd, value: lastClose },
        ])
        seriesMapRef.current.set(-1, closeSeries)
      }

      chart.timeScale().fitContent()
    } catch {
      // graceful — disposed during async data fetch
    }
  }, [candleData, hasDisplayData, lastClose, shouldDrawLastClose, trendLineEntries])

  if (isLoading) {
    return (
      <div className="flex h-[260px] items-center justify-center rounded-lg border border-gray-800 bg-gray-900">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-700 border-t-blue-500" />
      </div>
    )
  }

  if (isError) {
    return (
      <div
        role="alert"
        className="flex h-[260px] items-center justify-center rounded-lg border border-red-900 bg-red-950/30 text-sm text-red-300"
      >
        지수 추세 데이터를 불러오는 중 오류가 발생했습니다.
      </div>
    )
  }

  if (!hasDisplayData) {
    const maxPeriod = trend?.periodsUsed.length ? Math.max(...trend.periodsUsed) : null
    const isOverseas = trend?.code === '.IXIC' || trend?.code === '.SPX'
    return (
      <div
        role="status"
        className="flex h-[260px] flex-col items-center justify-center gap-2 rounded-lg border border-gray-800 bg-gray-900 text-sm text-gray-400"
      >
        <p>{isOverseas ? '미국장 지수 캔들 수집 대기 중입니다.' : '지수 캔들 수집 대기 중입니다.'}</p>
        {maxPeriod !== null && (
          <p className="text-xs text-gray-500">
            MA 계산 필요 최소: {maxPeriod}일. 장중 캔들은 snapshot 수집 즉시 표시됩니다.
          </p>
        )}
      </div>
    )
  }

  return (
    <div className="rounded-lg border border-gray-800 bg-gray-900 p-2">
      <div ref={containerRef} className="w-full" />
    </div>
  )
}
