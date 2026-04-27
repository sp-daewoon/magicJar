/**
 * CandleChart — 캔들스틱 + 보조지표 LineSeries/HistogramSeries 래퍼
 * LWC 공식 API만 사용 (spec §11-5, ADR 0022)
 * 보조지표 계산은 서버(domain.strategy.indicator)에서 수행 → props로 수신
 */
import { useEffect, useRef } from 'react'
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  type CandlestickData,
  type LineData,
  type HistogramData,
  type Time,
  type LogicalRange,
} from 'lightweight-charts'
import { cn } from '@/lib/cn'
import type { Candle, IndicatorSeries } from './Chart'

interface CandleChartProps {
  candles: Candle[]
  indicators?: IndicatorSeries[]
  height?: number
  className?: string
  /**
   * 다른 차트와 시간축 동기화: LWC 공식 subscribeVisibleLogicalRangeChange 사용
   * onRangeChange → 외부에서 setVisibleLogicalRange 호출
   */
  onRangeChange?: (range: LogicalRange | null) => void
  visibleRange?: LogicalRange | null
}

export default function CandleChart({
  candles,
  indicators = [],
  height = 400,
  className,
  onRangeChange,
  visibleRange,
}: CandleChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const indicatorSeriesRef = useRef<Map<string, ISeriesApi<'Line'> | ISeriesApi<'Histogram'>>>(new Map())

  useEffect(() => {
    if (!containerRef.current) return

    const chart = createChart(containerRef.current, {
      height,
      layout: { background: { color: '#ffffff' }, textColor: '#333' },
      grid: {
        vertLines: { color: '#f0f0f0' },
        horzLines: { color: '#f0f0f0' },
      },
      timeScale: { timeVisible: true, secondsVisible: false },
    })

    const series = chart.addCandlestickSeries({
      upColor: '#ef4444',
      downColor: '#3b82f6',
      borderUpColor: '#ef4444',
      borderDownColor: '#3b82f6',
      wickUpColor: '#ef4444',
      wickDownColor: '#3b82f6',
    })

    chartRef.current = chart
    candleSeriesRef.current = series

    // 시간축 동기화: LWC 공식 이벤트 구독
    if (onRangeChange) {
      chart.timeScale().subscribeVisibleLogicalRangeChange(onRangeChange)
    }

    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry) chart.resize(entry.contentRect.width, height)
    })
    observer.observe(containerRef.current)

    return () => {
      observer.disconnect()
      chart.remove()
      chartRef.current = null
      candleSeriesRef.current = null
      indicatorSeriesRef.current.clear()
    }
  }, [height, onRangeChange])

  // 캔들 데이터
  useEffect(() => {
    if (!candleSeriesRef.current) return
    const lwcData: CandlestickData[] = candles.map((c) => ({
      time: c.time as Time,
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
    }))
    candleSeriesRef.current.setData(lwcData)
  }, [candles])

  // 보조지표 시리즈
  useEffect(() => {
    if (!chartRef.current) return
    const chart = chartRef.current
    const seriesMap = indicatorSeriesRef.current

    // 제거된 indicator 정리
    seriesMap.forEach((s, id) => {
      if (!indicators.find((ind) => ind.id === id)) {
        chart.removeSeries(s)
        seriesMap.delete(id)
      }
    })

    // 추가/업데이트
    indicators.forEach((ind) => {
      let s = seriesMap.get(ind.id)
      if (!s) {
        if (ind.type === 'histogram') {
          s = chart.addHistogramSeries({ color: ind.color ?? '#94a3b8' })
        } else {
          s = chart.addLineSeries({ color: ind.color ?? '#f59e0b', lineWidth: 1 })
        }
        seriesMap.set(ind.id, s)
      }
      if (ind.type === 'histogram') {
        ;(s as ISeriesApi<'Histogram'>).setData(ind.data as HistogramData[])
      } else {
        ;(s as ISeriesApi<'Line'>).setData(ind.data as LineData[])
      }
    })
  }, [indicators])

  // 외부 시간축 동기화 (LWC 공식 setVisibleLogicalRange)
  useEffect(() => {
    if (!chartRef.current || visibleRange == null) return
    chartRef.current.timeScale().setVisibleLogicalRange(visibleRange)
  }, [visibleRange])

  return <div ref={containerRef} className={cn(className)} style={{ height }} />
}
