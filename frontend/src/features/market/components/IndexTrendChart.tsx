/**
 * IndexTrendChart — 지수 추세 horizontal indicator chart (T-MARKET R2 §2-1)
 *
 * LWC v4 (lightweight-charts ^4.2.0) — addLineSeries로 MA periods 라인 N개 overlay.
 * 실제 캔들 시계열은 backend가 별도 제공 안 함 (R1 단계 — MA 값과 lastClose만 응답).
 * → 본 차트는 "MA period별 현재값 수평선 + lastClose 마커" 형태의 단순 시각화.
 *
 * MarketTrendPage 좌측 패널에서 사용. 실제 일봉 시계열 overlay는 향후 확장 슬롯 (R3 backlog).
 *
 * §13 dispose race 가드 (ADR 0086 §2-8 정합) — EquityCurveChart 패턴 1:1.
 */
import { useEffect, useRef } from 'react'
import { createChart, type IChartApi, type ISeriesApi, type LineData } from 'lightweight-charts'
import { toNumberOrNull, type IndexTrendResponse } from '../../../api/market'

interface Props {
  trend: IndexTrendResponse | undefined
  isLoading?: boolean
  isError?: boolean
}

/** period → 라인 색상 (5개까지). 헌법 §1 색상 컨벤션 — 사용자 직관 정합. */
const PERIOD_COLORS = ['#60a5fa', '#a78bfa', '#f59e0b', '#ec4899', '#10b981']

export function IndexTrendChart({ trend, isLoading = false, isError = false }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesMapRef = useRef<Map<number, ISeriesApi<'Line'>>>(new Map())
  const isDisposedRef = useRef(false)

  useEffect(() => {
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
        timeVisible: false,
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
      seriesMapRef.current.clear()
      ro.disconnect()
      try {
        chart.remove()
      } catch {
        // graceful
      }
      chartRef.current = null
    }
  }, [])

  // 데이터 갱신 — MA periods 별 horizontal line + lastClose marker.
  useEffect(() => {
    if (isDisposedRef.current) return
    const chart = chartRef.current
    if (!chart || !trend) return

    try {
      // 기존 series 제거 (periods 변경 대응) — chart.removeSeries 호출.
      seriesMapRef.current.forEach((s) => {
        try {
          chart.removeSeries(s)
        } catch {
          // graceful
        }
      })
      seriesMapRef.current.clear()

      if (trend.dataInsufficient) {
        return
      }

      // 단순 horizontal line — 좌(과거)에서 우(현재)로 동일 값 2점.
      // BusinessDay 형식 LineData 호환 — 단순 sequential index 사용 (timestamp 없음).
      // LWC v4는 time 필드에 number(UTCTimestamp seconds), string(YYYY-MM-DD), BusinessDay 모두 허용.
      // 가장 안전한 path: 오늘 기준 N일 전 ~ 오늘 BusinessDay string.
      const today = new Date()
      const past = new Date(today)
      past.setDate(today.getDate() - 30) // 30일 전 ~ 오늘 horizontal.
      const isoToday = today.toISOString().slice(0, 10)
      const isoPast = past.toISOString().slice(0, 10)

      // periodsUsed 순으로 색상 매핑.
      trend.periodsUsed.forEach((period, idx) => {
        const maValue = toNumberOrNull(trend.ma[String(period)])
        if (maValue === null) return

        const color = PERIOD_COLORS[idx % PERIOD_COLORS.length]
        const series = chart.addLineSeries({
          color,
          lineWidth: 2,
          priceLineVisible: false,
          lastValueVisible: true,
          title: `MA${period}`,
        })
        const data: LineData[] = [
          { time: isoPast as LineData['time'], value: maValue },
          { time: isoToday as LineData['time'], value: maValue },
        ]
        series.setData(data)
        seriesMapRef.current.set(period, series)
      })

      // lastClose 별도 series (회색 표식) — 사용자가 MA와 비교 가능.
      const lastClose = toNumberOrNull(trend.lastClose)
      if (lastClose !== null) {
        const closeSeries = chart.addLineSeries({
          color: '#e5e7eb',
          lineWidth: 3,
          priceLineVisible: true,
          lastValueVisible: true,
          title: '종가',
        })
        closeSeries.setData([
          { time: isoPast as LineData['time'], value: lastClose },
          { time: isoToday as LineData['time'], value: lastClose },
        ])
        seriesMapRef.current.set(-1, closeSeries)
      }

      chart.timeScale().fitContent()
    } catch {
      // graceful — disposed during async data fetch
    }
  }, [trend])

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

  if (trend && trend.dataInsufficient) {
    return (
      <div
        role="status"
        className="flex h-[260px] flex-col items-center justify-center gap-2 rounded-lg border border-gray-800 bg-gray-900 text-sm text-gray-400"
      >
        <p>캔들 데이터가 부족합니다.</p>
        <p className="text-xs text-gray-500">
          필요 최소: {Math.max(...trend.periodsUsed)}일. 시장 개장 후 수집 대기 중.
        </p>
      </div>
    )
  }

  return (
    <div className="rounded-lg border border-gray-800 bg-gray-900 p-2">
      <div ref={containerRef} className="w-full" />
    </div>
  )
}
