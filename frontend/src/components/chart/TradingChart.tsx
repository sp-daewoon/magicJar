/**
 * TradingChart — 트레이딩뷰 스타일 통합 차트
 *
 * 핵심 설계:
 *   - 단일 IChartApi 인스턴스에 candle + volume을 함께 추가 (LWC 공식 patten)
 *   - volume은 별도 priceScaleId('volume')로 하단 오버레이 → zoom·crosshair 자동 동기화
 *   - subscribeCrosshairMove로 OHLC 툴팁 표시
 *   - localization.dateFormat·timeFormatter로 한국 표준 (yyyy-MM-dd HH:mm:ss) 포맷
 *
 * LWC 공식 API만 사용 (ADR 0022 — html2canvas·DOM hack 금지)
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  type CandlestickData,
  type HistogramData,
  type LineData,
  type Time,
  type MouseEventParams,
  type UTCTimestamp,
} from 'lightweight-charts'
import { cn } from '@/lib/cn'
import type { CandleDto } from '@/api/types'
import type { ClientInterval } from '@/lib/candleAggregator'
import { computeIndicators } from '@/lib/indicators'

interface TradingChartProps {
  candles: CandleDto[]
  interval: ClientInterval
  height?: number
  className?: string
  /** 활성 보조지표 id 집합 */
  activeIndicators?: Set<string>
}

interface HoverInfo {
  time: string
  open: number
  high: number
  low: number
  close: number
  volume: number
  changeRate: number // 전일 대비
}

const KST = 'Asia/Seoul'

/**
 * LWC Time → 'YYYY-MM-DD HH:mm:ss' KST.
 * - 'YYYY-MM-DD' (BusinessDay 또는 string) 그대로
 * - epoch second (UTCTimestamp) → KST 변환
 */
function formatTimeKst(t: Time, isIntraday: boolean): string {
  if (typeof t === 'string') {
    return isIntraday ? `${t} 00:00:00` : t
  }
  if (typeof t === 'number') {
    const d = new Date(t * 1000)
    const fmt = new Intl.DateTimeFormat('ko-KR', {
      timeZone: KST,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    })
    const parts = Object.fromEntries(fmt.formatToParts(d).map((p) => [p.type, p.value]))
    return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
  }
  // BusinessDay 객체
  const bd = t as { year: number; month: number; day: number }
  const yy = String(bd.year)
  const mm = String(bd.month).padStart(2, '0')
  const dd = String(bd.day).padStart(2, '0')
  return isIntraday ? `${yy}-${mm}-${dd} 00:00:00` : `${yy}-${mm}-${dd}`
}

function isIntradayInterval(i: ClientInterval): boolean {
  return i === 'M1' || i === 'M5' || i === 'M15' || i === 'M30' || i === 'H1'
}

