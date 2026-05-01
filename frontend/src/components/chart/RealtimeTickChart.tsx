/**
 * RealtimeTickChart — STOMP `/topic/market.tick.{code}` 라이브 스트림 차트.
 *
 * 핵심:
 *   - 컴포넌트 마운트 시 STOMP 구독, 매 메시지마다 LineSeries.update({time, value}) 호출
 *   - LWC v4가 비균등 시간(epoch second 단위로 ms 미만은 같은 second에 묶임) 자연 처리
 *   - 마지막 N개 윈도우(기본 600 = 10분 @ 1s) 유지 — UI 부담 차단
 *   - Crosshair tooltip 한국 표준 시간 'yyyy-MM-dd HH:mm:ss'
 *
 * 설계 분리:
 *   캔들/봉단위 차트(TradingChart)는 candle endpoint 응답 기반 (정적 + DAY 단위 fitContent).
 *   틱 차트는 candle endpoint 호출하지 않음 — 순수 STOMP 라이브 스트림.
 */
import { useEffect, useRef, useState } from 'react'
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  type LineData,
  type Time,
  type UTCTimestamp,
  type MouseEventParams,
} from 'lightweight-charts'
import { cn } from '@/lib/cn'
import { stompService } from '@/ws/stomp'
import type { TickMessage } from '@/api/types'

interface Props {
  code: string
  height?: number
  className?: string
  /** 표시 윈도우 — 마지막 N개 틱만 유지 (기본 600 = 10분 @ 1s mock tick) */
  windowSize?: number
}

interface HoverInfo {
  time: string
  price: number
  changeFromFirst: number
  changeRate: number
}

const KST = 'Asia/Seoul'

