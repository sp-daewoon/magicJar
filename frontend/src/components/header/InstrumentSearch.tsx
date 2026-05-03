/**
 * InstrumentSearch — 헤더 종목 검색창 (자동완성 드롭다운).
 * T20-T6
 *
 * - debounce 300ms (useInstrumentSearch 훅 내부 처리)
 * - dropdown: custom div (shadcn Popover 미설치 시 fallback)
 * - 키보드 ↑↓·Enter·Esc 완전 지원
 * - 종목 선택 → navigate('/symbol/{code}')
 * - 모든 페이지 헤더에서 렌더링 (RootLayout)
 */
import { useRef, useState, useCallback, useId } from 'react'
import { useNavigate } from 'react-router-dom'
import { useInstrumentSearch } from '@/hooks/useInstrumentSearch'
import { cn } from '@/lib/cn'
import type { SearchResult } from '@/api/types'

const MARKET_COLOR: Record<string, string> = {
  KOSPI: 'text-blue-600',
  KOSDAQ: 'text-purple-600',
  KONEX: 'text-gray-500',
}

export default function InstrumentSearch() {
  const navigate = useNavigate()
  const listId = useId()
  const inputRef = useRef<HTMLInputElement>(null)
  const [open, setOpen] = useState(false)
  const [focusedIdx, setFocusedIdx] = useState(-1)

  const { results, isLoading, query, setQuery, debouncedQuery } = useInstrumentSearch()

  const showDropdown = open && (isLoading || results.length > 0 || debouncedQuery.length >= 1)

  const handleSelect = useCallback(
    (item: SearchResult) => {
      setQuery('')
      setOpen(false)
      setFocusedIdx(-1)
      inputRef.current?.blur()
      navigate(`/symbol/${item.code}`)
    },
    [navigate, setQuery],
  )

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (!showDropdown) return

    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setFocusedIdx((prev) => Math.min(prev + 1, results.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setFocusedIdx((prev) => Math.max(prev - 1, -1))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (focusedIdx >= 0 && focusedIdx < results.length) {
        handleSelect(results[focusedIdx])
      }
    } else if (e.key === 'Escape') {
      setOpen(false)
      setFocusedIdx(-1)
      inputRef.current?.blur()
    }
  }

  return (
    <div className="relative" role="combobox" aria-expanded={showDropdown} aria-haspopup="listbox">
      <div className="relative flex items-center">
        {/* 검색 아이콘 */}
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="pointer-events-none absolute left-2.5 text-muted-foreground"
          aria-hidden="true"
        >
          <circle cx="11" cy="11" r="8" />
          <path d="m21 21-4.35-4.35" />
        </svg>
        <input
          ref={inputRef}
          type="search"
          role="searchbox"
          autoComplete="off"
          placeholder="종목 검색 (이름·코드)"
          aria-label="종목 검색"
          aria-autocomplete="list"
          aria-controls={listId}
          aria-activedescendant={focusedIdx >= 0 ? `${listId}-item-${focusedIdx}` : undefined}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value)
            setOpen(true)
            setFocusedIdx(-1)
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => {
            // 드롭다운 클릭 시 onBlur보다 onMouseDown이 먼저 → setTimeout으로 딜레이
            setTimeout(() => {
              setOpen(false)
              setFocusedIdx(-1)
            }, 150)
          }}
          onKeyDown={handleKeyDown}
          className={cn(
            'h-8 w-48 rounded-md border bg-background pl-8 pr-3 text-sm',
            'placeholder:text-muted-foreground',
            'focus:outline-none focus:ring-2 focus:ring-primary/30',
            'transition-[width]',
            open && 'w-56',
          )}
        />
        {/* 로딩 스피너 */}
        {isLoading && debouncedQuery.length >= 1 && (
          <span
            aria-label="검색 중"
            className="pointer-events-none absolute right-2.5 h-3.5 w-3.5 animate-spin rounded-full border-2 border-primary border-t-transparent"
          />
        )}
      </div>

      {/* 드롭다운 */}
      {showDropdown && (
        <ul
          id={listId}
          role="listbox"
          aria-label="검색 결과"
          className={cn(
            'absolute left-0 top-full z-50 mt-1 max-h-64 min-w-[200px] w-full overflow-y-auto',
            'rounded-md border bg-popover shadow-md',
          )}
        >
          {results.length === 0 && !isLoading && debouncedQuery.length >= 1 && (
            <li className="px-3 py-2 text-sm text-muted-foreground">
              검색 결과 없음
            </li>
          )}
          {results.map((item, idx) => (
            <li
              key={item.code}
              id={`${listId}-item-${idx}`}
              role="option"
              aria-selected={idx === focusedIdx}
              onMouseDown={(e) => {
                // onBlur보다 먼저 실행되도록 mousedown에서 처리
                e.preventDefault()
                handleSelect(item)
              }}
              className={cn(
                'flex cursor-pointer items-center justify-between gap-2 px-3 py-2 text-sm',
                'hover:bg-accent hover:text-accent-foreground',
                idx === focusedIdx && 'bg-accent text-accent-foreground',
              )}
            >
              <span className="flex flex-col">
                <span className="font-medium">{item.name}</span>
                <span className="text-xs text-muted-foreground">{item.code}</span>
              </span>
              <span className={cn('text-xs font-medium', MARKET_COLOR[item.market] ?? 'text-gray-500')}>
                {item.market}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
