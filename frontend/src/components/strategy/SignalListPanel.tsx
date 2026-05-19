/**
 * SignalListPanel — 사용자 시그널 시계열 패널 (T-SIG Phase 3 — ADR 0066)
 *
 * - useSignals(params) 무한 쿼리
 * - 실시간 prepend: useStompSignal signalQueue 수신 시 목록 상단 표시
 * - 필터: code·interval·side
 * - 빈 목록: "활성 전략 시그널이 아직 발생하지 않았습니다"
 * - feedback_no_mock_data 정합
 */
import { useEffect, useRef, useState } from 'react'
import { useSignals } from '../../api/hooks/useSignal'
import { type SignalDto, type SignalSide } from '../../api/signal'
import { SignalCard } from './SignalCard'

// ──────────────────────────────────────────────
// 필터 UI
// ──────────────────────────────────────────────

interface FilterState {
  code: string
  interval: string
  side: SignalSide | 'ALL'
}

const SIDE_OPTIONS: { value: FilterState['side']; label: string }[] = [
  { value: 'ALL',  label: '전체' },
  { value: 'BUY',  label: '매수' },
  { value: 'SELL', label: '매도' },
  { value: 'HOLD', label: '홀드' },
]

const INTERVAL_OPTIONS = ['', '1m', '5m', '15m', '30m', '1h', '1d']

// ──────────────────────────────────────────────
// 컴포넌트
// ──────────────────────────────────────────────

interface SignalListPanelProps {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  /** 실시간 prepend — useStompSignal signalQueue */
  prependSignals?: SignalDto[]
}

export function SignalListPanel({ userId, prependSignals = [] }: SignalListPanelProps) {
  const [filter, setFilter] = useState<FilterState>({ code: '', interval: '', side: 'ALL' })
  const [newIds, setNewIds] = useState<Set<string>>(new Set())

  // 새 시그널 하이라이트 — 3초 후 해제
  useEffect(() => {
    if (prependSignals.length === 0) return
    const ids = new Set(prependSignals.map((s) => s.id))
    setNewIds((prev) => new Set([...prev, ...ids]))
    const t = setTimeout(() => {
      setNewIds((prev) => {
        const next = new Set(prev)
        ids.forEach((id) => next.delete(id))
        return next
      })
    }, 3000)
    return () => clearTimeout(t)
  }, [prependSignals])

  const queryParams = {
    userId,
    code: filter.code || undefined,
    interval: filter.interval || undefined,
    side: filter.side === 'ALL' ? undefined : filter.side,
  }

  const {
    data,
    isLoading,
    isError,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useSignals(queryParams, 20)

  // Intersection Observer
  const sentinelRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = sentinelRef.current
    if (!el) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && hasNextPage && !isFetchingNextPage) {
          void fetchNextPage()
        }
      },
      { rootMargin: '200px' },
    )
    observer.observe(el)
    return () => observer.disconnect()
  }, [fetchNextPage, hasNextPage, isFetchingNextPage])

  const restSignals = data?.pages.flatMap((p) => p.content) ?? []

  // prepend 중복 제거 (REST와 겹칠 경우)
  const restIds = new Set(restSignals.map((s) => s.id))
  const uniquePrepend = prependSignals.filter((s) => !restIds.has(s.id))
  const allSignals = [...uniquePrepend, ...restSignals]

  return (
    <section aria-labelledby="signal-list-heading" className="flex flex-col gap-4">
      {/* 섹션 헤더 */}
      <div className="flex items-center justify-between">
        <h2 id="signal-list-heading" className="text-base font-semibold text-white">
          시그널 내역
        </h2>
        <span className="text-xs text-gray-500">
          {data?.pages[0]?.totalElements !== undefined
            ? `총 ${data.pages[0].totalElements.toLocaleString()}건`
            : null}
        </span>
      </div>

      {/* 필터 바 */}
      <div className="flex flex-wrap items-center gap-3">
        {/* 종목 코드 검색 */}
        <input
          type="text"
          value={filter.code}
          onChange={(e) => setFilter((f) => ({ ...f, code: e.target.value.toUpperCase() }))}
          placeholder="종목 코드"
          maxLength={6}
          className="w-24 rounded-lg border border-gray-700 bg-gray-800 px-3 py-1.5 text-sm text-white placeholder-gray-500 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
        />

        {/* 인터벌 선택 */}
        <select
          value={filter.interval}
          onChange={(e) => setFilter((f) => ({ ...f, interval: e.target.value }))}
          className="rounded-lg border border-gray-700 bg-gray-800 px-3 py-1.5 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
          aria-label="인터벌 필터"
        >
          {INTERVAL_OPTIONS.map((iv) => (
            <option key={iv} value={iv}>
              {iv === '' ? '전체 인터벌' : iv}
            </option>
          ))}
        </select>

        {/* 매수/매도 탭 */}
        <div
          role="tablist"
          aria-label="매매 방향 필터"
          className="flex items-center rounded-lg bg-gray-900 p-1"
        >
          {SIDE_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              role="tab"
              aria-selected={filter.side === opt.value}
              onClick={() => setFilter((f) => ({ ...f, side: opt.value }))}
              className={[
                'rounded-md px-3 py-1 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
                filter.side === opt.value
                  ? 'bg-blue-600 text-white'
                  : 'text-gray-400 hover:text-gray-200',
              ].join(' ')}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* 목록 */}
      {isLoading ? (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="h-16 animate-pulse rounded-lg bg-gray-800/60" />
          ))}
        </div>
      ) : isError ? (
        <div className="rounded-lg border border-red-900 bg-red-950/30 p-6 text-center">
          <p className="text-sm text-red-400">시그널 목록을 불러오지 못했습니다.</p>
        </div>
      ) : allSignals.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-gray-700 bg-gray-900/40 py-12 text-center">
          <p className="text-sm text-gray-400">
            활성 전략 시그널이 아직 발생하지 않았습니다.
          </p>
          <p className="text-xs text-gray-500">전략을 활성화하면 시그널이 여기에 표시됩니다.</p>
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          {allSignals.map((signal) => (
            <SignalCard
              key={signal.id}
              signal={signal}
              isNew={newIds.has(signal.id)}
            />
          ))}
          <div ref={sentinelRef} className="h-4" />
          {isFetchingNextPage && (
            <p className="text-center text-xs text-gray-500">불러오는 중...</p>
          )}
        </div>
      )}
    </section>
  )
}