function formatKst(t: Time): string {
  if (typeof t !== 'number') return String(t)
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

export default function RealtimeTickChart({ code, height = 480, className, windowSize = 600 }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Line'> | null>(null)
  const bufferRef = useRef<LineData[]>([])
  const firstPriceRef = useRef<number | null>(null)
  const [hover, setHover] = useState<HoverInfo | null>(null)
  const [latestPrice, setLatestPrice] = useState<number | null>(null)
  const [tickCount, setTickCount] = useState(0)

  // 차트 초기화
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
        mode: 1,
        vertLine: { width: 1, color: '#9ca3af', style: 3, labelBackgroundColor: '#374151' },
        horzLine: { color: '#9ca3af', style: 3, labelBackgroundColor: '#374151' },
      },
      timeScale: {
        timeVisible: true,
        secondsVisible: true,
        borderColor: '#e5e7eb',
        tickMarkFormatter: (t: Time) => {
          if (typeof t !== 'number') return String(t)
          const d = new Date(t * 1000)
          const fmt = new Intl.DateTimeFormat('ko-KR', {
            timeZone: KST,
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            hour12: false,
          })
          return fmt.format(d)
        },
      },
      rightPriceScale: { borderColor: '#e5e7eb' },
      localization: {
        dateFormat: 'yyyy-MM-dd',
        timeFormatter: formatKst,
      },
    })

    const series = chart.addLineSeries({
      color: '#3b82f6',
      lineWidth: 2,
      priceLineVisible: true,
      lastValueVisible: true,
    })

    chartRef.current = chart
    seriesRef.current = series

    // Crosshair tooltip
    const handleCrosshair = (param: MouseEventParams) => {
      if (!param.time || !param.point || param.point.x < 0 || param.point.y < 0) {
        setHover(null)
        return
      }
      const data = param.seriesData.get(series) as LineData | undefined
      if (!data) {
        setHover(null)
        return
      }
      const first = firstPriceRef.current ?? data.value
      const change = data.value - first
      const rate = first > 0 ? (change / first) * 100 : 0
      setHover({
        time: formatKst(param.time),
        price: data.value,
        changeFromFirst: change,
        changeRate: rate,
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
      seriesRef.current = null
      bufferRef.current = []
      firstPriceRef.current = null
      setHover(null)
      setLatestPrice(null)
      setTickCount(0)
    }
  }, [height])

  // STOMP 구독 — code 변경 시 재구독
  useEffect(() => {
    if (!code || !seriesRef.current) return

    bufferRef.current = []
    firstPriceRef.current = null
    setLatestPrice(null)
    setTickCount(0)
    seriesRef.current.setData([])

    stompService.connect('/ws')
    const topic = `/topic/market.tick.${code}`

    const unsubscribe = stompService.subscribe(topic, (msg) => {
      try {
        const tick: TickMessage = JSON.parse(msg.body)
        const epoch = Math.floor(new Date(tick.at).getTime() / 1000) as UTCTimestamp
        const point: LineData = { time: epoch, value: tick.price }

        const buf = bufferRef.current
        // 같은 epoch second 중복 방지 — LWC가 동일 시간 데이터 거부.
        if (buf.length > 0 && buf[buf.length - 1].time === point.time) {
          buf[buf.length - 1] = point
          seriesRef.current?.update(point)
        } else {
          buf.push(point)
          if (buf.length > windowSize) {
            buf.shift()
            // 윈도우 초과 시 setData로 재셋
            seriesRef.current?.setData(buf)
          } else {
            seriesRef.current?.update(point)
          }
        }

        if (firstPriceRef.current == null) {
          firstPriceRef.current = tick.price
        }
        setLatestPrice(tick.price)
        setTickCount((n) => n + 1)
      } catch {
        // ignore parse error
      }
    })

    return () => {
      unsubscribe()
    }
  }, [code, windowSize])

  const first = firstPriceRef.current
  const change = latestPrice != null && first != null ? latestPrice - first : null
  const rate = latestPrice != null && first != null && first > 0 ? (change! / first) * 100 : null

  return (
    <div className={cn('relative', className)}>
      <div ref={containerRef} style={{ height }} />

      {/* 좌상단 라이브 상태 카드 */}
      <div
        className="pointer-events-none absolute left-3 top-3 rounded-md border border-gray-200 bg-white/95 px-3 py-2 shadow-md backdrop-blur-sm"
        style={{ zIndex: 10 }}
      >
        <div className="flex items-center gap-2">
          <span className="inline-flex h-2 w-2 animate-pulse rounded-full bg-red-500" />
          <span className="text-xs font-semibold text-gray-700">LIVE · {code}</span>
        </div>
        <div className="mt-1 text-base font-bold tabular-nums">
          {latestPrice != null ? latestPrice.toLocaleString() : '대기 중...'}
          {rate != null && (
            <span
              className={cn(
                'ml-2 text-xs font-medium',
                rate > 0 && 'text-red-600',
                rate < 0 && 'text-blue-600',
              )}
            >
              {rate > 0 ? '+' : ''}
              {rate.toFixed(2)}%
            </span>
          )}
        </div>
        <div className="mt-0.5 text-[10px] text-gray-400 tabular-nums">
          틱 {tickCount.toLocaleString()}건 · 윈도우 {Math.min(tickCount, windowSize)}/{windowSize}
        </div>
      </div>

      {/* hover 시 우상단 상세 */}
      {hover && (
        <div
          className="pointer-events-none absolute right-3 top-3 rounded-md border border-gray-200 bg-white/95 px-3 py-2 shadow-md backdrop-blur-sm"
          style={{ zIndex: 10 }}
        >
          <div className="mb-1 text-xs font-semibold text-gray-700">{hover.time}</div>
          <div className="grid grid-cols-2 gap-x-4 text-xs tabular-nums">
            <span className="text-gray-500">가격</span>
            <span className="font-medium">{hover.price.toLocaleString()}</span>
            <span className="text-gray-500">시작 대비</span>
            <span
              className={cn(
                'font-medium',
                hover.changeFromFirst > 0 && 'text-red-600',
                hover.changeFromFirst < 0 && 'text-blue-600',
              )}
            >
              {hover.changeFromFirst > 0 ? '+' : ''}
              {hover.changeFromFirst.toLocaleString()} ({hover.changeRate.toFixed(2)}%)
            </span>
          </div>
        </div>
      )}
    </div>
  )
}
