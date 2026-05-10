/**
 * 앱 상단 네비게이션 헤더
 *
 * spec §3-2 E 정합:
 * - "magicJar" 로고 + "대시보드" + "관심종목" 메뉴
 * - <NavLink> — 활성 메뉴 highlight (aria-current 기반)
 * - SetupWizard(/setup)에서는 미표시 (App.tsx에서 제어)
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
        </nav>
      </div>
    </header>
  )
}
