/**
 * BlockedStatusPopover — 차단/위반 상태 라벨 + 클릭 popover
 *
 * 배경(2026-06-17, 최고 관리자 요청): 전략 카드에서 "신규 매수 차단"/리스크 차단 누적
 * 사유가 여러 줄로 인라인 렌더되어 카드 섹션이 과도하게 길어졌다.
 * → 라벨(배지)만 노출하고, 클릭 시 작은 popover에 요약(사유·시각·해제 조건)을 담는다.
 *   popover 안의 "최근 차단 이력 보기"로 전체 이력 화면(/risk-events)으로 넘어간다.
 *
 * 정보 손실 0: 인라인에 있던 모든 텍스트는 popover summary로 이동, 상세는 이력 화면에서.
 *
 * 접근성:
 *  - 라벨 button(aria-haspopup="dialog" + aria-expanded)
 *  - ESC / 외부 클릭으로 닫힘 (body scroll lock은 걸지 않음 — 인라인 popover)
 *  - 열릴 때 popover로 포커스 이동, 닫힐 때 트리거로 복귀
 */
import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

export interface BlockedStatusPopoverSummaryLine {
  /** 한 줄 요약 텍스트 */
  text: string
  /** 보조(흐린) 톤 여부 */
  muted?: boolean
}

interface BlockedStatusPopoverProps {
  /** 라벨(배지) 텍스트 — 예: "신규 매수 차단" */
  label: string
  /** popover 제목 */
  title: string
  /** popover 요약 줄 목록 (사유·시각·해제 조건 등) */
  summaryLines: BlockedStatusPopoverSummaryLine[]
  /** "최근 차단 이력 보기" 링크 대상 (필터 쿼리 포함) */
  historyTo: string
  /** 배지 색 톤: sky=매수차단, amber=보호중지 누적 */
  tone?: 'sky' | 'amber'
}

const TONE_STYLES: Record<
  NonNullable<BlockedStatusPopoverProps['tone']>,
  { badge: string; title: string }
> = {
  sky: {
    badge:
      'bg-sky-950/60 text-sky-200 border border-sky-700/70 hover:bg-sky-900/60 focus:ring-sky-400',
    title: 'text-sky-200',
  },
  amber: {
    badge:
      'bg-amber-950/50 text-amber-200 border border-amber-700/70 hover:bg-amber-900/50 focus:ring-amber-400',
    title: 'text-amber-200',
  },
}

export function BlockedStatusPopover({
  label,
  title,
  summaryLines,
  historyTo,
  tone = 'sky',
}: BlockedStatusPopoverProps) {
  const [open, setOpen] = useState(false)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const popoverRef = useRef<HTMLDivElement>(null)
  const panelId = useId()
  const toneStyle = TONE_STYLES[tone]

  // 외부 클릭 닫기
  useEffect(() => {
    if (!open) return
    function handlePointerDown(e: MouseEvent) {
      const target = e.target as Node
      if (
        popoverRef.current?.contains(target) ||
        triggerRef.current?.contains(target)
      ) {
        return
      }
      setOpen(false)
    }
    document.addEventListener('mousedown', handlePointerDown)
    return () => document.removeEventListener('mousedown', handlePointerDown)
  }, [open])

  // ESC 닫기 + 트리거 포커스 복귀
  useEffect(() => {
    if (!open) return
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Escape') return
      if (e.isComposing || e.keyCode === 229) return
      setOpen(false)
      triggerRef.current?.focus()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [open])

  // 열릴 때 popover로 포커스 이동
  useEffect(() => {
    if (open) popoverRef.current?.focus()
  }, [open])

  return (
    <div className="relative inline-flex">
      <button
        ref={triggerRef}
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        className={[
          'inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-offset-gray-900',
          toneStyle.badge,
        ].join(' ')}
      >
        <span aria-hidden="true">⛔</span>
        {label}
        <span aria-hidden="true" className="text-[10px] opacity-70">
          ▾
        </span>
      </button>

      {open && (
        <div
          ref={popoverRef}
          id={panelId}
          role="dialog"
          aria-label={title}
          tabIndex={-1}
          className="absolute left-0 top-full z-20 mt-1.5 w-64 rounded-lg border border-gray-700 bg-gray-900 p-3 text-xs leading-5 shadow-xl shadow-black/40 focus:outline-none"
        >
          <p className={`font-semibold ${toneStyle.title}`}>{title}</p>
          <div className="mt-1 space-y-1">
            {summaryLines.map((line, idx) => (
              <p
                key={idx}
                className={line.muted ? 'text-gray-400' : 'text-gray-200'}
              >
                {line.text}
              </p>
            ))}
          </div>
          <Link
            to={historyTo}
            onClick={() => setOpen(false)}
            className="mt-2 inline-flex items-center gap-1 font-semibold text-blue-300 underline-offset-2 hover:text-blue-200 hover:underline focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900 rounded"
          >
            최근 차단 이력 보기
            <span aria-hidden="true">→</span>
          </Link>
        </div>
      )}
    </div>
  )
}
