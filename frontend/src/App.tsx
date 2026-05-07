/**
 * 라우터 최상위 컴포넌트 + 온보딩 라우팅 가드
 *
 * spec §5-2 (Step 1 라우팅 가드) 정합:
 * - 진입 시 GET /api/onboarding/status 조회
 * - hasUserAccount=false + path !== '/setup' → Navigate to="/setup"
 * - hasUserAccount=true + path === '/setup' → Navigate to="/" (이미 완료된 사용자 차단)
 * - isLoading → 스플래시 표시
 *
 * 라우트 (PR-5b 구조 유지 + PR-6 확장):
 *   /        -> 대시보드 (T-W1 Watchlist 진입점 — PR-7에서 구현)
 *   /setup   -> 셋업 마법사 7단계 (T-ONBOARD)
 *   *        -> / 리다이렉트
 */
import type { ReactNode } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useOnboardingStatus } from './api/onboarding'
import { DashboardPlaceholder } from './pages/DashboardPlaceholder'
import { SetupWizard } from './pages/SetupWizard'

/** 온보딩 라우팅 가드 — status 기반 자동 분기 */
function OnboardingGuard({ children }: { children: ReactNode }) {
  const location = useLocation()
  const { data, isLoading, isError } = useOnboardingStatus()

  // 로딩 중 스플래시
  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-950">
        <div className="flex flex-col items-center gap-4">
          <div className="h-10 w-10 animate-spin rounded-full border-4 border-gray-800 border-t-blue-500" />
          <p className="text-sm text-gray-500">로딩 중...</p>
        </div>
      </div>
    )
  }

  // status 조회 실패 시 — /setup은 항상 접근 허용, 나머지는 그대로
  if (isError) {
    return <>{children}</>
  }

  const isSetupPath = location.pathname === '/setup'

  // 셋업 미완 + /setup 이외 경로 → /setup 이동
  if (data && !data.hasUserAccount && !isSetupPath) {
    return <Navigate to="/setup" replace />
  }

  // 셋업 완료 + /setup 진입 → / 이동 (중복 진입 차단)
  if (data && data.hasUserAccount && isSetupPath) {
    return <Navigate to="/" replace />
  }

  return <>{children}</>
}

export function App() {
  return (
    <OnboardingGuard>
      <Routes>
        <Route path="/" element={<DashboardPlaceholder />} />
        <Route path="/setup" element={<SetupWizard />} />
        {/* 미정의 경로는 / 로 리다이렉트 */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </OnboardingGuard>
  )
}
