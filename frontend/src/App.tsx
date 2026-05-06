import { Routes, Route, Navigate } from 'react-router-dom'
import { DashboardPlaceholder } from './pages/DashboardPlaceholder'
import { SetupWizardPlaceholder } from './pages/SetupWizardPlaceholder'

/**
 * 라우터 최상위 컴포넌트.
 *
 * 현재 라우트 (PR-5b 스캐폴드):
 *   /        -> 대시보드 (T-W1 Watchlist 진입점 — PR-7에서 구현)
 *   /setup   -> 셋업 마법사 7단계 (T-ONBOARD — PR-6에서 구현)
 *   *        -> / 리다이렉트
 *
 * PR-6+에서 추가될 라우트:
 *   /watchlist       -> T-W1 관심종목 화면
 *   /dashboard       -> 메인 대시보드 (차트 + 주문 + 포지션)
 *   /journal         -> 매매일지 (T-J)
 *   /strategy        -> 전략 빌더 (T-SB)
 */
export function App() {
  return (
    <Routes>
      <Route path="/" element={<DashboardPlaceholder />} />
      <Route path="/setup" element={<SetupWizardPlaceholder />} />
      {/* 미정의 경로는 / 로 리다이렉트 */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
