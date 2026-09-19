/**
 * TradingModeSection — 활성 주문 모드 전환 (감사 2026-09-19 §5 · 재기동 없는 MOCK↔REAL)
 *
 * SettingsModal Section: 사용자가 서비스를 내리지 않고 주문 모드를 전환하는 창구.
 *
 * 핵심 원칙 (사용자 확정):
 *  - 시세/차트/지표는 이 모드와 무관하게 항상 REAL(실시간). 이 토글은 주문 경로에만 영향.
 *  - MOCK 전환은 항상 허용(안전 방향).
 *  - REAL 전환은 env 상한(MAGICJAR_MODE=REAL + ALLOW_REAL=true) + 사용자 실거래 동의가
 *    모두 충족될 때만 가능. 미충족 시 REAL 버튼 비활성 + 사유 안내.
 *  - backend가 최종 방어(412) — UI 판단은 UX 편의일 뿐, 실제 게이트는 서버.
 */
import { useState } from 'react'
import { extractErrorMessage, useSystemConfig, useUpdateTradingMode } from '../../../api/onboarding'
import { useRuntimeFlags } from '../../../api/hooks/useRuntimeFlags'
import { useOnboardingStore } from '../../../stores/onboarding'
import type { SectionStatus } from '../SettingsSection'
import { SettingsSection } from '../SettingsSection'

export function TradingModeSection() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const { data: systemConfig } = useSystemConfig()
  const { data: flags } = useRuntimeFlags(userId)
  const { mutate, isPending } = useUpdateTradingMode()

  const [sectionStatus, setSectionStatus] = useState<SectionStatus>('idle')
  const [sectionError, setSectionError] = useState<string | null>(null)

  const activeMode = systemConfig?.activeTradingMode ?? 'MOCK'

  // REAL 전환 가능 조건 — backend와 동일 판정(UX 선반영). 최종 방어는 서버(412).
  const envReal = flags?.mode === 'REAL'
  const allowReal = flags?.allowReal === true
  const consented = flags?.userConsented === true
  const canGoReal = envReal && allowReal && consented

  const realBlockedReason = !envReal
    ? '서버 실거래 모드가 잠겨 있습니다 (MAGICJAR_MODE)'
    : !allowReal
      ? '실거래 허용 플래그가 꺼져 있습니다 (MAGICJAR_ALLOW_REAL)'
      : !consented
        ? '실거래 동의가 필요합니다 (위 실거래 동의 토글 확인)'
        : null

  function switchTo(mode: 'REAL' | 'MOCK') {
    if (mode === activeMode) return
    setSectionStatus('saving')
    setSectionError(null)
    mutate(
      { mode },
      {
        onSuccess: () => {
          setSectionStatus('saved')
          setTimeout(() => setSectionStatus('idle'), 3000)
        },
        onError: (err) => {
          setSectionStatus('error')
          setSectionError(extractErrorMessage(err, '주문 모드 전환에 실패했습니다.'))
        },
      },
    )
  }

  return (
    <SettingsSection
      title="주문 모드 (모의 / 실거래)"
      description="주문이 모의투자로 나갈지 실거래로 나갈지 결정합니다. 서비스 재시작 없이 즉시 전환됩니다. 시세·차트·지표는 모드와 무관하게 항상 실시간입니다."
      status={sectionStatus}
      errorMessage={sectionError ?? undefined}
    >
      <div className="mb-3 flex items-center gap-2">
        <span className="text-xs text-gray-500">현재 활성 모드:</span>
        {activeMode === 'REAL' ? (
          <span className="rounded-full bg-red-900/40 px-2 py-0.5 text-xs font-medium text-red-300">실거래</span>
        ) : (
          <span className="rounded-full bg-green-900/40 px-2 py-0.5 text-xs font-medium text-green-300">모의투자</span>
        )}
      </div>

      <div className="flex gap-2">
        <button
          type="button"
          disabled={isPending || activeMode === 'MOCK'}
          onClick={() => switchTo('MOCK')}
          className={
            activeMode === 'MOCK'
              ? 'flex-1 rounded-lg border border-green-500 bg-green-900/30 px-4 py-2 text-sm font-medium text-green-300'
              : 'flex-1 rounded-lg border border-gray-700 bg-gray-800 px-4 py-2 text-sm text-gray-300 transition-colors hover:border-green-600 hover:text-green-300 disabled:opacity-50'
          }
        >
          모의투자
        </button>
        <button
          type="button"
          disabled={isPending || activeMode === 'REAL' || !canGoReal}
          onClick={() => switchTo('REAL')}
          title={realBlockedReason ?? undefined}
          className={
            activeMode === 'REAL'
              ? 'flex-1 rounded-lg border border-red-500 bg-red-900/30 px-4 py-2 text-sm font-medium text-red-300'
              : 'flex-1 rounded-lg border border-gray-700 bg-gray-800 px-4 py-2 text-sm text-gray-300 transition-colors hover:border-red-600 hover:text-red-300 disabled:cursor-not-allowed disabled:opacity-40'
          }
        >
          실거래
        </button>
      </div>

      {activeMode === 'MOCK' && realBlockedReason && (
        <p className="mt-2 text-xs text-yellow-500/80">실거래로 전환하려면: {realBlockedReason}</p>
      )}
      {activeMode === 'REAL' && (
        <p className="mt-2 text-xs text-red-400/80">
          ⚠ 실거래 모드 — 자동매매 주문이 실제 계좌로 발주됩니다.
        </p>
      )}
    </SettingsSection>
  )
}
