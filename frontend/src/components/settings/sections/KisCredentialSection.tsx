/**
 * KisCredentialSection — KIS 키 관리 섹션
 *
 * ADR 0069 §3 Section 2: SettingsModal 내 mount 위치.
 *
 * 1차 PR Option A LOCK (spec §5-3):
 * - REAL 키 변경: KisCredentialForm 레이아웃 표시 + submit 버튼 disabled
 *   → "KIS 키 변경 기능은 후속 PR에서 활성화됩니다 (ADR 0069 §3 후속)" 안내
 * - MOCK 키 추가: 동상 (동일 이유)
 * - 현재 등록 상태: useOnboardingStatus의 hasUserAccount + hasMockAccount boolean 표시
 *
 * 마스킹 표시 (ADR 0069 §6):
 * - 계좌번호: backend가 masked_account 응답 — 1차 PR은 "등록됨" 표시
 * - App Key: "****" 표시
 * - Secret Key: "********" 표시
 */
import { useState } from 'react'
import { SettingsSection } from '../SettingsSection'
import { KisCredentialForm } from '../forms/KisCredentialForm'
import { useOnboardingStatus } from '../../../api/onboarding'

type ActiveForm = 'REAL' | 'MOCK' | null

export function KisCredentialSection() {
  const { data: status } = useOnboardingStatus()
  const [activeForm, setActiveForm] = useState<ActiveForm>(null)

  const hasReal = status?.hasUserAccount ?? false
  const hasMock = status?.hasMockAccount ?? false

  return (
    <SettingsSection
      title="KIS 키 관리"
      description="KIS 실전·모의 계좌 API 키를 관리합니다. 키 변경 기능은 후속 PR에서 활성화됩니다."
    >
      {/* 현재 등록 상태 */}
      <div className="mb-4 space-y-2">
        <div className="flex items-center justify-between rounded-lg border border-gray-800 bg-gray-900/50 px-4 py-3">
          <div>
            <span className="text-sm font-medium text-gray-300">실전 계좌 (REAL)</span>
            {hasReal && (
              <div className="mt-0.5 flex gap-4 text-xs text-gray-500">
                <span>App Key: <code className="text-gray-400">****</code></span>
                <span>Secret Key: <code className="text-gray-400">********</code></span>
              </div>
            )}
          </div>
          <div className="flex items-center gap-2">
            {hasReal ? (
              <span className="rounded-full bg-green-900/40 px-2 py-0.5 text-xs text-green-400">
                등록됨
              </span>
            ) : (
              <span className="rounded-full bg-gray-800 px-2 py-0.5 text-xs text-gray-500">
                미등록
              </span>
            )}
            <button
              type="button"
              onClick={() => setActiveForm((prev) => (prev === 'REAL' ? null : 'REAL'))}
              className="rounded-lg border border-gray-700 px-3 py-1 text-xs text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200"
            >
              {activeForm === 'REAL' ? '접기' : '변경'}
            </button>
          </div>
        </div>

        <div className="flex items-center justify-between rounded-lg border border-gray-800 bg-gray-900/50 px-4 py-3">
          <div>
            <span className="text-sm font-medium text-gray-300">모의 계좌 (MOCK)</span>
            {hasMock && (
              <div className="mt-0.5 flex gap-4 text-xs text-gray-500">
                <span>App Key: <code className="text-gray-400">****</code></span>
                <span>Secret Key: <code className="text-gray-400">********</code></span>
              </div>
            )}
          </div>
          <div className="flex items-center gap-2">
            {hasMock ? (
              <span className="rounded-full bg-blue-900/40 px-2 py-0.5 text-xs text-blue-400">
                등록됨
              </span>
            ) : (
              <span className="rounded-full bg-gray-800 px-2 py-0.5 text-xs text-gray-500">
                미등록
              </span>
            )}
            <button
              type="button"
              onClick={() => setActiveForm((prev) => (prev === 'MOCK' ? null : 'MOCK'))}
              className="rounded-lg border border-gray-700 px-3 py-1 text-xs text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200"
            >
              {activeForm === 'MOCK' ? '접기' : hasMock ? '변경' : '추가'}
            </button>
          </div>
        </div>
      </div>

      {/* 1차 PR Option A — 안내 메시지 */}
      <div className="mb-4 rounded-lg border border-yellow-800/30 bg-yellow-950/20 px-4 py-3">
        <p className="text-xs text-yellow-400/80">
          KIS 키 변경 기능은 후속 PR에서 활성화됩니다 (ADR 0069 §3 후속).
          현재는 등록된 키 상태만 확인할 수 있습니다.
        </p>
      </div>

      {/* REAL 키 변경 폼 (layout 표시 + submit disabled — Option A) */}
      {activeForm === 'REAL' && (
        <div className="mt-4 rounded-lg border border-gray-800 bg-gray-900/30 p-4">
          <h4 className="mb-3 text-xs font-semibold uppercase tracking-wider text-gray-500">
            실전 키 변경
          </h4>
          <KisCredentialForm
            accountType="REAL"
            accountNumberReadOnly
            submitDisabled
            submitLabel="변경 저장 (후속 PR 활성화 예정)"
          />
        </div>
      )}

      {/* MOCK 키 추가/변경 폼 (layout 표시 + submit disabled — Option A) */}
      {activeForm === 'MOCK' && (
        <div className="mt-4 rounded-lg border border-gray-800 bg-gray-900/30 p-4">
          <h4 className="mb-3 text-xs font-semibold uppercase tracking-wider text-gray-500">
            {hasMock ? '모의 키 변경' : '모의 키 추가'}
          </h4>
          <KisCredentialForm
            accountType="MOCK"
            submitDisabled
            submitLabel="저장 (후속 PR 활성화 예정)"
          />
        </div>
      )}
    </SettingsSection>
  )
}
