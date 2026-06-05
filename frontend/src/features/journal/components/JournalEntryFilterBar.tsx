/**
 * JournalEntryFilterBar — 매매일지 필터 바
 *
 * ADR 0072 §8 + spec §3-3 Phase 3 정합:
 * - 기간 picker (from · to ISO date string)
 * - 종목 코드 검색
 */
import type { JournalEventType } from '../api/journalApi'

export const EVENT_TYPE_LABELS: Record<JournalEventType, string> = {
  ORDER_FILLED: '체결',
}

interface FilterState {
  from: string
  to: string
  instrumentCode: string
}

interface JournalEntryFilterBarProps {
  filter: FilterState
  onChange: (next: FilterState) => void
  onReset: () => void
}

export function JournalEntryFilterBar({ filter, onChange, onReset }: JournalEntryFilterBarProps) {
  const inputClass =
    'rounded-md border border-gray-700 bg-gray-900 px-3 py-1.5 text-sm text-gray-200 placeholder:text-gray-500 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500'

  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-800 bg-gray-900/60 p-3">
      {/* 기간 시작 */}
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-gray-400">기간 시작</label>
        <input
          type="date"
          value={filter.from}
          onChange={(e) => onChange({ ...filter, from: e.target.value })}
          className={inputClass}
        />
      </div>

      {/* 기간 종료 */}
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-gray-400">기간 종료</label>
        <input
          type="date"
          value={filter.to}
          onChange={(e) => onChange({ ...filter, to: e.target.value })}
          className={inputClass}
        />
      </div>

      {/* 종목 코드 */}
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-gray-400">종목</label>
        <input
          type="text"
          placeholder="예: 005930"
          value={filter.instrumentCode}
          onChange={(e) => onChange({ ...filter, instrumentCode: e.target.value })}
          className={`${inputClass} w-28`}
          maxLength={12}
        />
      </div>

      {/* 초기화 */}
      <button
        type="button"
        onClick={onReset}
        className="rounded-md border border-gray-700 bg-gray-800 px-3 py-1.5 text-sm text-gray-400 transition-colors hover:border-gray-600 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-400"
      >
        초기화
      </button>
    </div>
  )
}
