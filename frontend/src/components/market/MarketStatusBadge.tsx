/**
 * 시장 상태 배지 — KRX 정규장 09:00~15:30 KST 판정.
 * 실시간 갱신: 1분 간격으로 상태 재평가 (장 시작/마감 경계 자동 반영).
 */
import { useEffect, useState } from 'react'
import { cn } from '@/lib/cn'
import {
  getMarketStatus,
  marketStatusLabel,
  marketStatusDetail,
  type MarketStatus,
} from '@/lib/marketHours'

interface Props {
  className?: string
  /** 작게 표시 (배지 1줄). 기본은 1줄 + 보조문구 1줄. */
  compact?: boolean
}

const STYLE_BY_STATE: Record<MarketStatus['state'], { bg: string; fg: string; dot: string }> = {
  OPEN: { bg: 'bg-green-50', fg: 'text-green-700', dot: 'bg-green-500 animate-pulse' },
  PRE_OPEN: { bg: 'bg-amber-50', fg: 'text-amber-700', dot: 'bg-amber-400' },
  AFTER_HOURS: { bg: 'bg-gray-100', fg: 'text-gray-600', dot: 'bg-gray-400' },
  WEEKEND: { bg: 'bg-gray-100', fg: 'text-gray-600', dot: 'bg-gray-400' },
  HOLIDAY: { bg: 'bg-purple-50', fg: 'text-purple-700', dot: 'bg-purple-400' },
}

export default function MarketStatusBadge({ className, compact = false }: Props) {
  const [status, setStatus] = useState<MarketStatus>(() => getMarketStatus())

  useEffect(() => {
    // 1분마다 재평가 — 장 시작/마감 경계 자동 반영
    const id = setInterval(() => setStatus(getMarketStatus()), 60_000)
    return () => clearInterval(id)
  }, [])

  const style = STYLE_BY_STATE[status.state]

  if (compact) {
    return (
      <span
        className={cn(
          'inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium',
          style.bg,
          style.fg,
          className,
        )}
      >
        <span className={cn('h-1.5 w-1.5 rounded-full', style.dot)} />
        {marketStatusLabel(status)}
      </span>
    )
  }

  return (
    <div
      className={cn(
        'inline-flex flex-col rounded-md border border-gray-200 bg-white/60 px-3 py-1.5',
        className,
      )}
    >
      <div className={cn('flex items-center gap-1.5 text-xs font-semibold', style.fg)}>
        <span className={cn('h-2 w-2 rounded-full', style.dot)} />
        {marketStatusLabel(status)}
      </div>
      <div className="mt-0.5 text-[10px] text-muted-foreground">
        {marketStatusDetail(status)}
      </div>
    </div>
  )
}
