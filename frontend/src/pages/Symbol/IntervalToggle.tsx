/**
 * 차트 인터벌 토글 컴포넌트
 *
 * 인터벌 8종: 1m·5m·30m·1h·1d·1wk·1mo·1y
 * 주의: 1wk·1mo·1y는 backend-core PR(PR-31b)과 동시 활성화.
 *       backend가 머지되기 전에는 400 응답 → "데이터 로드 실패" 표시 (회귀 허용, orchestrator 시퀀싱).
 * 활성 버튼 강조 + 키보드 접근성
 */
import type { CandleInterval } from '../../api/symbol'

interface IntervalToggleProps {
  value: CandleInterval
  onChange: (interval: CandleInterval) => void
}

const INTERVALS: { value: CandleInterval; label: string }[] = [
  { value: '1m', label: '1분' },
  { value: '5m', label: '5분' },
  { value: '30m', label: '30분' },
  { value: '1h', label: '1시간' },
  { value: '1d', label: '일' },
  { value: '1wk', label: '주' },
  { value: '1mo', label: '월' },
  { value: '1y', label: '년' },
]

export function IntervalToggle({ value, onChange }: IntervalToggleProps) {
  return (
    <div
      role="group"
      aria-label="차트 인터벌 선택"
      className="flex gap-1 flex-wrap"
    >
      {INTERVALS.map((item) => {
        const isActive = item.value === value
        return (
          <button
            key={item.value}
            type="button"
            onClick={() => onChange(item.value)}
            aria-pressed={isActive}
            aria-label={`인터벌 ${item.label} 선택${isActive ? ' (현재 선택됨)' : ''}`}
            className={`rounded-md px-3 py-1 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900 ${
              isActive
                ? 'bg-blue-600 text-white'
                : 'bg-gray-800 text-gray-400 hover:bg-gray-700 hover:text-gray-200'
            }`}
          >
            {item.label}
          </button>
        )
      })}
    </div>
  )
}
