/**
 * 관심종목 행 컴포넌트
 *
 * spec §3-2 B 정합:
 * - 1 row: code · priority · 등록일 + 휴지통 버튼
 * - 휴지통 클릭 → window.confirm("관심종목 005930을(를) 삭제하시겠습니까?")
 * - confirm 확인 → useRemoveWatchlist() mutation 호출
 * - row fade-out animation (200ms — Tailwind transition)
 */
import { useState } from 'react'
import { useRemoveWatchlist } from '../../api/hooks/useWatchlist'
import type { WatchlistEntryDto } from '../../api/watchlist'

interface WatchlistRowProps {
  entry: WatchlistEntryDto
}

/** ISO 날짜 문자열을 "YYYY-MM-DD" 포맷으로 변환 (KST 표시용) */
function formatDate(isoString: string): string {
  const date = new Date(isoString)
  return date.toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    timeZone: 'Asia/Seoul',
  })
}

export function WatchlistRow({ entry }: WatchlistRowProps) {
  const [isRemoving, setIsRemoving] = useState(false)
  const { mutate: removeWatchlist, isPending } = useRemoveWatchlist()

  function handleRemoveClick() {
    if (window.confirm(`관심종목 ${entry.code}을(를) 삭제하시겠습니까?`)) {
      setIsRemoving(true)
      removeWatchlist(entry.code, {
        onError: () => {
          // 삭제 실패 시 fade-out 취소
          setIsRemoving(false)
        },
      })
    }
  }

  return (
    <div
      className={`flex items-center justify-between rounded-xl border border-gray-800 bg-gray-900 px-4 py-3 transition-all duration-200 ${
        isRemoving ? 'opacity-0' : 'opacity-100'
      }`}
      aria-label={`관심종목 ${entry.code}`}
    >
      <div className="flex items-center gap-4">
        {/* 종목 코드 */}
        <span className="font-mono text-base font-semibold text-white tracking-wider">
          {entry.code}
        </span>
        {/* 우선순위 */}
        <span className="rounded-md bg-gray-800 px-2 py-0.5 text-xs text-gray-400">
          P{entry.priority}
        </span>
      </div>

      <div className="flex items-center gap-4">
        {/* 등록일 (KST) */}
        <span className="text-xs text-gray-500">{formatDate(entry.createdAt)}</span>

        {/* 삭제 버튼 */}
        <button
          type="button"
          onClick={handleRemoveClick}
          disabled={isPending || isRemoving}
          className="flex h-8 w-8 items-center justify-center rounded-lg text-gray-500 transition-colors hover:bg-red-900/40 hover:text-red-400 focus:outline-none focus:ring-2 focus:ring-red-400 focus:ring-offset-1 focus:ring-offset-gray-900 disabled:cursor-not-allowed disabled:opacity-40"
          aria-label={`${entry.code} 삭제`}
        >
          <svg
            className="h-4 w-4"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={2}
            aria-hidden="true"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="m14.74 9-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 0 1-2.244 2.077H8.084a2.25 2.25 0 0 1-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 0 0-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 0 1 3.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 0 0-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 0 0-7.5 0"
            />
          </svg>
        </button>
      </div>
    </div>
  )
}
