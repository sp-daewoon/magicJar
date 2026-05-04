/**
 * 봉 단위 선택 버튼 그룹.
 * 일/주/월/년: 백엔드 D1 데이터를 클라이언트에서 집계 (candleAggregator)
 * 분봉(M1~H1): 백엔드 Interval enum이 지원 — 시드 부재 시 빈 응답 안내
 * 자유 입력(Xm): 1~600 정수, 분 단위만 — 백엔드 R3-3 합성 그대로 전달
 * 틱: STOMP 실시간 stream (별도 처리, MVP에서는 미연결)
 *
 * ADR 0050 §2-D: 분 단위 자유 입력 정책
 *   - 일 이상(일/주/월/년)은 버튼만 제공
 *   - 분봉은 버튼(1m·5m·10m·15m·30m·60m) + 직접 입력(1~600) 병행
 *   - 입력 검증: 1~600 정수, 범위 밖이면 inline 에러 표시
 */
import { useState } from 'react'
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
  { value: 'M10', label: '10분', available: true },
  { value: 'M5', label: '5분', available: true },
  { value: 'M1', label: '1분', available: true },
  { value: 'TICK', label: '틱', available: true },
]

/** 자유 입력 분 값 → ClientInterval 형식으로 변환 */
function toCustomInterval(minutes: number): ClientInterval {
  return `${minutes}m` as ClientInterval
}

/** ClientInterval이 자유 입력 형식(`Xm`)인지 판별 */
function isCustomInterval(v: ClientInterval | string): boolean {
  return /^\d+m$/.test(v) && !['M1', 'M5', 'M10', 'M15', 'M30', 'H1'].includes(v)
}

/** 자유 입력 분 값 유효성 검사 */
function validateMinutes(raw: string): { ok: boolean; value: number; error: string } {
  const trimmed = raw.trim()
  if (trimmed === '') return { ok: false, value: 0, error: '' }

  // 정수만 허용 (소수·음수·문자 차단)
  if (!/^\d+$/.test(trimmed)) {
    return { ok: false, value: 0, error: '정수만 입력 가능합니다' }
  }
  const n = parseInt(trimmed, 10)
  if (n < 1 || n > 600) {
    return { ok: false, value: n, error: '1~600분 범위만 입력 가능합니다' }
  }
  return { ok: true, value: n, error: '' }
}

interface Props {
  value: ClientInterval
  onChange: (v: ClientInterval) => void
}

export default function IntervalSelector({ value, onChange }: Props) {
  const [customInput, setCustomInput] = useState<string>('')
  const [customError, setCustomError] = useState<string>('')

  /** 자유 입력 적용 */
  function applyCustom(raw: string) {
    const result = validateMinutes(raw)
    if (!result.ok) {
      if (result.error) setCustomError(result.error)
      return
    }
    setCustomError('')
    onChange(toCustomInterval(result.value))
  }

  /** 현재 value가 자유 입력인지 */
  const isCustomActive = isCustomInterval(value)

  return (
    <div className="flex flex-wrap items-start gap-1">
      {/* 버튼 그룹 */}
      {OPTIONS.map((opt) => {
        const active = opt.value === value
        const disabled = !opt.available
        return (
          <button
            key={opt.value}
            type="button"
            disabled={disabled}
            onClick={() => {
              if (disabled) return
              setCustomError('')
              setCustomInput('')
              onChange(opt.value as ClientInterval)
            }}
            title={disabled ? '준비 중' : undefined}
            className={cn(
              'rounded px-2.5 py-1 text-xs font-medium transition',
              active
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted text-muted-foreground hover:bg-muted/70',
              disabled && 'cursor-not-allowed opacity-40 hover:bg-muted',
            )}
          >
            {opt.label}
          </button>
        )
      })}

      {/* 자유 입력 — 분 단위 (1~600) */}
      <div className="flex flex-col gap-0.5">
        <div className="flex items-center gap-0.5">
          <input
            type="text"
            inputMode="numeric"
            placeholder="분"
            value={customInput}
            onChange={(e) => {
              setCustomInput(e.target.value)
              setCustomError('')
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') applyCustom(customInput)
            }}
            onBlur={() => {
              if (customInput.trim()) applyCustom(customInput)
            }}
            aria-label="분 단위 직접 입력 (1~600)"
            className={cn(
              'w-12 rounded border bg-background px-1.5 py-1 text-xs tabular-nums outline-none transition',
              isCustomActive
                ? 'border-primary ring-1 ring-primary'
                : 'border-input hover:border-muted-foreground/50',
              customError && 'border-destructive ring-1 ring-destructive',
            )}
          />
          <button
            type="button"
            onClick={() => applyCustom(customInput)}
            className={cn(
              'rounded px-2 py-1 text-xs font-medium transition',
              isCustomActive
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted text-muted-foreground hover:bg-muted/70',
            )}
          >
            적용
          </button>
        </div>
        {customError && (
          <span className="text-[10px] text-destructive leading-none" role="alert">
            {customError}
          </span>
        )}
        {isCustomActive && !customError && (
          <span className="text-[10px] text-primary leading-none">
            현재: {value}
          </span>
        )}
      </div>
    </div>
  )
}
