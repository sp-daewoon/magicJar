/**
 * DailyPnlBarChart — LWC v4 Daily P&L histogram chart
 *
 * ADR 0072 §8 + spec §3-3 LWC v4 chart 패턴 정합:
 * - createChart + addHistogramSeries (dailyPnl 시계열)
 * - 양수: emerald, 음수: red
 * - 빈 데이터 graceful
 *
 * 룰 5: anthropic·openai import 0건
 */
import { useEffect, useRef } from 'react'
import { createChart } from 'lightweight-charts'
import type { IChartApi, ISeriesApi, HistogramData } from 'lightweight-charts'
import type { EquityPointDto } from '../api/performanceApi'

interface DailyPnlBarChartProps {
  points: EquityPointDto[] | undefined
  isLoading: boolean
  isError: boolean
}

export function DailyPnlBarChart({ points, isLoading, isError }: DailyPnlBarChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Histogram'> | null>(null)

  useEffect(() => {
    if (!containerRef.current) return

    const chart = createChart(containerRef.current, {
      width: containerRef.current.clientWidth,
      height: 200,
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
      },
      rightPriceScale: {
        borderColor: '#374151',
      },
    })

    const histSeries = chart.addHistogramSeries({
      color: '#10b981',
      priceLineVisible: false,
    })

    chartRef.current = chart
    seriesRef.current = histSeries

    const ro = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry && chartRef.current) {
        chartRef.current.applyOptions({ width: entry.contentRect.width })
      }
    })
    ro.observe(containerRef.current)

    return () => {
      ro.disconnect()
      chart.remove()
      chartRef.current = null
      seriesRef.current = null
    }
  }, [])

  useEffect(() => {
    if (!seriesRef.current || !points) return

    if (points.length === 0) {
      seriesRef.current.setData([])
      return
    }

    const data: HistogramData[] = points.map((p) => ({
      time: p.tradeDate as HistogramData['time'],
      value: Number(p.dailyPnl),
      color: Number(p.dailyPnl) >= 0 ? '#10b981' : '#ef4444',
    }))

    seriesRef.current.setData(data)
    chartRef.current?.timeScale().fitContent()
  }, [points])

  if (isLoading) {
    return (
      <div className="flex h-[200px] items-center justify-center rounded-lg border border-gray-800 bg-gray-900">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-700 border-t-blue-500" />
      </div>
    )
  }

  if (isError) {
    return (
      <div className="flex h-[200px] items-center justify-center rounded-lg border border-gray-800 bg-gray-900 text-sm text-red-400">
        일별 P&L 데이터를 불러오는 중 오류가 발생했습니다.
      </div>
    )
  }

  if (points !== undefined && points.length === 0) {
    return (
      <div className="flex h-[200px] flex-col items-center justify-center rounded-lg border border-gray-800 bg-gray-900">
        <p className="text-sm text-gray-500">일별 P&L 데이터가 없습니다.</p>
      </div>
    )
  }

  return (
    <div className="rounded-lg border border-gray-800 bg-gray-900 p-2">
      <div ref={containerRef} className="w-full" />
    </div>
  )
}
