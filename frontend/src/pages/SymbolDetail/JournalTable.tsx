/**
 * JournalTable — 종목 매매일지 리스트
 * 컬럼: 진입시각·청산시각·진입가·청산가·수량·손익·전략명
 */
import type { TradeJournalEntry } from '@/api/types'

interface JournalTableProps {
  entries: TradeJournalEntry[]
  isLoading: boolean
  isError: boolean
}

function formatKrw(value: number): string {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    minimumFractionDigits: 0,
  }).format(value)
}

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Seoul',
  })
}

function PnlCell({ value, rate }: { value?: number; rate?: number }) {
  if (value == null) return <span className="text-muted-foreground">미청산</span>
  const positive = value >= 0
  return (
    <div className={`font-medium tabular-nums ${positive ? 'text-red-500' : 'text-blue-500'}`}>
      <div>{formatKrw(value)}</div>
      {rate != null && (
        <div className="text-xs">
          {positive ? '+' : ''}{rate.toFixed(2)}%
        </div>
      )}
    </div>
  )
}

function SkeletonRow() {
  return (
    <tr>
      {Array.from({ length: 7 }).map((_, i) => (
        <td key={i} className="px-3 py-2">
          <div className="h-4 w-full animate-pulse rounded bg-gray-200" />
        </td>
      ))}
    </tr>
  )
}

export default function JournalTable({ entries, isLoading, isError }: JournalTableProps) {
  return (
    <div className="overflow-x-auto rounded-md border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b bg-gray-50 text-left text-xs text-muted-foreground">
            <th className="px-3 py-2 font-medium">진입시각</th>
            <th className="px-3 py-2 font-medium">청산시각</th>
            <th className="px-3 py-2 text-right font-medium">진입가</th>
            <th className="px-3 py-2 text-right font-medium">청산가</th>
            <th className="px-3 py-2 text-right font-medium">수량</th>
            <th className="px-3 py-2 text-right font-medium">손익</th>
            <th className="px-3 py-2 font-medium">전략</th>
          </tr>
        </thead>
        <tbody>
          {isLoading && Array.from({ length: 3 }).map((_, i) => <SkeletonRow key={i} />)}

          {!isLoading && isError && (
            <tr>
              <td colSpan={7} className="px-3 py-8 text-center text-sm text-destructive">
                매매일지를 불러오지 못했습니다.
              </td>
            </tr>
          )}

          {!isLoading && !isError && entries.length === 0 && (
            <tr>
              <td colSpan={7} className="px-3 py-10 text-center text-muted-foreground">
                이 종목의 매매 기록이 없습니다.
              </td>
            </tr>
          )}

          {!isLoading && !isError && entries.map((entry) => (
            <tr key={entry.id} className="border-b last:border-0 hover:bg-gray-50">
              <td className="px-3 py-2 tabular-nums text-muted-foreground">
                {formatDateTime(entry.entryAt)}
              </td>
              <td className="px-3 py-2 tabular-nums text-muted-foreground">
                {entry.exitAt ? formatDateTime(entry.exitAt) : <span className="text-yellow-500">진행 중</span>}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                {formatKrw(entry.entryPrice)}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                {entry.exitPrice != null ? formatKrw(entry.exitPrice) : <span className="text-muted-foreground">—</span>}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                {entry.entryQty.toLocaleString('ko-KR')}
              </td>
              <td className="px-3 py-2 text-right">
                <PnlCell value={entry.pnl ?? undefined} rate={entry.pnlRate ?? undefined} />
              </td>
              <td className="px-3 py-2">
                <span className="rounded bg-blue-50 px-1.5 py-0.5 text-xs text-blue-700">
                  {entry.strategyId}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
