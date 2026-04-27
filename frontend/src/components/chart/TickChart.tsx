/**
 * TickChart — 실시간 틱 LineSeries 래퍼
 * LWC 공식: LineSeries.update({time, value}) — 비균등 시간 자연 처리 (spec §11-2)
 */
import { useEffect, useRef } from 'react'
import { createChart, type IChartApi, type ISeriesApi, type Time } from 'lightweight-charts'
import { cn } from '@/lib/cn'
import type { Tick } from './Chart'

interface TickChartProps {
  ticks: Tick[]
  height?: number
  className?: string
  /** 실시간 tick이 추가되면 호출 (STOMP 연동 시 사용) */
  onNewTick?: (handler: (tick: Tick) => void) => () => void
}

export default function TickChart({ ticks, height = 300, className, onNewTick }: TickChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Line'> | null>(null)

  useEffect(() => {
    if (!containerRef.current) return

    const chart = createChart(containerRef.current, {
      height,
      layout: { background: { color: '#ffffff' }, textColor: '#333' },
      grid: {
        vertLines: { color: '#f0f0f0' },
        horzLines: { color: '#f0f0f0' },
      },
      timeScale: { timeVisible: true, secondsVisible: true },
    })

    const series = chart.addLineSeries({ color: '#3b82f6', lineWidth: 1 })
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

  // 초기 데이터 / 전체 교체
  useEffect(() => {
    if (!seriesRef.current) return
    seriesRef.current.setData(
      ticks.map((t) => ({ time: t.time as Time, value: t.value })),
    )
  }, [ticks])

  // 실시간 단건 업데이트
  useEffect(() => {
    if (!onNewTick || !seriesRef.current) return
    const series = seriesRef.current
    const unsubscribe = onNewTick((tick) => {
      series.update({ time: tick.time as Time, value: tick.value })
    })
    return unsubscribe
  }, [onNewTick])

  return <div ref={containerRef} className={cn(className)} style={{ height }} />
}
