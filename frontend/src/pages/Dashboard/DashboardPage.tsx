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
 * 모든 위젯은 STOMP push 시 즉시 invalidate + 30s polling fallback (KIS rate limit 보호).
 * mock 합성 0 LOCK — 실패 시 위젯별 오류 표시 (페이지 전체 중단 금지).
 *
 * B-1 (2026-05-20): accountType state 추가 — 실전/모의 토글로 두 잔고 둘 다 조회.
 *   - queryKey 에 accountType 포함되므로 캐시 분리 자동 (REAL/MOCK 동시 보존 가능).
 *   - MOCK 키 미등록 시 backend 412 응답 → 각 카드 isError 분기에서 안내.
 *
 * C-5 (2026-05-20): 토글 클릭 시 immediate refetch — queryClient.invalidateQueries
 *   로 새 accountType queryKey 묶음을 즉시 fetch 트리거. cache miss 시 backend KIS
 *   REST latency (1-3s) 사용자 체감 단축. (사용자 dogfooding: "모의 누르면 바로
 *   계좌정보를 가져오는게 아니고 딜레이가 있는데?")
 *
 * polling → STOMP push 전환 (2026-05-20): useStompAccountBalance subscribe 도입.
 *   주문 체결·취소·거절 즉시 invalidate → 5s polling 폐기, 30s polling 은 fallback only.
 *   "소켓방식으로 가져와야하는거" (사용자 dogfooding) — KIS rate limit burst 해소.
 */
import { useCallback, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import type { AccountType } from '../../api/account'
import { useStompAccountBalance } from '../../api/hooks/useStompAccountBalance'
import { AccountSummaryCard } from './AccountSummaryCard'
import { HoldingsTable } from './HoldingsTable'
import { BalanceCard } from './BalanceCard'
import { AccountTypeToggle } from './AccountTypeToggle'

/**
 * 빠른 진입 링크 목록 (ADR 0040 Watchlist-first 정합).
 *
 * A-2 (2026-05-20): 5중 Link DRY 위반 해소 — 동일 className 30 line 중복 → 단일 정의 + map.
 * 신규 빠른 링크 추가 시 본 배열에 객체 한 줄 push.
 */
const QUICK_LINKS = [
  { to: '/watchlist', label: '관심종목' },
  { to: '/positions', label: 'DB 포지션' },
  { to: '/orders', label: '주문 내역' },
  { to: '/performance', label: '성과 대시보드' },
  { to: '/journal', label: '매매일지' },
] as const

/** 빠른 진입 단일 링크 — 모양·동작 단일 정의 (A-2 DRY). */
function QuickLink({ to, label }: { to: string; label: string }) {
  return (
    <Link
      to={to}
      className="rounded-lg border border-gray-700 bg-gray-900/60 px-3 py-1.5 text-xs text-gray-300 transition-colors hover:bg-gray-800 hover:text-white"
    >
      {label} →
    </Link>
  )
}

export function DashboardPage() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const [accountType, setAccountType] = useState<AccountType>('REAL')
  const queryClient = useQueryClient()

  /**
   * STOMP push 구독 (polling → push 전환 / 2026-05-20).
   *
   * 단일 topic `/topic/account.balance.{userId}` 메시지 수신 시 ['account'] prefix 전체 invalidate.
   * payload.accountType 무관하게 양쪽 (REAL·MOCK) 캐시 동시 invalidate (queryKey 가
   *   ['account', kind, userId, accountType] 구조 → prefix invalidate 시 둘 다 cover).
   * STOMP 끊김 시 useAccountSummary 등 3 hook 의 refetchInterval 30s 가 fallback.
   */
  useStompAccountBalance({ userId, enabled: userId !== null && userId !== undefined })

  /**
   * C-5: 토글 클릭 시 invalidate trigger.
   *
   * queryKey 가 `['account', 'summary' | 'balance' | 'positions', userId, accountType]`
   * 형태라 prefix `['account']` 만 invalidate 해도 충분히 새 type 분기 refetch 강제됨.
   * exact:false 로 모든 account-* 묶음 cover. (REAL→MOCK 또는 MOCK→REAL 양방향)
   */
  const handleAccountTypeChange = useCallback(
    (next: AccountType) => {
      if (next === accountType) return
      setAccountType(next)
      // 새 queryKey 묶음 (accountType 이 next 인 항목) 즉시 refetch 유도.
      queryClient.invalidateQueries({
        queryKey: ['account'],
        exact: false,
      })
    },
    [accountType, queryClient],
  )

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
            KIS 실시간 잔고·보유 종목·평가손익 (실시간 push + 30초 fallback)
            <span className="ml-1 text-gray-600">
              · 현재 {accountType === 'REAL' ? '실전' : '모의'} 계좌
            </span>
          </p>
        </div>
        {/* B-1: 실전/모의 토글 — 클릭 시 모든 하위 카드 동시 전환 */}
        {/* C-5: handleAccountTypeChange 가 invalidateQueries 로 즉시 refetch 강제 */}
        <AccountTypeToggle value={accountType} onChange={handleAccountTypeChange} />
      </div>

      {/* 빠른 진입 링크 (ADR 0040 Watchlist-first 정합) — A-2 DRY: QUICK_LINKS map */}
      <div className="mb-5 flex flex-wrap gap-2">
        {QUICK_LINKS.map((link) => (
          <QuickLink key={link.to} to={link.to} label={link.label} />
        ))}
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