export default function TradingChart({
  candles,
  interval,
  height = 480,
  className,
  activeIndicators,
}: TradingChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const rsiContainerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const rsiChartRef = useRef<IChartApi | null>(null)
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const volumeSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null)
  const indicatorSeriesRef = useRef<Map<string, ISeriesApi<'Line'>>>(new Map())
  const [hover, setHover] = useState<HoverInfo | null>(null)

  const intraday = isIntradayInterval(interval)
  const active = activeIndicators ?? new Set<string>()
  const showRsi = active.has('RSI-14')

  const indicators = useMemo(() => computeIndicators(candles, active), [candles, active])
  const mainIndicators = indicators.filter((i) => i.pane === 'main')
  const rsiIndicator = indicators.find((i) => i.pane === 'rsi')

  useEffect(() => {
    if (!containerRef.current) return

    const chart = createChart(containerRef.current, {
      height,
      layout: {
        background: { color: '#ffffff' },
        textColor: '#374151',
        fontFamily: 'Pretendard, -apple-system, sans-serif',
      },
      grid: {
        vertLines: { color: '#f3f4f6' },
        horzLines: { color: '#f3f4f6' },
      },
      crosshair: {
        mode: 1, // Normal — 캔들 hover 시 십자선
        vertLine: { width: 1, color: '#9ca3af', style: 3, labelBackgroundColor: '#374151' },
        horzLine: { color: '#9ca3af', style: 3, labelBackgroundColor: '#374151' },
      },
      timeScale: {
        timeVisible: intraday,
        secondsVisible: false,
        borderColor: '#e5e7eb',
        // tickMarkFormatter: 시간축 눈금에 한국 표준 (LWC v4)
        tickMarkFormatter: (t: Time) => {
          if (typeof t === 'string') {
            return t.slice(5) // 'MM-DD'
          }
          if (typeof t === 'number') {
            const d = new Date(t * 1000)
            const fmt = new Intl.DateTimeFormat('ko-KR', {
              timeZone: KST,
              month: '2-digit',
              day: '2-digit',
              hour: intraday ? '2-digit' : undefined,
              minute: intraday ? '2-digit' : undefined,
              hour12: false,
            })
            return fmt.format(d).replace(/\. /g, '-').replace(/\.$/, '')
          }
          const bd = t as { year: number; month: number; day: number }
          return `${String(bd.month).padStart(2, '0')}-${String(bd.day).padStart(2, '0')}`
        },
      },
      rightPriceScale: {
        borderColor: '#e5e7eb',
        scaleMargins: { top: 0.05, bottom: 0.28 }, // 하단 28%는 volume용
      },
      localization: {
        dateFormat: 'yyyy-MM-dd',
        // crosshair label에 표시되는 시간 (LWC v4 timeFormatter)
        timeFormatter: (t: Time) => formatTimeKst(t, intraday),
      },
    })

    const candleSeries = chart.addCandlestickSeries({
      upColor: '#ef4444',
      downColor: '#3b82f6',
      borderUpColor: '#ef4444',
      borderDownColor: '#3b82f6',
      wickUpColor: '#ef4444',
      wickDownColor: '#3b82f6',
    })

    const volumeSeries = chart.addHistogramSeries({
      priceFormat: { type: 'volume' },
      priceScaleId: 'volume',
    })

    // volume scale을 메인 차트 하단 25%에만 위치
    chart.priceScale('volume').applyOptions({
      scaleMargins: { top: 0.75, bottom: 0 },
    })

    chartRef.current = chart
    candleSeriesRef.current = candleSeries
    volumeSeriesRef.current = volumeSeries

    // OHLC 툴팁 — LWC 공식 subscribeCrosshairMove
    const handleCrosshair = (param: MouseEventParams) => {
      if (!param.time || !param.point || param.point.x < 0 || param.point.y < 0) {
        setHover(null)
        return
      }
      const candleData = param.seriesData.get(candleSeries) as CandlestickData | undefined
      const volumeData = param.seriesData.get(volumeSeries) as HistogramData | undefined
      if (!candleData) {
        setHover(null)
        return
      }
      const changeRate =
        candleData.open > 0
          ? ((candleData.close - candleData.open) / candleData.open) * 100
          : 0
      setHover({
        time: formatTimeKst(param.time, intraday),
        open: candleData.open,
        high: candleData.high,
        low: candleData.low,
        close: candleData.close,
        volume: typeof volumeData?.value === 'number' ? volumeData.value : 0,
        changeRate,
      })
    }
    chart.subscribeCrosshairMove(handleCrosshair)

    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry) chart.resize(entry.contentRect.width, height)
    })
    observer.observe(containerRef.current)

    return () => {
      observer.disconnect()
      chart.unsubscribeCrosshairMove(handleCrosshair)
      chart.remove()
      chartRef.current = null
      candleSeriesRef.current = null
      volumeSeriesRef.current = null
      setHover(null)
    }
  }, [height, intraday])

  // 데이터 업데이트
  useEffect(() => {
    if (!candleSeriesRef.current || !volumeSeriesRef.current) return

    // 분봉은 epoch second(UTCTimestamp), 일/주/월/년은 'YYYY-MM-DD' 문자열
    const toLwcTime = (t: string | number): Time => {
      if (typeof t === 'number') return t as UTCTimestamp
      // 'YYYY-MM-DD' — LWC가 BusinessDay로 처리
      return t as Time
    }

    const candleData: CandlestickData[] = candles.map((c) => ({
      time: toLwcTime(c.time),
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
    }))

    const volumeData: HistogramData[] = candles.map((c) => ({
      time: toLwcTime(c.time),
      value: c.volume,
      color: c.close >= c.open ? 'rgba(239, 68, 68, 0.45)' : 'rgba(59, 130, 246, 0.45)',
    }))

    candleSeriesRef.current.setData(candleData)
    volumeSeriesRef.current.setData(volumeData)

    // 마지막 100봉만 보이도록 초기 zoom (사용자 조작 시 그대로 유지)
    if (candles.length > 0 && chartRef.current) {
      chartRef.current.timeScale().fitContent()
    }
  }, [candles])

  // 보조지표 시리즈 add/remove/update — main pane
  useEffect(() => {
    if (!chartRef.current) return
    const chart = chartRef.current
    const seriesMap = indicatorSeriesRef.current

    const toLwcTime = (t: string | number): Time =>
      typeof t === 'number' ? (t as UTCTimestamp) : (t as Time)

    // 활성 main pane indicator만 처리 (RSI는 별도 chart)
    const activeMainIds = new Set(mainIndicators.map((i) => i.id))

    // 비활성된 series 제거
    for (const [id, series] of seriesMap) {
      if (!activeMainIds.has(id)) {
        chart.removeSeries(series)
        seriesMap.delete(id)
      }
    }

    // 추가/업데이트
    for (const ind of mainIndicators) {
      let series = seriesMap.get(ind.id)
      if (!series) {
        series = chart.addLineSeries({
          color: ind.color,
          lineWidth: 1,
          priceLineVisible: false,
          lastValueVisible: false,
        })
        seriesMap.set(ind.id, series)
      }
      const data: LineData[] = ind.data.map((p) => ({
        time: toLwcTime(p.time),
        value: p.value,
      }))
      series.setData(data)
    }
  }, [mainIndicators])

  // RSI 별도 sub-chart
  useEffect(() => {
    if (!rsiContainerRef.current) return
    if (!showRsi) {
      // RSI 끄면 chart 제거
      rsiChartRef.current?.remove()
      rsiChartRef.current = null
      return
    }

    const chart = createChart(rsiContainerRef.current, {
      height: 120,
      layout: {
        background: { color: '#ffffff' },
        textColor: '#374151',
        fontFamily: 'Pretendard, -apple-system, sans-serif',
      },
      grid: {
        vertLines: { color: '#f3f4f6' },
        horzLines: { color: '#f3f4f6' },
      },
      timeScale: { timeVisible: intraday, secondsVisible: false, borderColor: '#e5e7eb' },
      rightPriceScale: { borderColor: '#e5e7eb' },
      crosshair: { mode: 1 },
    })
    rsiChartRef.current = chart

    const rsiSeries = chart.addLineSeries({
      color: '#dc2626',
      lineWidth: 1,
      priceLineVisible: false,
    })

    // 30·70 기준선
    rsiSeries.createPriceLine({ price: 70, color: '#fbbf24', lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: '70' })
    rsiSeries.createPriceLine({ price: 30, color: '#fbbf24', lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: '30' })

    if (rsiIndicator) {
      const toLwcTime = (t: string | number): Time =>
        typeof t === 'number' ? (t as UTCTimestamp) : (t as Time)
      const data: LineData[] = rsiIndicator.data.map((p) => ({
        time: toLwcTime(p.time),
        value: p.value,
      }))
      rsiSeries.setData(data)
    }

    // 메인 차트와 timeScale 동기화 (LWC 공식 subscribeVisibleLogicalRangeChange)
    const mainChart = chartRef.current
    if (mainChart) {
      const sync = (range: { from: number; to: number } | null) => {
        if (range) chart.timeScale().setVisibleLogicalRange(range)
      }
      mainChart.timeScale().subscribeVisibleLogicalRangeChange(sync)
    }

    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry) chart.resize(entry.contentRect.width, 120)
    })
    observer.observe(rsiContainerRef.current)

    return () => {
      observer.disconnect()
      chart.remove()
      rsiChartRef.current = null
    }
  }, [showRsi, rsiIndicator, intraday])

  return (
    <div className={cn('relative', className)}>
      <div ref={containerRef} style={{ height }} />
      {showRsi && (
        <div className="mt-1 border-t pt-1">
          <div className="px-2 text-[10px] font-medium text-muted-foreground">RSI 14</div>
          <div ref={rsiContainerRef} style={{ height: 120 }} />
        </div>
      )}
      {hover && (
        <div
          className="pointer-events-none absolute left-3 top-3 rounded-md border border-gray-200 bg-white/95 px-3 py-2 shadow-md backdrop-blur-sm"
          style={{ zIndex: 10 }}
        >
          <div className="mb-1 text-xs font-semibold text-gray-700">{hover.time}</div>
          <div className="grid grid-cols-2 gap-x-4 gap-y-0.5 text-xs tabular-nums">
            <span className="text-gray-500">시가</span>
            <span className="font-medium text-gray-900">{hover.open.toLocaleString()}</span>
            <span className="text-gray-500">고가</span>
            <span className="font-medium text-red-600">{hover.high.toLocaleString()}</span>
            <span className="text-gray-500">저가</span>
            <span className="font-medium text-blue-600">{hover.low.toLocaleString()}</span>
            <span className="text-gray-500">종가</span>
            <span
              className={cn(
                'font-medium',
                hover.changeRate > 0 && 'text-red-600',
                hover.changeRate < 0 && 'text-blue-600',
                hover.changeRate === 0 && 'text-gray-900',
              )}
            >
              {hover.close.toLocaleString()}
              <span className="ml-1 text-[10px] text-gray-400">
                {hover.changeRate > 0 ? '+' : ''}
                {hover.changeRate.toFixed(2)}%
              </span>
            </span>
            <span className="text-gray-500">거래량</span>
            <span className="font-medium text-gray-700">{hover.volume.toLocaleString()}</span>
          </div>
        </div>
      )}
    </div>
  )
}
