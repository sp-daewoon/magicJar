/**
 * StrategyFilterBar — 전략 목록 필터 바 (T-SIG Phase 3 — ADR 0066)
 *
 * - status 필터 탭 (전체·ACTIVE·BUY_BLOCKED·PAUSED·DISABLED)
 * - 검색 입력 (name/nameKo/code 부분일치 — 호출부에서 300ms debounce 후 서버 q 전달)
 * - 정렬 드롭다운 (최근순·이름순·상태순 — 서버 sort)
 * - 키보드 네비게이션 (ArrowLeft/ArrowRight)
 *
 * 검색·정렬 props는 optional — 미지정 시 상태 탭만 렌더(하위호환).
 */
import { useRef } from 'react'
import { type StrategyListSort, type StrategyStatus } from '../../api/strategy'

export type StrategyFilterOption = StrategyStatus | 'ALL'

interface StrategyFilterBarProps {
  value: StrategyFilterOption
  onChange: (v: StrategyFilterOption) => void
  /** 검색어 (raw 입력값) — 지정 시 검색 입력 렌더 */
  search?: string
  onSearchChange?: (q: string) => void
  /** 정렬 키 — 지정 시 정렬 드롭다운 렌더 */
  sort?: StrategyListSort
  onSortChange?: (s: StrategyListSort) => void
}

const OPTIONS: { value: StrategyFilterOption; label: string }[] = [
  { value: 'ALL',      label: '전체' },
  { value: 'ACTIVE',   label: '자동매매중' },
  { value: 'BUY_BLOCKED', label: '매수차단' },
  { value: 'PAUSED',   label: '중지' },
  { value: 'DISABLED', label: '보호중지' },
]

const SORT_OPTIONS: { value: StrategyListSort; label: string }[] = [
  { value: 'recent', label: '최근순' },
  { value: 'name',   label: '이름순' },
  { value: 'status', label: '상태순' },
]

const TAB_ACTIVE =
  'bg-blue-600 text-white'
const TAB_DEFAULT =
  'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200'

export function StrategyFilterBar({
  value,
  onChange,
  search,
  onSearchChange,
  sort,
  onSortChange,
}: StrategyFilterBarProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const showSearch = onSearchChange !== undefined
  const showSort = onSortChange !== undefined

  function handleKeyDown(e: React.KeyboardEvent, currentIdx: number) {
    let nextIdx = currentIdx
    if (e.key === 'ArrowRight') {
      nextIdx = (currentIdx + 1) % OPTIONS.length
      e.preventDefault()
    } else if (e.key === 'ArrowLeft') {
      nextIdx = (currentIdx - 1 + OPTIONS.length) % OPTIONS.length
      e.preventDefault()
    } else {
      return
    }
    const buttons = containerRef.current?.querySelectorAll('button')
    buttons?.[nextIdx]?.focus()
    onChange(OPTIONS[nextIdx].value)
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      {showSearch && (
        <div className="relative">
          <svg
            className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-500"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={2}
            aria-hidden
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-4.35-4.35M17 11a6 6 0 11-12 0 6 6 0 0112 0z" />
          </svg>
          <input
            type="search"
            value={search ?? ''}
            onChange={(e) => onSearchChange?.(e.target.value)}
            placeholder="이름·종목 코드 검색"
            aria-label="전략 검색"
            className="w-48 rounded-lg border border-gray-700 bg-gray-900 py-1.5 pl-8 pr-2 text-sm text-gray-200 placeholder:text-gray-500 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 sm:w-56"
          />
        </div>
      )}

      <div
        ref={containerRef}
        role="tablist"
        aria-label="전략 상태 필터"
        className="flex items-center gap-1 rounded-lg bg-gray-900 p-1"
      >
        {OPTIONS.map((opt, idx) => (
          <button
            key={opt.value}
            role="tab"
            aria-selected={value === opt.value}
            tabIndex={value === opt.value ? 0 : -1}
            onClick={() => onChange(opt.value)}
            onKeyDown={(e) => handleKeyDown(e, idx)}
            className={[
              'rounded-md px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900',
              value === opt.value ? TAB_ACTIVE : TAB_DEFAULT,
            ].join(' ')}
          >
            {opt.label}
          </button>
        ))}
      </div>

      {showSort && (
        <label className="flex items-center gap-1.5 text-xs text-gray-400">
          <span className="sr-only">정렬</span>
          <select
            value={sort ?? 'recent'}
            onChange={(e) => onSortChange?.(e.target.value as StrategyListSort)}
            aria-label="전략 정렬"
            className="rounded-lg border border-gray-700 bg-gray-900 py-1.5 pl-2 pr-7 text-sm text-gray-200 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          >
            {SORT_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </label>
      )}
    </div>
  )
}
