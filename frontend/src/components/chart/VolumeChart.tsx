/**
 * VolumeChart — 거래량 히스토그램 (LWC HistogramSeries)
 * LWC 공식 API만 사용 (ADR 0022)
 */
import { useEffect, useRef } from 'react'
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  type HistogramData,
  type Time,
} from 'lightweight-charts'
import { cn } from '@/lib/cn'
import type { CandleDto } from '@/api/types'

interface VolumeChartProps {
  candles: CandleDto[]
  height?: number
  className?: string
}

export default function VolumeChart({ candles, height = 100, className }: VolumeChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Histogram'> | null>(null)

  useEffect(() => {
    if (!containerRef.current) return

    const chart = createChart(containerRef.current, {
      height,
      layout: { background: { color: '#ffffff' }, textColor: '#999' },
      grid: {
        vertLines: { color: '#f0f0f0' },
        horzLines: { visible: false },
      },
      timeScale: { timeVisible: true, secondsVisible: false },
      rightPriceScale: { scaleMargins: { top: 0.1, bottom: 0 } },
      handleScroll: false,
      handleScale: false,
    })

    const series = chart.addHistogramSeries({
      color: '#94a3b8',
      priceFormat: { type: 'volume' },
      priceScaleId: '',
    })

    chartRef.current = chart
    seriesRef.current = series

    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry) chart.resize(entry.contentRect.width, height)
    })
    observer.observe(containerRef.current)

    return () => {
      observer.disconnect()
      chart.remove()
      chartRef.current = null
      seriesRef.current = null
    }
  }, [height])

  useEffect(() => {
    if (!seriesRef.current) return
    const data: HistogramData[] = candles.map((c) => ({
      time: c.time as Time,
      value: c.volume,
      color: c.close >= c.open ? '#fca5a5' : '#93c5fd',
    }))
    seriesRef.current.setData(data)
  }, [candles])

  return <div ref={containerRef} className={cn(className)} style={{ height }} />
}
