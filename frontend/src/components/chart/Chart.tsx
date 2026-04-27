/**
 * Chart — LWC 얇은 래퍼 (spec §11-3, ADR 0022)
 *
 * 준수 원칙 (spec §11-5):
 *   - LWC 공식 API(createChart, addLineSeries, addCandlestickSeries,
 *     setData, update, takeScreenshot, subscribeVisibleLogicalRangeChange 등)만 호출
 *   - 자체 <canvas> 오버레이 / DOM hack / prototype 조작 금지
 *   - html-to-image·html2canvas·dom-to-image 등 외부 캡처 라이브러리 도입 금지
 *   - 캡처는 LWC 공식 takeScreenshot() → chartCapture.ts
 */
import { useEffect, useRef } from 'react'
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  type CandlestickData,
  type LineData,
  type SeriesMarker,
  type Time,
} from 'lightweight-charts'
import { captureChart } from './chartCapture'
import { cn } from '@/lib/cn'

export interface Candle {
  time: number
  open: number
  high: number
  low: number
  close: number
}

export interface Tick {
  time: number
  value: number
}

export interface IndicatorSeries {
  id: string
  type: 'line' | 'histogram'
  data: LineData[]
  color?: string
}

export interface ChartProps {
  data: {
    candles?: Candle[]
    ticks?: Tick[]
    indicators?: IndicatorSeries[]
    markers?: SeriesMarker<Time>[]
  }
  height?: number
  onCapture?: (png: Blob) => void
  className?: string
}

/**
 * LWC 공식 시리즈를 결정: candles 있으면 CandlestickSeries, 없으면 LineSeries
 */
export default function Chart({ data, height = 400, onCapture, className }: ChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const lineSeriesRef = useRef<ISeriesApi<'Line'> | null>(null)

  // 차트 초기화
  useEffect(() => {
    if (!containerRef.current) return

    const chart = createChart(containerRef.current, {
      height,
      layout: {
        background: { color: '#ffffff' },
        textColor: '#333',
      },
      grid: {
        vertLines: { color: '#f0f0f0' },
        horzLines: { color: '#f0f0f0' },
      },
      timeScale: {
        timeVisible: true,
        secondsVisible: false,
      },
    })

    chartRef.current = chart

    if (data.candles && data.candles.length > 0) {
      const series = chart.addCandlestickSeries({
        upColor: '#ef4444',
        downColor: '#3b82f6',
        borderUpColor: '#ef4444',
        borderDownColor: '#3b82f6',
        wickUpColor: '#ef4444',
        wickDownColor: '#3b82f6',
      })
      candleSeriesRef.current = series
    } else {
      const series = chart.addLineSeries({ color: '#3b82f6' })
      lineSeriesRef.current = series
    }

    // ResizeObserver로 컨테이너 크기 변경에 대응 (LWC 공식 resize API)
    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry) {
        chart.resize(entry.contentRect.width, height)
      }
    })
    observer.observe(containerRef.current)

    return () => {
      observer.disconnect()
      chart.remove()
      chartRef.current = null
      candleSeriesRef.current = null
      lineSeriesRef.current = null
    }
  }, [height])

  // 캔들 데이터 업데이트
  useEffect(() => {
    if (!candleSeriesRef.current || !data.candles) return
    const series = candleSeriesRef.current

    const lwcData: CandlestickData[] = data.candles.map((c) => ({
      time: c.time as Time,
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
    }))

    series.setData(lwcData)

    if (data.markers) {
      series.setMarkers(data.markers)
    }
  }, [data.candles, data.markers])

  // 틱 데이터 업데이트
  useEffect(() => {
    if (!lineSeriesRef.current || !data.ticks) return
    const series = lineSeriesRef.current

    const lwcData: LineData[] = data.ticks.map((t) => ({
      time: t.time as Time,
      value: t.value,
    }))

    series.setData(lwcData)
  }, [data.ticks])

  // onCapture 핸들러
  function handleCapture() {
    if (!chartRef.current || !onCapture) return
    captureChart(chartRef.current).then(onCapture).catch(console.error)
  }

  return (
    <div className={cn('relative', className)}>
      <div ref={containerRef} style={{ height }} />
      {onCapture && (
        <button
          type="button"
          onClick={handleCapture}
          className="absolute right-2 top-2 rounded bg-white/80 px-2 py-1 text-xs shadow hover:bg-white"
        >
          캡처
        </button>
      )}
    </div>
  )
}
