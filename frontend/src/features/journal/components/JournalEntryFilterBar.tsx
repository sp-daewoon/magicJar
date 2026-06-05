/**
 * JournalEntryFilterBar — 매매일지 필터 바
 *
 * ADR 0072 §8 + spec §3-3 Phase 3 정합:
 * - 기간 picker (from · to ISO date string)
 * - 종목명/코드 자동완성 검색
 */
import { InstrumentSearchInput } from '../../../components/InstrumentSearchInput'
import type { InstrumentSearchResultDto } from '../../../api/instrument'
import type { JournalEventType } from '../api/journalApi'

export const EVENT_TYPE_LABELS: Record<JournalEventType, string> = {
  ORDER_FILLED: '체결',
}

export interface JournalEntryFilterState {
  from: string
  to: string
  instrumentCode: string
  instrumentInput: string
}

interface JournalEntryFilterBarProps {
  filter: JournalEntryFilterState
  onChange: (next: JournalEntryFilterState) => void
  onReset: () => void
}

export function JournalEntryFilterBar({ filter, onChange, onReset }: JournalEntryFilterBarProps) {
  const inputClass =
    'rounded-md border border-gray-700 bg-gray-900 px-3 py-1.5 text-sm text-gray-200 placeholder:text-gray-500 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500'

  function handleInstrumentRawChange(value: string) {
    onChange({
      ...filter,
      instrumentInput: value,
      instrumentCode: extractInstrumentCode(value),
    })
  }

  function handleInstrumentSelect(result: InstrumentSearchResultDto) {
    onChange({
      ...filter,
      instrumentCode: result.code,
      instrumentInput: formatInstrumentDisplay(result),
    })
  }

  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-800 bg-gray-900/60 p-3">
      {/* 기간 시작 */}
      <label className="flex flex-col gap-1">
        <span className="text-xs font-medium text-gray-400">기간 시작</span>
        <input
          type="date"
          value={filter.from}
          onChange={(e) => onChange({ ...filter, from: e.target.value })}
          className={inputClass}
        />
      </label>

      {/* 기간 종료 */}
      <label className="flex flex-col gap-1">
        <span className="text-xs font-medium text-gray-400">기간 종료</span>
        <input
          type="date"
          value={filter.to}
          onChange={(e) => onChange({ ...filter, to: e.target.value })}
          className={inputClass}
        />
      </label>

      {/* 종목명/코드 */}
      <label className="flex w-60 flex-col gap-1">
        <span className="text-xs font-medium text-gray-400">종목</span>
        <InstrumentSearchInput
          value={filter.instrumentInput}
          onRawChange={handleInstrumentRawChange}
          onSelect={handleInstrumentSelect}
          placeholder="종목명 또는 코드 검색"
        />
      </label>

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

function formatInstrumentDisplay(result: InstrumentSearchResultDto): string {
  return `${result.name} (${result.code})`
}

function extractInstrumentCode(value: string): string {
  const trimmed = value.trim()
  const labelMatch = trimmed.match(/\((\d{6})\)\s*$/)
  if (labelMatch) return labelMatch[1]
  return /^\d{6}$/.test(trimmed) ? trimmed : ''
}
