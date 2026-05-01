/**
 * 봉 단위 선택 버튼 그룹.
 * 일/주/월/년: 백엔드 D1 데이터를 클라이언트에서 집계 (candleAggregator)
 * 분봉(M1~H1): 백엔드 Interval enum이 지원 — 시드 부재 시 빈 응답 안내
 * 틱: STOMP 실시간 stream (별도 처리, MVP에서는 미연결)
 */
import { cn } from '@/lib/cn'
import type { ClientInterval } from '@/lib/candleAggregator'

interface Option {
  value: ClientInterval | 'TICK'
  label: string
  available: boolean
}

const OPTIONS: Option[] = [
  { value: 'DAY', label: '일', available: true },
  { value: 'WEEK', label: '주', available: true },
  { value: 'MONTH', label: '월', available: true },
  { value: 'YEAR', label: '년', available: true },
  { value: 'H1', label: '60분', available: true },
  { value: 'M30', label: '30분', available: true },
  { value: 'M15', label: '15분', available: true },
  { value: 'M5', label: '5분', available: true },
  { value: 'M1', label: '1분', available: true },
  { value: 'TICK', label: '틱', available: true },
]

interface Props {
  value: ClientInterval
  onChange: (v: ClientInterval) => void
}

export default function IntervalSelector({ value, onChange }: Props) {
  return (
    <div className="flex flex-wrap items-center gap-1">
      {OPTIONS.map((opt) => {
        const isActive = opt.value === value
        const disabled = !opt.available
        return (
          <button
            key={opt.value}
            type="button"
            disabled={disabled}
            onClick={() => !disabled && onChange(opt.value as ClientInterval)}
            title={disabled ? '준비 중' : undefined}
            className={cn(
              'rounded px-2.5 py-1 text-xs font-medium transition',
              isActive
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted text-muted-foreground hover:bg-muted/70',
              disabled && 'cursor-not-allowed opacity-40 hover:bg-muted',
            )}
          >
            {opt.label}
          </button>
        )
      })}
    </div>
  )
}
