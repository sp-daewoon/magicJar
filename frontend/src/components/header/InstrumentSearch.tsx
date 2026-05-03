/**
 * InstrumentSearch — 헤더 종목 검색창 (자동완성 드롭다운).
 *
 * - debounce 300ms (useInstrumentSearch 훅 내부 처리)
 * - dropdown: custom div (shadcn Popover 미설치 시 fallback)
 * - 키보드 ↑↓·Enter·Esc 완전 지원
 * - Enter 키 자동 진입:
 *   - 결과 1건: 즉시 navigate
 *   - 결과 다수 + 선택: 선택 항목 navigate
 *   - 결과 다수 + 선택 없음: 첫 결과 navigate
 * - 검색 기록 LRU 20개:
 *   - 포커스 시 (query 0건): 기록 dropdown 노출
 *   - 종목 선택 시 localStorage에 추가
 *   - 항목 X 버튼으로 단건 삭제
 *   - "검색 기록 모두 삭제" 옵션
 */
import { useRef, useState, useCallback, useId } from 'react'
import { useNavigate } from 'react-router-dom'
import { useInstrumentSearch } from '@/hooks/useInstrumentSearch'
import { useSearchHistory } from '@/hooks/useSearchHistory'
import type { SearchHistoryItem } from '@/hooks/useSearchHistory'
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
  const historyListId = useId()
  const inputRef = useRef<HTMLInputElement>(null)
  const [open, setOpen] = useState(false)
  const [focusedIdx, setFocusedIdx] = useState(-1)

  const { results, isLoading, query, setQuery, debouncedQuery } = useInstrumentSearch()
  const { history, addToHistory, removeFromHistory, clearHistory } = useSearchHistory()

  // 검색 결과 dropdown: query >= 1 + loading or 결과 존재
  const showResultDropdown = open && (isLoading || results.length > 0 || debouncedQuery.length >= 1)
  // 검색 기록 dropdown: query = 0 + 기록 존재 + 포커스
  const showHistoryDropdown = open && query.length === 0 && history.length > 0

  const showDropdown = showResultDropdown || showHistoryDropdown

  const handleSelect = useCallback(
    (item: SearchResult | SearchHistoryItem) => {
      setQuery('')
      setOpen(false)
      setFocusedIdx(-1)
      inputRef.current?.blur()
      addToHistory({ code: item.code, name: item.name, market: item.market })
      navigate(`/symbol/${item.code}`)
    },
    [navigate, setQuery, addToHistory],
  )

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    // 기록 dropdown 키보드 네비게이션
    if (showHistoryDropdown && !showResultDropdown) {
      if (e.key === 'ArrowDown') {
        e.preventDefault()
        setFocusedIdx((prev) => Math.min(prev + 1, history.length - 1))
      } else if (e.key === 'ArrowUp') {
        e.preventDefault()
        setFocusedIdx((prev) => Math.max(prev - 1, -1))
      } else if (e.key === 'Enter') {
        e.preventDefault()
        const idx = focusedIdx >= 0 ? focusedIdx : 0
        if (history[idx]) {
          handleSelect(history[idx])
        }
      } else if (e.key === 'Escape') {
        setOpen(false)
        setFocusedIdx(-1)
        inputRef.current?.blur()
      }
      return
    }

    if (!showResultDropdown) return

    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setFocusedIdx((prev) => Math.min(prev + 1, results.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setFocusedIdx((prev) => Math.max(prev - 1, -1))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (results.length === 0) return

      if (results.length === 1) {
        // 결과 1건: 즉시 navigate
        handleSelect(results[0])
      } else if (focusedIdx >= 0 && focusedIdx < results.length) {
        // 선택된 항목 있음: 그 항목으로 navigate
        handleSelect(results[focusedIdx])
      } else {
        // 선택 없음: 첫 결과로 navigate
        handleSelect(results[0])
      }
    } else if (e.key === 'Escape') {
      setOpen(false)
      setFocusedIdx(-1)
      inputRef.current?.blur()
    }
  }

  return (
    <div
      className="relative"
      role="combobox"
      aria-expanded={showDropdown}
      aria-haspopup="listbox"
    >
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
          aria-controls={showHistoryDropdown ? historyListId : listId}
          aria-activedescendant={
            focusedIdx >= 0
              ? `${showHistoryDropdown ? historyListId : listId}-item-${focusedIdx}`
              : undefined
          }
          value={query}
          onChange={(e) => {
            setQuery(e.target.value)
            setOpen(true)
            setFocusedIdx(-1)
          }}
          onFocus={() => {
            setOpen(true)
            setFocusedIdx(-1)
          }}
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

      {/* 검색 결과 드롭다운 */}
      {showResultDropdown && (
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
            <li className="px-3 py-2 text-sm text-muted-foreground">검색 결과 없음</li>
          )}
          {results.map((item, idx) => (
            <li
              key={item.code}
              id={`${listId}-item-${idx}`}
              role="option"
              aria-selected={idx === focusedIdx}
              onMouseDown={(e) => {
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
              <span
                className={cn(
                  'text-xs font-medium',
                  MARKET_COLOR[item.market] ?? 'text-gray-500',
                )}
              >
                {item.market}
              </span>
            </li>
          ))}
        </ul>
      )}

      {/* 검색 기록 드롭다운 (query 0건 + 기록 있음) */}
      {showHistoryDropdown && !showResultDropdown && (
        <ul
          id={historyListId}
          role="listbox"
          aria-label="검색 기록"
          className={cn(
            'absolute left-0 top-full z-50 mt-1 max-h-80 min-w-[220px] w-full overflow-y-auto',
            'rounded-md border bg-popover shadow-md',
          )}
        >
          {/* 헤더 */}
          <li className="flex items-center justify-between px-3 py-1.5 border-b">
            <span className="text-[11px] font-semibold text-muted-foreground uppercase tracking-wide">
              최근 검색
            </span>
            <button
              type="button"
              onMouseDown={(e) => {
                e.preventDefault()
                clearHistory()
              }}
              className="text-[11px] text-muted-foreground hover:text-destructive transition-colors"
              aria-label="검색 기록 모두 삭제"
            >
              모두 삭제
            </button>
          </li>

          {history.map((item, idx) => (
            <li
              key={item.code}
              id={`${historyListId}-item-${idx}`}
              role="option"
              aria-selected={idx === focusedIdx}
              className={cn(
                'flex cursor-pointer items-center justify-between gap-2 px-3 py-2 text-sm',
                'hover:bg-accent hover:text-accent-foreground group',
                idx === focusedIdx && 'bg-accent text-accent-foreground',
              )}
            >
              {/* 종목 정보 영역 — 클릭 시 navigate */}
              <span
                className="flex flex-1 flex-col min-w-0"
                onMouseDown={(e) => {
                  e.preventDefault()
                  handleSelect(item)
                }}
              >
                <span className="font-medium truncate">{item.name}</span>
                <span className="text-xs text-muted-foreground">{item.code}</span>
              </span>
              <span
                className={cn(
                  'text-xs font-medium mr-1 shrink-0',
                  MARKET_COLOR[item.market] ?? 'text-gray-500',
                )}
              >
                {item.market}
              </span>
              {/* X 버튼 — 단건 삭제 */}
              <button
                type="button"
                onMouseDown={(e) => {
                  e.preventDefault()
                  e.stopPropagation()
                  removeFromHistory(item.code)
                }}
                className="shrink-0 p-0.5 rounded opacity-0 group-hover:opacity-100 hover:bg-destructive/20 transition-opacity"
                aria-label={`${item.name} 검색 기록 삭제`}
              >
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  width="12"
                  height="12"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <path d="M18 6 6 18" />
                  <path d="m6 6 12 12" />
                </svg>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
