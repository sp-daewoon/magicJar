/**
 * RiskEventsPage — /risk-events (T-RISK Phase 3 — ADR 0067)
 *
 * 리스크 위반 이력 페이지:
 * - 날짜 범위 필터 + verdict 필터
 * - RiskEventList (무한 스크롤 테이블)
 * - 빈 상태 안내: "리스크 위반 이력 없음"
 */
import { useState } from 'react'
import type { RiskVerdict } from '../../api/risk'
import { RiskEventList } from '../../components/risk/RiskEventList'

const VERDICT_OPTIONS: { value: RiskVerdict | ''; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'ALLOWED', label: '허용' },
  { value: 'BLOCKED', label: '차단' },
  { value: 'AUTO_PAUSED', label: '자동 정지' },
]

export function RiskEventsPage() {
  const [verdictFilter, setVerdictFilter] = useState<RiskVerdict | ''>('')

  return (
    <div className="mx-auto max-w-5xl px-4 py-8">
      {/* 페이지 헤더 */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-white">리스크 위반 이력</h1>
        <p className="mt-2 text-sm text-gray-500">
          시그널이 리스크 정책에 의해 차단되거나 자동 정지된 이벤트 목록입니다.
        </p>
      </div>

      {/* 필터 영역 */}
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <span className="text-xs text-gray-500">판정 필터</span>
        <div className="flex gap-2">
          {VERDICT_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              onClick={() => setVerdictFilter(opt.value)}
              className={[
                'rounded-lg px-3 py-1.5 text-xs font-medium transition-colors',
                verdictFilter === opt.value
                  ? 'bg-blue-600 text-white'
                  : 'border border-gray-700 text-gray-400 hover:bg-gray-800 hover:text-gray-200',
              ].join(' ')}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* 이벤트 목록 */}
      <RiskEventList verdictFilter={verdictFilter || undefined} />
    </div>
  )
}
