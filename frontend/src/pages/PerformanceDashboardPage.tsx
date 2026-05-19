/**
 * PerformanceDashboardPage — /performance 라우트
 *
 * ADR 0072 §2-6 F-1 + spec §3-3 Phase 3 정합:
 * - PeriodToggle (DAY·WEEK·MONTH·YEAR·ALL)
 * - 5 KPI 카드 (P&L·MDD·승률·평균 손익비·거래 수)
 * - EquityCurveChart (LWC v4 — cumulativePnl 시계열)
 * - DailyPnlBarChart (LWC v4 — dailyPnl histogram)
 * - userId: useOnboardingStore.registeredUserId (null 시 setup 안내)
 * - mock 합성 0 — 빈 결과 "성과 데이터가 없습니다" 명시
 */
import { useState } from 'react'
import { useOnboardingStore } from '../stores/onboarding'
import { PerformancePeriodSelector } from '../features/performance/components/PerformancePeriodSelector'
import { PerformanceSummaryCards } from '../features/performance/components/PerformanceSummaryCards'
import { EquityCurveChart } from '../features/performance/components/EquityCurveChart'
import { DailyPnlBarChart } from '../features/performance/components/DailyPnlBarChart'
import { usePerformanceSummary } from '../features/performance/hooks/usePerformanceSummary'
import { useEquityCurve } from '../features/performance/hooks/useEquityCurve'
import type { PerformancePeriod } from '../features/performance/api/performanceApi'

export function PerformanceDashboardPage() {
  // ADR 0089 Phase 2 — registeredUserId now number, no conversion needed
  const userId = useOnboardingStore((s) => s.registeredUserId)

  const [period, setPeriod] = useState<PerformancePeriod>('MONTH')

  const {
    data: summary,
    isLoading: summaryLoading,
    isError: summaryError,
  } = usePerformanceSummary({ userId, period })

  const {
    data: equityCurve,
    isLoading: curveLoading,
    isError: curveError,
  } = useEquityCurve({ userId })

  // userId 미등록 → setup 안내
  if (!userId) {
    return (
      <div className="mx-auto max-w-7xl px-4 py-10">
        <div className="rounded-lg border border-yellow-800 bg-yellow-900/20 p-6 text-center">
          <p className="text-sm text-yellow-300">사용자 설정이 완료되지 않았습니다.</p>
          <p className="mt-1 text-xs text-yellow-400">
            <a href="/setup" className="underline hover:text-yellow-200">
              설정 마법사
            </a>
            에서 계좌를 등록해 주세요.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-6">
      {/* 페이지 헤더 */}
      <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-gray-100">성과 대시보드</h1>
          <p className="mt-0.5 text-sm text-gray-500">
            P&amp;L · MDD · 승률 · 평균 손익비 · Equity Curve
          </p>
        </div>
        <PerformancePeriodSelector value={period} onChange={setPeriod} />
      </div>

      {/* 5 KPI 카드 */}
      <div className="mb-6">
        <PerformanceSummaryCards
          summary={summary}
          isLoading={summaryLoading}
          isError={summaryError}
        />
      </div>

      {/* Equity Curve */}
      <div className="mb-4">
        <h2 className="mb-2 text-sm font-semibold text-gray-300">Equity Curve (누적 손익)</h2>
        <EquityCurveChart
          points={equityCurve?.points}
          isLoading={curveLoading}
          isError={curveError}
        />
      </div>

      {/* Daily P&L Bar */}
      <div>
        <h2 className="mb-2 text-sm font-semibold text-gray-300">일별 P&amp;L</h2>
        <DailyPnlBarChart
          points={equityCurve?.points}
          isLoading={curveLoading}
          isError={curveError}
        />
      </div>

      {/* 캐시 HIT 표시 */}
      {summary?.cacheHit && (
        <p className="mt-3 text-right text-xs text-gray-600">캐시 HIT</p>
      )}
    </div>
  )
}
