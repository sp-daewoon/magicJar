/**
 * CollapsibleSection — 접기/펼치기 섹션 (빌더 고도화 C · SaveModal UX 정돈)
 *
 * SaveModal이 한 모달에 과밀해지는 문제를 섹션 그룹핑 + 접기로 완화한다.
 * 필수(이름·종목·손절·익절)는 펼침 default, 고급(VI·분할매수·분할익절)은 접기 default.
 *
 * 순수 표현(레이아웃)만 담당 — 입력/검증/payload 로직은 부모(SaveModal)가 보존한다.
 * 룰 5: LLM provider import 0건
 */
import { useId, useState, type ReactNode } from 'react'

interface CollapsibleSectionProps {
  title: string
  /** 제목 옆 부가 설명(optional) */
  hint?: string
  /** 초기 펼침 여부 (default true) */
  defaultOpen?: boolean
  /** 우측 상단 배지(optional, 예: "고급") */
  badge?: ReactNode
  children: ReactNode
  className?: string
}

export function CollapsibleSection({
  title,
  hint,
  defaultOpen = true,
  badge,
  children,
  className = '',
}: CollapsibleSectionProps) {
  const [open, setOpen] = useState(defaultOpen)
  const panelId = useId()

  return (
    <div className={['rounded-lg border border-gray-800 bg-gray-900/40', className].filter(Boolean).join(' ')}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-controls={panelId}
        className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left focus:outline-none focus:ring-2 focus:ring-blue-400"
      >
        <span className="flex min-w-0 items-center gap-2">
          <span className="text-xs font-semibold text-gray-200">{title}</span>
          {hint && <span className="truncate text-[11px] text-gray-500">{hint}</span>}
        </span>
        <span className="flex shrink-0 items-center gap-2">
          {badge}
          <span aria-hidden="true" className="text-gray-500 transition-transform" style={{ transform: open ? 'rotate(90deg)' : 'none' }}>
            ▸
          </span>
        </span>
      </button>
      {open && (
        <div id={panelId} className="border-t border-gray-800 p-3">
          {children}
        </div>
      )}
    </div>
  )
}
