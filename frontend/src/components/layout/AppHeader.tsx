/**
 * 앱 상단 네비게이션 헤더
 *
 * spec §3-2 E 정합:
 * - "magicJar" 로고 + "대시보드" + "관심종목" 메뉴
 * - <NavLink> — 활성 메뉴 highlight (aria-current 기반)
 * - SetupWizard(/setup)에서는 미표시 (App.tsx에서 제어)
 * - T-SIG Phase 3 (ADR 0066): "전략 목록" 메뉴 추가
 * - T-RISK Phase 3 (ADR 0067): "리스크 설정"·"위반 이력"·"포지션" 메뉴 추가
 * - T-O Phase 3 (ADR 0068): "주문" 메뉴 추가
 */
import { NavLink } from 'react-router-dom'

export function AppHeader() {
  return (
    <header className="sticky top-0 z-50 border-b border-gray-800 bg-gray-950/95 backdrop-blur">
      <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3">
        {/* 로고 */}
        <NavLink
          to="/"
          className="text-xl font-bold tracking-tight text-white hover:text-gray-200"
        >
          magic<span className="text-blue-400">Jar</span>
        </NavLink>

        {/* 네비게이션 메뉴 */}
        <nav className="flex items-center gap-1" aria-label="메인 메뉴">
          <NavLink
            to="/"
            end
            className={({ isActive }) =>
              [
                'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950',
                isActive
                  ? 'bg-gray-800 text-white'
                  : 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200',
              ].join(' ')
            }
          >
            대시보드
          </NavLink>
          <NavLink
            to="/watchlist"
            className={({ isActive }) =>
              [
                'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950',
                isActive
                  ? 'bg-gray-800 text-white'
                  : 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200',
              ].join(' ')
            }
          >
            관심종목
          </NavLink>
          {/* T-SB: 전략 빌더 메뉴 — ADR 0040 §1 */}
          <NavLink
            to="/strategy/builder"
            className={({ isActive }) =>
              [
                'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950',
                isActive
                  ? 'bg-gray-800 text-white'
                  : 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200',
              ].join(' ')
            }
          >
            전략 빌더
          </NavLink>
          {/* T-BT R3: 백테스트 메뉴 — ADR 0061 §4 D-1 */}
          <NavLink
            to="/strategy/backtest"
            className={({ isActive }) =>
              [
                'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950',
                isActive
                  ? 'bg-gray-800 text-white'
                  : 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200',
              ].join(' ')
            }
          >
            백테스트
          </NavLink>
          {/* T-SIG Phase 3: 전략 목록 메뉴 — ADR 0066 */}
          <NavLink
            to="/strategy/list"
            className={({ isActive }) =>
              [
                'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950',
                isActive
                  ? 'bg-gray-800 text-white'
                  : 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200',
              ].join(' ')
            }
          >
            전략 목록
          </NavLink>
          {/* T-RISK Phase 3: 리스크 설정 메뉴 — ADR 0067 */}
          <NavLink
            to="/risk-config"
            className={({ isActive }) =>
              [
                'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950',
                isActive
                  ? 'bg-gray-800 text-white'
                  : 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200',
              ].join(' ')
            }
          >
            리스크 설정
          </NavLink>
          {/* T-RISK Phase 3: 위반 이력 메뉴 — ADR 0067 */}
          <NavLink
            to="/risk-events"
            className={({ isActive }) =>
              [
                'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950',
                isActive
                  ? 'bg-gray-800 text-white'
                  : 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200',
              ].join(' ')
            }
          >
            위반 이력
          </NavLink>
          {/* T-RISK Phase 3: 포지션 메뉴 — ADR 0067 */}
          <NavLink
            to="/positions"
            className={({ isActive }) =>
              [
                'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950',
                isActive
                  ? 'bg-gray-800 text-white'
                  : 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200',
              ].join(' ')
            }
          >
            포지션
          </NavLink>
          {/* T-O Phase 3: 주문 내역 메뉴 — ADR 0068 */}
          <NavLink
            to="/orders"
            className={({ isActive }) =>
              [
                'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950',
                isActive
                  ? 'bg-gray-800 text-white'
                  : 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200',
              ].join(' ')
            }
          >
            주문
          </NavLink>
        </nav>
      </div>
    </header>
  )
}
