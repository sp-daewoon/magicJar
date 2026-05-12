/**
 * PositionsPage — /positions (T-RISK Phase 3 — ADR 0067)
 *
 * 보유 포지션 페이지:
 * - source 필터 (MOCK / REAL / 전체)
 * - PositionCard 그리드
 * - 빈 상태 안내: "보유 포지션 없음"
 * - 오류 시 위젯별 오류 표시 (페이지 전체 중단 금지)
 */
import { useState } from 'react'
import type { PositionSource } from '../../api/position'
import { usePositions } from '../../api/hooks/usePositions'
import { PositionCard } from '../../components/risk/PositionCard'

const SOURCE_OPTIONS: { value: PositionSource | ''; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'MOCK', label: 'MOCK' },
  { value: 'REAL', label: 'REAL' },
]

export function PositionsPage() {
  const [sourceFilter, setSourceFilter] = useState<PositionSource | ''>('')
  const { data, isLoading, isError, refetch } = usePositions(
    sourceFilter ? { source: sourceFilter } : {},
  )

  const positions = data ?? []

  return (
    <div className="mx-auto max-w-5xl px-4 py-8">
      {/* 페이지 헤더 */}
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white">보유 포지션</h1>
          <p className="mt-2 text-sm text-gray-500">
            현재 자동매매 시스템이 보유 중인 포지션 목록입니다.
          </p>
        </div>
        {/* 새로고침 버튼 */}
        <button
          onClick={() => void refetch()}
          className="rounded-lg border border-gray-700 px-3 py-1.5 text-sm text-gray-400 hover:bg-gray-800 hover:text-gray-200 transition-colors"
          aria-label="포지션 목록 새로고침"
        >
          새로고침
        </button>
      </div>

      {/* 소스 필터 */}
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <span className="text-xs text-gray-500">소스 필터</span>
        <div className="flex gap-2">
          {SOURCE_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              onClick={() => setSourceFilter(opt.value)}
              className={[
                'rounded-lg px-3 py-1.5 text-xs font-medium transition-colors',
                sourceFilter === opt.value
                  ? 'bg-blue-600 text-white'
                  : 'border border-gray-700 text-gray-400 hover:bg-gray-800 hover:text-gray-200',
              ].join(' ')}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* 포지션 목록 */}
      {isLoading ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="h-36 animate-pulse rounded-xl bg-gray-800" />
          ))}
        </div>
      ) : isError ? (
        <div className="rounded-xl border border-red-800 bg-red-900/20 p-6 text-center text-sm text-red-400">
          포지션 목록을 불러오지 못했습니다.
          <button
            onClick={() => void refetch()}
            className="ml-2 text-red-300 underline hover:text-red-200"
          >
            다시 시도
          </button>
        </div>
      ) : positions.length === 0 ? (
        <div className="rounded-xl border border-gray-800 bg-gray-900/40 py-16 text-center">
          <p className="text-gray-500">보유 포지션이 없습니다.</p>
          <p className="mt-1 text-xs text-gray-600">
            자동매매 전략이 활성화되면 포지션이 여기에 표시됩니다.
          </p>
        </div>
      ) : (
        <>
          <div className="mb-3 text-xs text-gray-500">
            총 {positions.length}개 포지션
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {positions.map((pos) => (
              <PositionCard key={pos.id} position={pos} />
            ))}
          </div>
        </>
      )}
    </div>
  )
}
