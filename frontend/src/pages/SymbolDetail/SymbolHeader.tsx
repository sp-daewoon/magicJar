/**
 * SymbolHeader — 종목 상단 헤더
 * 종목명, 현재가(WebSocket 실시간), 등락률, 최종 갱신 시각
 */
import type { Instrument } from '@/api/types'
import type { QuoteState } from '@/hooks/useSymbolQuote'
import type { ConnectionState } from '@/ws/stomp'
import MarketStatusBadge from '@/components/market/MarketStatusBadge'
import { getMarketStatus } from '@/lib/marketHours'

interface SymbolHeaderProps {
  instrument: Instrument | null
  quote: QuoteState
  wsState: ConnectionState
  isLoading: boolean
}

function formatKrw(value: number): string {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    minimumFractionDigits: 0,
  }).format(value)
}

function formatTime(date: Date | null): string {
  if (!date) return '--:--:--'
  return date.toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
    timeZone: 'Asia/Seoul',
  })
}

function WsIndicator({ state }: { state: ConnectionState }) {
  const colorMap: Record<ConnectionState, string> = {
    CONNECTED: 'bg-green-500',
    CONNECTING: 'bg-yellow-400 animate-pulse',
    DISCONNECTED: 'bg-gray-400',
    ERROR: 'bg-red-500',
  }
  const labelMap: Record<ConnectionState, string> = {
    CONNECTED: '실시간',
    CONNECTING: '연결 중',
    DISCONNECTED: '끊김',
    ERROR: '오류',
  }
  return (
    <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
      <span className={`h-2 w-2 rounded-full ${colorMap[state]}`} />
      {labelMap[state]}
    </span>
  )
}

export default function SymbolHeader({ instrument, quote, wsState, isLoading }: SymbolHeaderProps) {
  if (isLoading) {
    return (
      <div className="space-y-2">
        <div className="h-7 w-48 animate-pulse rounded bg-gray-200" />
        <div className="h-10 w-36 animate-pulse rounded bg-gray-200" />
      </div>
    )
  }

  if (!instrument) return null

  const isPositive = (quote.changeRate ?? 0) >= 0
  const priceColor = isPositive ? 'text-red-500' : 'text-blue-500'
  const changeSign = isPositive ? '+' : ''
  const marketStatus = getMarketStatus()
  const isClosed = marketStatus.state !== 'OPEN'

  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      {/* 왼쪽: 종목명 + 코드 */}
      <div>
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-2xl font-bold">{instrument.name}</h1>
          <span className="text-sm text-muted-foreground">{instrument.code}</span>
          <span className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600">
            {instrument.market}
          </span>
          {isClosed && (
            <span className="rounded bg-gray-200 px-1.5 py-0.5 text-[10px] font-medium text-gray-700">
              장외 — 마지막 가격
            </span>
          )}
        </div>

        <div className="mt-1 flex items-end gap-3">
          {/* 현재가 */}
          <span className={`text-3xl font-bold tabular-nums ${priceColor}`}>
            {quote.price != null ? formatKrw(quote.price) : '—'}
          </span>

          {/* 등락률 */}
          {quote.changeRate != null && (
            <span className={`text-base font-semibold tabular-nums ${priceColor}`}>
              {changeSign}{quote.changeRate.toFixed(2)}%
            </span>
          )}

          {/* 전일 대비 */}
          {quote.change != null && (
            <span className={`text-sm tabular-nums ${priceColor}`}>
              ({changeSign}{formatKrw(quote.change)})
            </span>
          )}
        </div>
      </div>

      {/* 오른쪽: 시장 상태 + 연결 상태 + 갱신 시각 */}
      <div className="flex flex-col items-end gap-1 text-sm text-muted-foreground">
        <MarketStatusBadge />
        <WsIndicator state={wsState} />
        <span className="tabular-nums">
          갱신: {formatTime(quote.updatedAt)}
        </span>
      </div>
    </div>
  )
}
