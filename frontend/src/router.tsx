import { createBrowserRouter } from 'react-router-dom'
import { lazy, Suspense } from 'react'

const DashboardPage = lazy(() => import('@/pages/Dashboard'))
const BacktestPage = lazy(() => import('@/pages/Backtest'))
const JournalPage = lazy(() => import('@/pages/Journal'))
const StrategyPage = lazy(() => import('@/pages/Strategy'))
const HarnessOpsPage = lazy(() => import('@/pages/HarnessOps'))

function LoadingFallback() {
  return (
    <div className="flex h-screen items-center justify-center">
      <div className="text-muted-foreground">로딩 중...</div>
    </div>
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
        <a href="/harness" className="text-sm hover:underline">
          하네스
        </a>
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
    path: '/harness',
    element: (
      <RootLayout>
        <HarnessOpsPage />
      </RootLayout>
    ),
  },
])
