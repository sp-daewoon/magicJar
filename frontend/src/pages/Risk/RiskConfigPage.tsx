/**
 * RiskConfigPage — /risk-config (T-RISK Phase 3 — ADR 0067)
 *
 * 리스크 설정 페이지:
 * - RiskConfigForm 렌더링
 * - 빈 config → default seed 표시 + 사용자 갱신 유도 (이미 form 내부에서 처리)
 * - userId 없으면 셋업 안내 (StrategyListPage 패턴 정합)
 */
import { Link } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import { RiskConfigForm } from '../../components/risk/RiskConfigForm'

export function RiskConfigPage() {
  const userId = useOnboardingStore((s) => s.registeredUserId)

  if (!userId) {
    return (
      <div className="mx-auto flex max-w-2xl flex-col items-center gap-6 px-4 py-24 text-center">
        <h1 className="text-2xl font-bold text-white">리스크 설정</h1>
        <p className="text-sm text-gray-400">KIS 계좌 등록을 완료하면 리스크 설정을 사용할 수 있습니다.</p>
        <Link
          to="/setup"
          className="rounded-lg bg-blue-600 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950"
        >
          계좌 등록하러 가기
        </Link>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-8">
      {/* 페이지 헤더 */}
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-white">리스크 설정</h1>
        <p className="mt-2 text-sm text-gray-500">
          자동매매 전략의 손실 한도·포지션 한도·거래 빈도를 설정합니다.
          설정값 초과 시 주문이 차단되거나 해당 전략의 신규 BUY가 자동 차단됩니다.
        </p>
      </div>

      {/* 설정 폼 */}
      <RiskConfigForm userId={userId} />
    </div>
  )
}
