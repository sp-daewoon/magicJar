/**
 * JournalEntryList — 매매일지 시계열 테이블
 *
 * ADR 0072 §8 + spec §3-3 Phase 3 정합:
 * - loading state 스켈레톤
 * - 데이터 없음: "매매일지가 비어있습니다" (mock 합성 0 LOCK)
 * - 행 클릭 → 상세 모달
 * - 페이지네이션 (prev/next)
 */
import { EVENT_TYPE_LABELS } from './JournalEntryFilterBar'
import type { TradingJournalEntryDto, JournalEventType } from '../api/journalApi'

const EVENT_TYPE_COLORS: Record<JournalEventType, string> = {
  ORDER_FILLED: 'bg-blue-900/50 text-blue-300 border-blue-700',
  SIGNAL_GENERATED: 'bg-emerald-900/50 text-emerald-300 border-emerald-700',
  RISK_VIOLATION: 'bg-red-900/50 text-red-300 border-red-700',
  RECONCILE_DRIFT: 'bg-yellow-900/50 text-yellow-300 border-yellow-700',
  POSITION_UPDATED: 'bg-purple-900/50 text-purple-300 border-purple-700',
}

interface JournalEntryListProps {
  entries: TradingJournalEntryDto[]
  isLoading: boolean
  isError: boolean
  page: number
  totalPages: number
  onPageChange: (page: number) => void
  onEntryClick: (entry: TradingJournalEntryDto) => void
}

function formatKst(isoString: string): string {
  const d = new Date(isoString)
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatPnl(pnl: string | null): string {
  if (pnl === null) return '-'
  const n = Number(pnl)
  const sign = n > 0 ? '+' : ''
  return `${sign}${n.toLocaleString('ko-KR')}원`
}

export function JournalEntryList({
  entries,
  isLoading,
  isError,
  page,
  totalPages,
  onPageChange,
  onEntryClick,
}: JournalEntryListProps) {
  if (isLoading) {
    return (
      <div className="space-y-2 py-4" role="status" aria-label="로딩 중">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="h-12 animate-pulse rounded-lg bg-gray-800" />
        ))}
      </div>
    )
  }

  if (isError) {
    return (
      <div className="py-10 text-center text-sm text-red-400">
        매매일지를 불러오는 중 오류가 발생했습니다.
      </div>
    )
  }

  if (entries.length === 0) {
    return (
      <div className="py-16 text-center">
        <p className="text-sm text-gray-500">매매일지가 비어있습니다.</p>
        <p className="mt-1 text-xs text-gray-600">
          자동매매 시그널·체결·리스크 이벤트가 발생하면 여기에 기록됩니다.
        </p>
      </div>
    )
  }

  return (
    <div>
      <div className="overflow-x-auto rounded-lg border border-gray-800">
        <table className="w-full text-sm">
          <thead className="bg-gray-900">
            <tr>
              <th className="px-3 py-2.5 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
                시각
              </th>
              <th className="px-3 py-2.5 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
                이벤트
              </th>
              <th className="px-3 py-2.5 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
                종목
              </th>
              <th className="px-3 py-2.5 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
                내용
              </th>
              <th className="px-3 py-2.5 text-right text-xs font-medium uppercase tracking-wide text-gray-500">
                실현 손익
              </th>
              <th className="px-3 py-2.5 text-center text-xs font-medium uppercase tracking-wide text-gray-500">
                메모
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {entries.map((entry) => {
              const pnlNum = entry.realizedPnl !== null ? Number(entry.realizedPnl) : null
              const pnlColor =
                pnlNum === null
                  ? 'text-gray-500'
                  : pnlNum > 0
                    ? 'text-emerald-400'
                    : pnlNum < 0
                      ? 'text-red-400'
                      : 'text-gray-400'

              return (
                <tr
                  key={entry.id}
                  onClick={() => onEntryClick(entry)}
                  className="cursor-pointer transition-colors hover:bg-gray-800/60"
                >
                  <td className="whitespace-nowrap px-3 py-2.5 text-xs text-gray-400">
                    {formatKst(entry.occurredAt)}
                  </td>
                  <td className="px-3 py-2.5">
                    <span
                      className={`inline-flex items-center rounded border px-1.5 py-0.5 text-xs font-medium ${
                        EVENT_TYPE_COLORS[entry.eventType]
                      }`}
                    >
                      {EVENT_TYPE_LABELS[entry.eventType]}
                    </span>
                  </td>
                  <td className="px-3 py-2.5">
                    {entry.instrumentCode ? (
                      <span className="text-gray-200">
                        {entry.instrumentName ?? entry.instrumentCode}
                        <span className="ml-1 text-xs text-gray-500">({entry.instrumentCode})</span>
                      </span>
                    ) : (
                      <span className="text-gray-600">-</span>
                    )}
                  </td>
                  <td className="max-w-xs truncate px-3 py-2.5 text-gray-300" title={entry.summary}>
                    {entry.summary}
                  </td>
                  <td className={`whitespace-nowrap px-3 py-2.5 text-right font-mono text-xs ${pnlColor}`}>
                    {formatPnl(entry.realizedPnl)}
                  </td>
                  <td className="px-3 py-2.5 text-center">
                    {entry.notes.length > 0 ? (
                      <span className="inline-flex items-center gap-0.5 rounded-full bg-gray-700 px-1.5 py-0.5 text-xs text-gray-300">
                        {entry.notes.length}
                      </span>
                    ) : (
                      <span className="text-gray-700">-</span>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {/* 페이지네이션 */}
      {totalPages > 1 && (
        <div className="mt-3 flex items-center justify-between text-sm">
          <span className="text-gray-500">
            {page + 1} / {totalPages} 페이지
          </span>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => onPageChange(page - 1)}
              disabled={page === 0}
              className="rounded-md border border-gray-700 px-3 py-1 text-gray-400 transition-colors hover:border-gray-600 hover:text-gray-200 disabled:opacity-40"
            >
              이전
            </button>
            <button
              type="button"
              onClick={() => onPageChange(page + 1)}
              disabled={page >= totalPages - 1}
              className="rounded-md border border-gray-700 px-3 py-1 text-gray-400 transition-colors hover:border-gray-600 hover:text-gray-200 disabled:opacity-40"
            >
              다음
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
