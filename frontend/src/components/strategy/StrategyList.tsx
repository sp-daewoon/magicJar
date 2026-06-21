/**
 * StrategyList — 전략 카드 그리드 + 무한 스크롤 (T-SIG Phase 3 — ADR 0066)
 *
 * - useSigStrategies(size=20, {q, sort}) 무한 쿼리 (검색·정렬 서버 위임)
 * - 빈 목록: "전략을 등록하고 시그널을 받으세요" 안내
 * - Intersection Observer 기반 자동 더 불러오기
 * - 다중 선택 + 일괄 시작/중지 (client-side 루프, 신규 백엔드 0)
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useBulkToggleStatus, useSigStrategies } from '../../api/hooks/useSigStrategy'
import { type StrategyListQuery, type StrategyStatus } from '../../api/strategy'
import { useToast } from '../ui/ToastProvider'
import { type StrategyFilterOption } from './StrategyFilterBar'
import { StrategyCard } from './StrategyCard'

const FILTER_EMPTY_LABEL: Record<StrategyStatus, string> = {
  ACTIVE: '자동매매중',
  BUY_BLOCKED: '매수차단',
  PAUSED: '중지',
  DISABLED: '보호중지',
  DELETED: '삭제됨',
}

interface StrategyListProps {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  filter: StrategyFilterOption
  /** 검색·정렬 (서버 위임) */
  query?: StrategyListQuery
  /** 즐겨찾기 전략 id 집합 (시작 화면 favoriteIds 권위) */
  favoriteIds?: Set<number>
}

export function StrategyList({ userId, filter, query, favoriteIds }: StrategyListProps) {
  const {
    data,
    isLoading,
    isError,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useSigStrategies(userId, 20, query)
  const { showToast } = useToast()
  const bulkToggle = useBulkToggleStatus()

  // 다중 선택 상태 (id 집합)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [selectMode, setSelectMode] = useState(false)

  // Intersection Observer — 뷰포트 끝 도달 시 다음 페이지 로드
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

  // ── 전체 전략 + 필터 적용 ──
  const allStrategies = useMemo(
    () => data?.pages.flatMap((p) => p.content) ?? [],
    [data],
  )

  const filtered = useMemo(
    () =>
      filter === 'ALL'
        ? allStrategies.filter((s) => s.status !== 'DELETED')
        : allStrategies.filter((s) => s.status === (filter as StrategyStatus)),
    [allStrategies, filter],
  )

  // 선택된 항목이 현 목록에서 사라지면 정리 (필터·검색 변경 시)
  const visibleIds = useMemo(() => new Set(filtered.map((s) => s.id)), [filtered])
  useEffect(() => {
    setSelected((prev) => {
      const next = new Set([...prev].filter((id) => visibleIds.has(id)))
      return next.size === prev.size ? prev : next
    })
  }, [visibleIds])

  function toggleSelect(id: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function selectAllVisible() {
    setSelected(new Set(filtered.map((s) => s.id)))
  }

  function clearSelection() {
    setSelected(new Set())
  }

  function handleBulk(status: 'ACTIVE' | 'PAUSED') {
    const ids = [...selected]
    if (ids.length === 0) return
    bulkToggle.mutate(
      { ids, status },
      {
        onSuccess: (results) => {
          const okCount = results.filter((r) => r.ok).length
          const failCount = results.length - okCount
          const verb = status === 'ACTIVE' ? '시작' : '중지'
          if (failCount === 0) {
            showToast(`${okCount}개 전략을 ${verb}했습니다.`, 'success')
          } else if (okCount === 0) {
            showToast(`${failCount}개 전략 ${verb} 실패. 개별 카드에서 확인해 주세요.`, 'error')
          } else {
            showToast(`${okCount}개 ${verb}, ${failCount}개 실패(부분 적용).`, 'error')
          }
          clearSelection()
        },
        onError: () => {
          showToast('일괄 처리 중 오류가 발생했습니다.', 'error')
        },
      },
    )
  }

  // ── 로딩 스켈레톤 ──
  if (isLoading) {
    return (
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: 6 }).map((_, i) => (
          <div
            key={i}
            className="h-44 animate-pulse rounded-xl bg-gray-800/60"
          />
        ))}
      </div>
    )
  }

  // ── 에러 ──
  if (isError) {
    return (
      <div className="rounded-lg border border-red-900 bg-red-950/30 p-6 text-center">
        <p className="text-sm text-red-400">전략 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.</p>
      </div>
    )
  }

  // ── 빈 목록 ──
  if (filtered.length === 0) {
    const isSearching = !!query?.q?.trim()
    return (
      <div className="flex flex-col items-center gap-4 rounded-xl border border-dashed border-gray-700 bg-gray-900/40 py-16 text-center">
        <p className="text-sm text-gray-400">
          {isSearching
            ? `"${query?.q?.trim()}" 검색 결과가 없습니다.`
            : filter === 'ALL'
              ? '등록된 전략이 없습니다.'
              : `${FILTER_EMPTY_LABEL[filter as StrategyStatus]} 상태의 전략이 없습니다.`}
        </p>
        <p className="text-xs text-gray-500">전략을 등록하고 실시간 시그널을 받아보세요.</p>
        <Link
          to="/strategy/builder"
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950"
        >
          전략 빌더 열기
        </Link>
      </div>
    )
  }

  return (
    <div>
      {/* 일괄 액션 툴바 */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => {
            setSelectMode((prev) => !prev)
            if (selectMode) clearSelection()
          }}
          className={[
            'rounded-lg px-3 py-1.5 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950',
            selectMode ? 'bg-blue-600 text-white' : 'bg-gray-800 text-gray-300 hover:bg-gray-700',
          ].join(' ')}
          aria-pressed={selectMode}
        >
          {selectMode ? '선택 종료' : '여러 개 선택'}
        </button>

        {selectMode && (
          <>
            <span className="text-xs text-gray-400" aria-live="polite">
              {selected.size}개 선택
            </span>
            <button
              type="button"
              onClick={selectAllVisible}
              className="rounded-lg bg-gray-800 px-3 py-1.5 text-xs font-medium text-gray-300 transition-colors hover:bg-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950"
            >
              전체 선택
            </button>
            <button
              type="button"
              disabled={selected.size === 0 || bulkToggle.isPending}
              onClick={() => handleBulk('ACTIVE')}
              className="rounded-lg bg-emerald-700 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-emerald-600 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-emerald-300 focus:ring-offset-1 focus:ring-offset-gray-950"
            >
              {bulkToggle.isPending ? '처리 중...' : '일괄 시작'}
            </button>
            <button
              type="button"
              disabled={selected.size === 0 || bulkToggle.isPending}
              onClick={() => handleBulk('PAUSED')}
              className="rounded-lg bg-amber-700 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-amber-600 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-amber-300 focus:ring-offset-1 focus:ring-offset-gray-950"
            >
              일괄 중지
            </button>
          </>
        )}
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {filtered.map((s) => (
          <StrategyCard
            key={s.id}
            strategy={s}
            selectMode={selectMode}
            selected={selected.has(s.id)}
            onToggleSelect={() => toggleSelect(s.id)}
            isFavorite={favoriteIds?.has(Number(s.id)) ?? false}
          />
        ))}
      </div>

      {/* 무한 스크롤 sentinel */}
      <div ref={sentinelRef} className="h-4" />
      {isFetchingNextPage && (
        <p className="mt-4 text-center text-xs text-gray-500">불러오는 중...</p>
      )}
    </div>
  )
}
