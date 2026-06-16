/**
 * StrategyPerformancePage — 전략 성과·메모 풀스크린 페이지 (T-PERF R3 — ADR 0126)
 *
 * 라우트: /strategy/:id/performance
 * 모달과 동일한 공용 컴포넌트(StrategyPerformanceContent)를 풀스크린으로 렌더.
 *
 * userId: useOnboardingStore.registeredUserId (미등록 시 셋업 안내)
 * 룰 5: LLM provider import 0건.
 */
import { Link, useParams } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import { useSigStrategy } from '../../api/hooks/useSigStrategy'
import { StrategyPerformanceContent } from '../../components/strategy/StrategyPerformanceContent'

export function StrategyPerformancePage() {
  const { id } = useParams<{ id: string }>()
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const strategyQuery = useSigStrategy(id)

  const strategyId = id ? Number(id) : NaN

  if (!id || Number.isNaN(strategyId)) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-24 text-center">
        <p className="text-sm text-gray-400">잘못된 전략 경로입니다</p>
        <Link to="/strategy/list" className="mt-4 inline-block text-sm text-blue-400 hover:text-blue-300">
          전략 목록으로
        </Link>
      </div>
    )
  }

  if (!userId) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-24 text-center">
        <h1 className="text-2xl font-bold text-white">전략 성과·메모</h1>
        <p className="mt-3 text-sm text-gray-400">
          KIS 계좌 등록을 완료하면 전략 성과를 확인할 수 있습니다.
        </p>
        <Link
          to="/setup"
          className="mt-6 inline-block rounded-lg bg-blue-600 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-500"
        >
          계좌 등록하러 가기
        </Link>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-6 sm:px-6">
      <div className="mb-6 flex items-center justify-between gap-3">
        <div>
          <h1 className="text-lg font-bold text-gray-100">전략 성과·메모</h1>
          <p className="mt-0.5 text-xs text-gray-500">
            하루하루 누적된 성과와 그날의 시장 맥락·메모 (ADR 0126)
          </p>
        </div>
        <Link
          to="/strategy/list"
          className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800"
        >
          전략 목록
        </Link>
      </div>

      <StrategyPerformanceContent
        strategyId={strategyId}
        strategyName={strategyQuery.data?.name}
        userId={userId}
      />
    </div>
  )
}
