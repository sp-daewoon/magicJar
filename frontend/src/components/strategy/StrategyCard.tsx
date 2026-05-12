/**
 * StrategyCard — 전략 단건 카드 (T-SIG Phase 3 — ADR 0066)
 *
 * 표시 항목: 이름·codes·intervals·status badge·시그널 카운트
 * 액션: 상태 토글 (ACTIVE↔PAUSED), 삭제 (soft delete)
 * 실제 데이터만 표시 (feedback_no_mock_data 정합)
 */
import { useState } from 'react'
import {
  type SigStrategyDto,
  type StrategyStatus,
} from '../../api/strategy'
import { useDeleteSigStrategy, useToggleSigStrategyStatus } from '../../api/hooks/useSigStrategy'

// ──────────────────────────────────────────────
// 상태 배지 스타일
// ──────────────────────────────────────────────

const STATUS_BADGE: Record<StrategyStatus, { label: string; cls: string }> = {
  ACTIVE:   { label: '활성',     cls: 'bg-emerald-900/80 text-emerald-300 border border-emerald-700' },
  PAUSED:   { label: '일시정지', cls: 'bg-amber-900/80 text-amber-300 border border-amber-700' },
  DISABLED: { label: '비활성',  cls: 'bg-gray-700/80 text-gray-400 border border-gray-600' },
  DELETED:  { label: '삭제됨',  cls: 'bg-red-900/80 text-red-400 border border-red-800' },
}

// ──────────────────────────────────────────────
// 컴포넌트
// ──────────────────────────────────────────────

interface StrategyCardProps {
  strategy: SigStrategyDto
}

export function StrategyCard({ strategy }: StrategyCardProps) {
  const [confirmDelete, setConfirmDelete] = useState(false)
  const badge = STATUS_BADGE[strategy.status]

  const toggleMutation = useToggleSigStrategyStatus()
  const deleteMutation = useDeleteSigStrategy()

  function handleToggle() {
    const nextStatus: StrategyStatus =
      strategy.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE'
    toggleMutation.mutate({ id: strategy.id, status: nextStatus })
  }

  function handleDelete() {
    if (!confirmDelete) {
      setConfirmDelete(true)
      return
    }
    deleteMutation.mutate(strategy.id)
    setConfirmDelete(false)
  }

  const isToggleable = strategy.status === 'ACTIVE' || strategy.status === 'PAUSED'
  const isDeletable = strategy.status !== 'DELETED'
  const isBusy = toggleMutation.isPending || deleteMutation.isPending

  return (
    <article className="flex flex-col gap-3 rounded-xl border border-gray-800 bg-gray-900 p-5 transition-colors hover:border-gray-700">
      {/* 헤더 */}
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="truncate text-base font-semibold text-white" title={strategy.name}>
            {strategy.name}
          </h3>
          <div className="mt-1 flex flex-wrap gap-1">
            {strategy.codes.slice(0, 4).map((code) => (
              <span
                key={code}
                className="rounded bg-gray-800 px-1.5 py-0.5 text-xs font-medium text-gray-300"
              >
                {code}
              </span>
            ))}
            {strategy.codes.length > 4 && (
              <span className="rounded bg-gray-800 px-1.5 py-0.5 text-xs text-gray-500">
                +{strategy.codes.length - 4}
              </span>
            )}
          </div>
        </div>

        {/* 상태 배지 */}
        <span
          className={`shrink-0 rounded px-2 py-0.5 text-xs font-bold ${badge.cls}`}
        >
          {badge.label}
        </span>
      </div>

      {/* 인터벌 */}
      {strategy.intervals.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {strategy.intervals.map((iv) => (
            <span
              key={iv}
              className="rounded bg-blue-950/50 px-2 py-0.5 text-xs text-blue-400"
            >
              {iv}
            </span>
          ))}
        </div>
      )}

      {/* DSL 미리보기 */}
      {strategy.buyCondition && (
        <p className="truncate rounded bg-gray-800/60 px-3 py-1.5 font-mono text-xs text-gray-300" title={strategy.buyCondition}>
          매수: {strategy.buyCondition}
        </p>
      )}

      {/* 시그널 카운트 */}
      {strategy.recentSignalCount !== undefined && (
        <p className="text-xs text-gray-500">
          최근 7일 시그널{' '}
          <span className="font-semibold text-gray-300">{strategy.recentSignalCount}건</span>
        </p>
      )}

      {/* 액션 버튼 */}
      <div className="flex items-center gap-2 pt-1">
        {/* 상태 토글 */}
        {isToggleable && (
          <button
            onClick={handleToggle}
            disabled={isBusy}
            className="rounded-lg bg-gray-800 px-3 py-1.5 text-xs font-medium text-gray-200 transition-colors hover:bg-gray-700 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900"
          >
            {toggleMutation.isPending
              ? '처리 중...'
              : strategy.status === 'ACTIVE'
              ? '일시정지'
              : '활성화'}
          </button>
        )}

        {/* 삭제 */}
        {isDeletable && (
          <button
            onClick={handleDelete}
            disabled={isBusy}
            onBlur={() => setConfirmDelete(false)}
            className={[
              'rounded-lg px-3 py-1.5 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-red-400 focus:ring-offset-1 focus:ring-offset-gray-900',
              confirmDelete
                ? 'bg-red-700 text-white hover:bg-red-600'
                : 'bg-gray-800 text-red-400 hover:bg-red-900/40',
            ].join(' ')}
          >
            {deleteMutation.isPending
              ? '삭제 중...'
              : confirmDelete
              ? '정말 삭제하시겠습니까?'
              : '삭제'}
          </button>
        )}

        {(toggleMutation.isError || deleteMutation.isError) && (
          <p className="text-xs text-red-400">요청 실패. 다시 시도해 주세요.</p>
        )}
      </div>
    </article>
  )
}
