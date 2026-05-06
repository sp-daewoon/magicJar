/**
 * Zustand onboarding store
 *
 * spec §5-9 정합 — 마법사 step 추적용 클라이언트 상태
 * TanStack Query는 server state, Zustand는 wizard step 추적용으로 분리
 */
import { create } from 'zustand'

type WizardStep = 1 | 2 | 3 | 4 | 5 | 6 | 7

interface OnboardingState {
  /** 현재 단계 (1=KisReal입력, 2=검증중, 3=KisMock, 4=SystemConfig, 5=Complete, 6=완료, 7=운영) */
  currentStep: WizardStep
  /** KIS 검증 후 반환된 userId (8자리) */
  registeredUserId: string | null
  /** 모의계좌 등록 여부 */
  hasMockRegistered: boolean
  /** 시스템 설정(DART/Slack) 등록 여부 */
  hasSystemConfig: boolean
  /** 검증 실패 시 에러 메시지 (Step 1로 돌아갈 때 표시) */
  validationError: string | null

  // actions
  setStep: (step: WizardStep) => void
  setRegistered: (userId: string) => void
  setMockRegistered: () => void
  setSystemConfig: () => void
  setValidationError: (message: string | null) => void
  reset: () => void
}

const initialState = {
  currentStep: 1 as WizardStep,
  registeredUserId: null,
  hasMockRegistered: false,
  hasSystemConfig: false,
  validationError: null,
}

export const useOnboardingStore = create<OnboardingState>((set) => ({
  ...initialState,

  setStep: (step) => set({ currentStep: step }),

  setRegistered: (userId) =>
    set({
      registeredUserId: userId,
      validationError: null,
    }),

  setMockRegistered: () => set({ hasMockRegistered: true }),

  setSystemConfig: () => set({ hasSystemConfig: true }),

  setValidationError: (message) => set({ validationError: message }),

  reset: () => set(initialState),
}))
