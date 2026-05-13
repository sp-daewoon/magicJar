/**
 * SettingsModal — 운영 변경 화면 modal (ADR 0069 §3 1차 scope)
 *
 * 진입점: AppHeader ⚙️ 버튼 클릭
 * 닫기: 우상단 X 버튼 / Esc 키 / overlay click
 *
 * 3 section:
 *   Section 1: RealTradingConsentSection (실거래 동의 — 이중 confirm UX)
 *   Section 2: KisCredentialSection (KIS 키 — 1차 layout + disabled submit)
 *   Section 3: SystemConfigSection (DART + Slack — fully functional)
 *
 * overlay z-index: z-[60] — AppHeader(z-50) 위
 * panel: max-w-2xl + max-h-[85vh] overflow-y-auto
 *
 * ADR 0069 §6 UX 룰 정합.
 */
import { useEffect } from 'react'
import { KisCredentialSection } from './sections/KisCredentialSection'
import { RealTradingConsentSection } from './sections/RealTradingConsentSection'
import { SystemConfigSection } from './sections/SystemConfigSection'

interface Props {
  onClose: () => void
}

function XIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="h-5 w-5"
      aria-hidden="true"
    >
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  )
}

export function SettingsModal({ onClose }: Props) {
  // Esc 키 → 닫기 (ADR 0069 §6 UX 룰)
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  // body scroll lock
  useEffect(() => {
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = prev }
  }, [])

  function handleOverlayClick(e: React.MouseEvent<HTMLDivElement>) {
    // panel 클릭 이벤트가 올라오지 않도록 panel에서 stopPropagation 처리됨
    // overlay 클릭 시 단순 닫기 (1차 PR — form dirty 추적은 ADR 0069 §7 §J 후속)
    if (e.target === e.currentTarget) onClose()
  }

  return (
    /* overlay */
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center bg-black/70 p-4"
      onClick={handleOverlayClick}
      role="dialog"
      aria-modal="true"
      aria-label="설정"
    >
      {/* panel */}
      <div
        className="w-full max-w-2xl max-h-[85vh] overflow-y-auto rounded-2xl border border-gray-800 bg-gray-900 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <header className="sticky top-0 z-10 flex items-center justify-between border-b border-gray-800 bg-gray-900 px-6 py-4">
          <h2 className="text-lg font-semibold text-white">설정</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="설정 닫기"
            className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-gray-600"
          >
            <XIcon />
          </button>
        </header>

        {/* 섹션 컨테이너 — divide-y border */}
        <div className="divide-y divide-gray-800">
          {/* Section 1: 실거래 동의 */}
          <RealTradingConsentSection />

          {/* Section 2: KIS 키 관리 */}
          <KisCredentialSection />

          {/* Section 3: 시스템 설정 */}
          <SystemConfigSection />
        </div>
      </div>
    </div>
  )
}
