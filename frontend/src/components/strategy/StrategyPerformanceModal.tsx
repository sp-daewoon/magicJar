/**
 * StrategyPerformanceModal — 전략 성과·메모 모달 wrapper (T-PERF R3 — ADR 0126)
 *
 * StrategyCard "📊 성과·메모" 버튼 → 본 모달 오픈.
 * 본문은 공용 StrategyPerformanceContent (페이지와 동일 컴포넌트 재사용).
 *
 * useModalBehavior (ESC 닫기 + body scroll lock + IME 안전) 정합.
 * 룰 5: LLM provider import 0건.
 */
import { Link } from 'react-router-dom'
import { useModalBehavior } from '../../hooks/useModalBehavior'
import { StrategyPerformanceContent } from './StrategyPerformanceContent'

interface StrategyPerformanceModalProps {
  strategyId: number
  strategyName?: string
  userId: number | null
  onClose: () => void
}

export function StrategyPerformanceModal({
  strategyId,
  strategyName,
  userId,
  onClose,
}: StrategyPerformanceModalProps) {
  useModalBehavior({ open: true, onClose })

  return (
    <div
      className="fixed inset-0 z-[70] flex items-center justify-center bg-black/70 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="전략 성과·메모"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div className="max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-lg border border-gray-800 bg-gray-950 shadow-xl">
        {/* 헤더 */}
        <div className="sticky top-0 z-10 flex items-center justify-between border-b border-gray-800 bg-gray-950 px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold text-gray-100">전략 성과·메모</h2>
            <p className="mt-0.5 text-xs text-gray-500">
              하루하루 누적된 성과와 그날의 시장 맥락·메모를 확인합니다
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Link
              to={`/strategy/${strategyId}/performance`}
              className="rounded border border-gray-700 px-2.5 py-1 text-xs text-gray-300 hover:bg-gray-800"
            >
              전체화면
            </Link>
            <button
              type="button"
              onClick={onClose}
              className="rounded border border-gray-700 px-2.5 py-1 text-xs text-gray-300 hover:bg-gray-800"
              aria-label="모달 닫기"
            >
              닫기
            </button>
          </div>
        </div>

        <div className="p-4">
          <StrategyPerformanceContent strategyId={strategyId} strategyName={strategyName} userId={userId} />
        </div>
      </div>
    </div>
  )
}
