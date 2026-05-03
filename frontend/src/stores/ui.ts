/**
 * ui store — 전역 UI 상태 (모달 열림/닫힘 등)
 * Zustand — persist 없음 (세션 내 상태)
 */
import { create } from 'zustand'

interface UiState {
  settingsOpen: boolean
  openSettings: () => void
  closeSettings: () => void
}

export const useUiStore = create<UiState>((set) => ({
  settingsOpen: false,
  openSettings: () => set({ settingsOpen: true }),
  closeSettings: () => set({ settingsOpen: false }),
}))
