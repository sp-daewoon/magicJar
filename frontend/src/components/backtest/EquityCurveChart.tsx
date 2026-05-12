/**
 * EquityCurveChart — LWC v4 line chart, 누적 PnL 곡선 (T-BT R3)
 *
 * ADR 0025 §2-1 LOCK: LWC v4 공식 API 한정.
 * TradingView Advanced 라이브러리 import 0건.
 * 사용 API: createChart · addLineSeries · setData
 *
 * 입력: BacktestPositionResultDto[] — closeTs + pnl 기반
 * 누적 PnL = 포지션 종료 시점의 실현 손익 누적합
 *
 * feedback_no_mock_data.md 정합:
 *   positions 0건 또는 pnl 있는 포지션 0건 → "결과 데이터 없음" 안내
 *
 * 룰 5: LLM provider import 0건
 */
import { createChart, type IChartApi, type UTCTimestamp } from 'lightweight-charts'
import { useEffect, useRef } from 'react'
import type { BacktestPositionResultDto } from '../../api/backtest'

interface EquityCurveChartProps {
  positions: BacktestPositionResultDto[]
}

/** ISO string → UTCTimestamp (초 단위) */
function toUTCTimestamp(isoString: string): UTCTimestamp {
  return Math.floor(new Date(isoString).getTime() / 1000) as UTCTimestamp
}

/** KST 날짜 포맷터 */
function formatKstDate(ms: number): string {
  return new Intl.DateTimeFormat('sv-SE', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    timeZone: 'Asia/Seoul',
  }).format(new Date(ms))
}

export function EquityCurveChart({ positions }: EquityCurveChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)

  // 포지션 중 closeTs + pnl 있는 항목 필터 + 시간순 정렬
  const closedPositions = positions
    .filter((p) => p.closeTs !== null && p.pnl !== null)
    .sort((a, b) => new Date(a.closeTs!).getTime() - new Date(b.closeTs!).getTime())

  useEffect(() => {
    if (!containerRef.current) return

    const chart = createChart(containerRef.current, {
      layout: {
        background: { color: '#0f172a' },
        textColor: '#94a3b8',
      },
      grid: {
        vertLines: { color: '#1e293b' },
        horzLines: { color: '#1e293b' },
      },
      crosshair: {
        vertLine: { color: '#475569' },
        horzLine: { color: '#475569' },
      },
      rightPriceScale: {
        borderColor: '#334155',
      },
      timeScale: {
        borderColor: '#334155',
        tickMarkFormatter: (time: UTCTimestamp | { year: number; month: number; day: number }) => {
          const ms = typeof time === 'number' ? time * 1000 : Date.UTC((time as { year: number; month: number; day: number }).year, (time as { year: number; month: number; day: number }).month - 1, (time as { year: number; month: number; day: number }).day)
          return formatKstDate(ms)
        },
      },
      width: containerRef.current.clientWidth,
      height: 280,
    })

    chartRef.current = chart

    const lineSeries = chart.addLineSeries({
      color: '#22d3ee',
      lineWidth: 2,
      priceFormat: {
        type: 'custom',
        formatter: (v: number) =>
          v >= 0
            ? `+${v.toLocaleString('ko-KR')}원`
            : `${v.toLocaleString('ko-KR')}원`,
      },
    })

    // 초기 자본 기준 0 포인트 추가 + 누적 PnL 계산
    if (closedPositions.length > 0) {
      let cumPnl = 0
      const points: { time: UTCTimestamp; value: number }[] = []

      // 시작점: 첫 포지션 오픈 직전 0 포인트 (오픈 ts 기준)
      const firstOpenTs = positions
        .sort((a, b) => new Date(a.openTs).getTime() - new Date(b.openTs).getTime())[0]
        ?.openTs
      if (firstOpenTs) {
        points.push({ time: toUTCTimestamp(firstOpenTs), value: 0 })
      }

      for (const pos of closedPositions) {
        cumPnl += parseFloat(pos.pnl!)
        const ts = toUTCTimestamp(pos.closeTs!)
        // 같은 ts가 이미 있으면 덮어쓰기 (LWC는 시간 중복 허용 안 함)
        const last = points[points.length - 1]
        if (last && last.time === ts) {
          last.value = Math.round(cumPnl)
        } else {
          points.push({ time: ts, value: Math.round(cumPnl) })
        }
      }

      lineSeries.setData(points)
      chart.timeScale().fitContent()
    }

    // ResizeObserver
    const ro = new ResizeObserver(() => {
      if (containerRef.current) {
        chart.applyOptions({ width: containerRef.current.clientWidth })
      }
    })
    if (containerRef.current) ro.observe(containerRef.current)

    return () => {
      ro.disconnect()
      chart.remove()
      chartRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [positions])

  if (closedPositions.length === 0) {
    return (
      <div className="flex h-[280px] items-center justify-center rounded-xl border border-gray-800 bg-gray-900">
        <div className="text-center">
          <p className="text-sm text-gray-500">종료 포지션 데이터가 없습니다</p>
          <p className="mt-1 text-xs text-gray-700">백테스트 실행 후 결과를 확인하세요</p>
        </div>
      </div>
    )
  }

  return (
    <div className="overflow-hidden rounded-xl border border-gray-800 bg-gray-900">
      <div className="border-b border-gray-800 px-4 py-2.5">
        <h3 className="text-sm font-medium text-gray-300">누적 손익 (Equity Curve)</h3>
        <p className="mt-0.5 text-xs text-gray-600">초기 자본 기준 누적 실현 손익 (원화)</p>
      </div>
      <div ref={containerRef} className="w-full" />
    </div>
  )
}
