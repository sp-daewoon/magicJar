/**
 * DashboardPage — 메인 대시보드 격상 (T-ACCT Phase 4 — ADR 0081 + spec §5-1)
 *
 * 진입: `/` (App.tsx 라우트)
 * 구성 (위→아래):
 *   1. 페이지 헤더 — 환영 메시지 + 빠른 진입 링크 + 실전/모의 토글 (B-1)
 *   2. AccountSummaryCard — 총자산·예수금·평가손익·종목 수 4 셀
 *   3. HoldingsTable — KIS 실시간 보유 종목 8 컬럼
 *   4. BalanceCard — 예수금·자산·매입평가 3 그룹 상세
 *
 * userId 미등록 → /setup 안내 (PerformanceDashboardPage 패턴 정합).
 * 모든 위젯은 30s auto-refresh (Redis TTL 30s 정합 — KIS 호출 1회 / 30s).
 * mock 합성 0 LOCK — 실패 시 위젯별 오류 표시 (페이지 전체 중단 금지).
 *
 * B-1 (2026-05-20): accountType state 추가 — 실전/모의 토글로 두 잔고 둘 다 조회.
 *   - queryKey 에 accountType 포함되므로 캐시 분리 자동 (REAL/MOCK 동시 보존 가능).
 *   - MOCK 키 미등록 시 backend 412 응답 → 각 카드 isError 분기에서 안내.
 */
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import type { AccountType } from '../../api/account'
import { AccountSummaryCard } from './AccountSummaryCard'
import { HoldingsTable } from './HoldingsTable'
import { BalanceCard } from './BalanceCard'
import { AccountTypeToggle } from './AccountTypeToggle'

export function DashboardPage() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const [accountType, setAccountType] = useState<AccountType>('REAL')

  // userId 미등록 → 셋업 안내
  if (!userId) {
    return (
      <div className="mx-auto flex max-w-2xl flex-col items-center gap-6 px-4 py-24 text-center">
        <div>
          <div className="mb-2 text-4xl font-bold tracking-tight text-white">
            magic<span className="text-blue-400">Jar</span>
          </div>
          <p className="text-sm text-gray-400">마법의항아리 — 한국 주식 자동매매 시스템</p>
        </div>
        <div className="w-full rounded-2xl border border-yellow-800 bg-yellow-900/20 p-6">
          <p className="text-sm text-yellow-300">
            계좌 설정이 완료되지 않았습니다.
          </p>
          <p className="mt-1 text-xs text-yellow-400">
            KIS 계좌를 등록하면 실시간 잔고·보유 종목·평가손익을 확인할 수 있습니다.
          </p>
        </div>
        <Link
          to="/setup"
          className="rounded-lg bg-blue-600 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950"
        >
          계좌 등록하러 가기 →
        </Link>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-6">
      {/* 페이지 헤더 */}
      <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-gray-100">대시보드</h1>
          <p className="mt-0.5 text-sm text-gray-500">
            KIS 실시간 잔고·보유 종목·평가손익 (30초마다 자동 갱신)
            <span className="ml-1 text-gray-600">
              · 현재 {accountType === 'REAL' ? '실전' : '모의'} 계좌
            </span>
          </p>
        </div>
        {/* B-1: 실전/모의 토글 — 클릭 시 모든 하위 카드 동시 전환 */}
        <AccountTypeToggle value={accountType} onChange={setAccountType} />
      </div>

      {/* 빠른 진입 링크 (ADR 0040 Watchlist-first 정합) */}
      <div className="mb-5 flex flex-wrap gap-2">
        <Link
          to="/watchlist"
          className="rounded-lg border border-gray-700 bg-gray-900/60 px-3 py-1.5 text-xs text-gray-300 transition-colors hover:bg-gray-800 hover:text-white"
        >
          관심종목 →
        </Link>
        <Link
          to="/positions"
          className="rounded-lg border border-gray-700 bg-gray-900/60 px-3 py-1.5 text-xs text-gray-300 transition-colors hover:bg-gray-800 hover:text-white"
        >
          DB 포지션 →
        </Link>
        <Link
          to="/orders"
          className="rounded-lg border border-gray-700 bg-gray-900/60 px-3 py-1.5 text-xs text-gray-300 transition-colors hover:bg-gray-800 hover:text-white"
        >
          주문 내역 →
        </Link>
        <Link
          to="/performance"
          className="rounded-lg border border-gray-700 bg-gray-900/60 px-3 py-1.5 text-xs text-gray-300 transition-colors hover:bg-gray-800 hover:text-white"
        >
          성과 대시보드 →
        </Link>
        <Link
          to="/journal"
          className="rounded-lg border border-gray-700 bg-gray-900/60 px-3 py-1.5 text-xs text-gray-300 transition-colors hover:bg-gray-800 hover:text-white"
        >
          매매일지 →
        </Link>
      </div>

      {/* 1. 계좌 요약 카드 (4 셀) */}
      <div className="mb-5">
        <AccountSummaryCard userId={userId} accountType={accountType} />
      </div>

      {/* 2. 보유 종목 테이블 */}
      <div className="mb-5">
        <HoldingsTable userId={userId} accountType={accountType} />
      </div>

      {/* 3. 잔고 상세 카드 */}
      <div>
        <BalanceCard userId={userId} accountType={accountType} />
      </div>
    </div>
  )
}
