/**
 * DisclosurePanel — 종목 상세 sidebar 공시 패널
 *
 * ADR 0075 §8-5 Phase 3 산출물 + spec §2-3 핵심 결정 LOCK:
 *   - useSymbolDisclosures hook (refetchInterval 60s)
 *   - STOMP 0 LOCK (§6 사용자 결정 6-1)
 *   - 정정공시 빨간 배지 강조 (isCorrection=true)
 *   - onlyCorrection 필터 토글 버튼
 *   - 빈 상태: "최근 7일간 공시가 없습니다" (feedback_no_mock_data 정합)
 *   - AI provider import 0 (§11-2)
 *   - DART URL 새 탭 open (target="_blank" rel="noopener noreferrer")
 */
import { useState } from 'react'
import { useSymbolDisclosures } from '../hooks/useSymbolDisclosures'
import { DisclosureItemRow } from './DisclosureItem'

interface Props {
  code: string
}

export function DisclosurePanel({ code }: Props) {
  const [page, setPage] = useState(0)
  const [onlyCorrection, setOnlyCorrection] = useState(false)

  const { data, isLoading, isError } = useSymbolDisclosures(code, {
    page,
    size: 20,
    onlyCorrection,
  })

  // onlyCorrection 필터 토글 시 페이지 초기화
  function handleToggleOnlyCorrection() {
    setOnlyCorrection((prev) => !prev)
    setPage(0)
  }

  // ── 로딩 스켈레톤
  if (isLoading) {
    return (
      <section aria-label="공시" className="rounded-lg border border-gray-800 bg-gray-900 p-4">
        <h3 className="mb-3 text-sm font-semibold text-gray-300">공시</h3>
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="space-y-1 rounded bg-gray-800 p-3 animate-pulse">
              <div className="h-3 w-4/5 rounded bg-gray-700" />
              <div className="h-2 w-2/5 rounded bg-gray-700" />
            </div>
          ))}
        </div>
      </section>
    )
  }

  // ── REST 실패
  if (isError) {
    return (
      <section aria-label="공시" className="rounded-lg border border-gray-800 bg-gray-900 p-4">
        <h3 className="mb-3 text-sm font-semibold text-gray-300">공시</h3>
        <p className="text-xs text-red-400">공시를 불러오지 못했습니다.</p>
      </section>
    )
  }

  const items = data?.items ?? []
  const totalPages = data?.totalPages ?? 0
  const hasMore = page < totalPages - 1

  return (
    <section aria-label="공시" className="rounded-lg border border-gray-800 bg-gray-900 p-4">
      {/* 헤더 + onlyCorrection 필터 */}
      <div className="mb-3 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-gray-300">공시</h3>
          {data && data.totalElements > 0 && (
            <span className="text-xs text-gray-500">{data.totalElements}건</span>
          )}
        </div>

        {/* 정정공시만 보기 토글 */}
        <button
          type="button"
          onClick={handleToggleOnlyCorrection}
          aria-pressed={onlyCorrection}
          className={`rounded px-2 py-1 text-xs transition-colors ${
            onlyCorrection
              ? 'bg-red-500/20 text-red-400 border border-red-500/40'
              : 'border border-gray-700 text-gray-500 hover:text-gray-300'
          }`}
        >
          정정공시만
        </button>
      </div>

      {/* 빈 상태 (ADR 0075 §11-3 + feedback_no_mock_data 정합) */}
      {items.length === 0 ? (
        <p className="py-4 text-center text-xs text-gray-500">
          {onlyCorrection ? '최근 7일간 정정공시가 없습니다.' : '최근 7일간 공시가 없습니다.'}
        </p>
      ) : (
        <>
          <ul>
            {items.map((item) => (
              <DisclosureItemRow key={item.id} item={item} />
            ))}
          </ul>

          {/* 더 보기 */}
          {hasMore && (
            <button
              type="button"
              onClick={() => setPage((p) => p + 1)}
              className="mt-3 w-full rounded border border-gray-700 py-1.5 text-xs text-gray-400 hover:border-gray-500 hover:text-gray-200 transition-colors"
            >
              더 보기
            </button>
          )}

          {/* 처음으로 */}
          {page > 0 && (
            <button
              type="button"
              onClick={() => setPage(0)}
              className="mt-2 w-full text-xs text-gray-600 hover:text-gray-400 transition-colors"
            >
              처음으로
            </button>
          )}
        </>
      )}
    </section>
  )
}
