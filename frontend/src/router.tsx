import { createBrowserRouter } from 'react-router-dom'
import { lazy, Suspense } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { SettingsModal } from '@/components/settings/SettingsModal'
import InstrumentSearch from '@/components/header/InstrumentSearch'
import { useAuthStatus, DEFAULT_USER_ID } from '@/hooks/useAuth'
import { useUiStore } from '@/stores/ui'
import type { KisMode } from '@/api/auth'

const DashboardPage = lazy(() => import('@/pages/Dashboard'))
const BacktestPage = lazy(() => import('@/pages/Backtest'))
const JournalPage = lazy(() => import('@/pages/Journal'))
const StrategyPage = lazy(() => import('@/pages/Strategy'))
const StrategyBuilderPage = lazy(() => import('@/pages/StrategyBuilder'))
const SymbolDetailPage = lazy(() => import('@/pages/SymbolDetail'))
const OrdersPage = lazy(() => import('@/pages/Orders'))
const DevlogPage = lazy(() => import('@/pages/Devlog'))

function LoadingFallback() {
  return (
    <div className="flex h-screen items-center justify-center">
      <div className="text-muted-foreground">로딩 중...</div>
    </div>
  )
}

/** 헤더 우측 인증 모드 배지 */
function ModeBadge({ mode }: { mode: KisMode | null }) {
  if (!mode) return null
  if (mode === 'prod') {
    return (
      <Badge variant="destructive" className="text-xs px-2 py-0.5">
        실전
      </Badge>
    )
  }
  return (
    <Badge className="bg-yellow-500 text-white hover:bg-yellow-500 text-xs px-2 py-0.5">
      모의
    </Badge>
  )
}

/** 헤더 — 설정 버튼 + 인증 모드 배지 */
function HeaderActions() {
  const { settingsOpen, openSettings, closeSettings } = useUiStore()
  const { data: authStatus } = useAuthStatus(DEFAULT_USER_ID)

  return (
    <>
      <div className="flex items-center gap-2">
        {authStatus && <ModeBadge mode={authStatus.mode} />}
        <Button
          variant="ghost"
          size="icon"
          onClick={openSettings}
          aria-label="설정 열기"
          title="설정"
          className="h-8 w-8"
        >
          {/* 설정(톱니바퀴) 아이콘 */}
          <svg
            xmlns="http://www.w3.org/2000/svg"
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" />
            <circle cx="12" cy="12" r="3" />
          </svg>
        </Button>
      </div>

      <SettingsModal open={settingsOpen} onClose={closeSettings} />
    </>
  )
}

function RootLayout({ children }: { children?: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-background">
      <nav className="border-b px-4 py-2 flex items-center gap-6">
        <a href="/" className="font-bold text-primary">
          magicJar
        </a>
        <a href="/" className="text-sm hover:underline">
          대시보드
        </a>
        <a href="/backtest" className="text-sm hover:underline">
          백테스트
        </a>
        <a href="/journal" className="text-sm hover:underline">
          매매일지
        </a>
        <a href="/strategy" className="text-sm hover:underline">
          전략
        </a>
        <a href="/strategy/builder" className="text-sm hover:underline">
          전략빌더
        </a>
        <a href="/symbol/005930" className="text-sm hover:underline">
          종목상세
        </a>
        <a href="/orders" className="text-sm hover:underline">
          주문
        </a>
        <a href="/devlog" className="text-sm hover:underline flex items-center gap-1">
          {/* BookOpen icon (lucide-react SVG inline) */}
          <svg
            xmlns="http://www.w3.org/2000/svg"
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z" />
            <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z" />
          </svg>
          개발일지
        </a>
        {/* 헤더 검색창 (T20-T6) + 우측 정렬: 인증 상태 배지 + 설정 버튼 */}
        <div className="ml-auto flex items-center gap-3">
          <InstrumentSearch />
          <HeaderActions />
        </div>
      </nav>
      <main className="p-4">
        <Suspense fallback={<LoadingFallback />}>{children}</Suspense>
      </main>
    </div>
  )
}

export const router = createBrowserRouter([
  {
    path: '/',
    element: (
      <RootLayout>
        <DashboardPage />
      </RootLayout>
    ),
  },
  {
    path: '/backtest',
    element: (
      <RootLayout>
        <BacktestPage />
      </RootLayout>
    ),
  },
  {
    path: '/journal',
    element: (
      <RootLayout>
        <JournalPage />
      </RootLayout>
    ),
  },
  {
    path: '/strategy',
    element: (
      <RootLayout>
        <StrategyPage />
      </RootLayout>
    ),
  },
  {
    path: '/strategy/builder',
    element: (
      <RootLayout>
        <StrategyBuilderPage />
      </RootLayout>
    ),
  },
  {
    path: '/symbol/:code',
    element: (
      <RootLayout>
        <SymbolDetailPage />
      </RootLayout>
    ),
  },
  {
    path: '/orders',
    element: (
      <RootLayout>
        <OrdersPage />
      </RootLayout>
    ),
  },
  {
    path: '/devlog',
    element: (
      <RootLayout>
        <DevlogPage />
      </RootLayout>
    ),
  },
])
