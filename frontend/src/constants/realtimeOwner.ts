/**
 * 실시간 구독 owner 문자열 인간화 (/admin/realtime 구독 테이블).
 *
 * 최고 관리자 불만: "owner 문자열(strategy:strategy:86) 의미 불명".
 *
 * backend 계약 (market-data — 정규화된 단일 prefix):
 *   strategy:86 · detail:74683223 · holding:<id> · pending_buy:<id> · 호가 DETAIL 은 detail
 * 단, 롤링 배포 중 legacy 이중 prefix(`strategy:strategy:86`)가 올 수 있어
 * 표시 전 중복 prefix collapse 를 거친다.
 */

/** badge 색상 분류 (Tailwind class 묶음) */
export type OwnerBadgeTone =
  | 'strategy'
  | 'detail'
  | 'holding'
  | 'pending_buy'
  | 'watchlist'
  | 'screener'
  | 'default'

export interface OwnerView {
  /** 배지에 표시할 한국어 라벨 (예: "전략 #86") */
  label: string
  /** 색상 분류 */
  tone: OwnerBadgeTone
  /** hover title — 원본 + 한 줄 설명 */
  title: string
}

/** Tailwind 배지 색상 (tone → class) */
export const OWNER_BADGE_CLASS: Record<OwnerBadgeTone, string> = {
  strategy: 'bg-violet-950 text-violet-300 border-violet-800',
  detail: 'bg-blue-950 text-blue-300 border-blue-800',
  holding: 'bg-emerald-950 text-emerald-300 border-emerald-800',
  pending_buy: 'bg-orange-950 text-orange-300 border-orange-800',
  watchlist: 'bg-teal-950 text-teal-300 border-teal-800',
  screener: 'bg-amber-950 text-amber-300 border-amber-800',
  default: 'bg-gray-800 text-gray-300 border-gray-700',
}

/**
 * 중복 prefix collapse — `strategy:strategy:86` → `strategy:86`.
 * 롤링 배포 중 legacy 이중 prefix 흡수. 같은 prefix 가 연속되면 1개로 접는다.
 */
export function collapseOwnerPrefix(raw: string): string {
  const parts = raw.split(':')
  if (parts.length <= 1) return raw
  const collapsed: string[] = []
  for (const p of parts) {
    // 직전과 같은 토큰이면 중복으로 보고 접는다 (strategy:strategy → strategy)
    if (collapsed.length > 0 && collapsed[collapsed.length - 1] === p) continue
    collapsed.push(p)
  }
  return collapsed.join(':')
}

/** owner legend 항목 (테이블 하단 범례) */
export interface OwnerLegendEntry {
  tone: OwnerBadgeTone
  label: string
  desc: string
}

export const OWNER_LEGEND: OwnerLegendEntry[] = [
  { tone: 'strategy', label: '전략 #N', desc: '전략이 ACTIVE 상태라 이 종목 실시간 시세를 요구 중' },
  { tone: 'detail', label: '상세 보기', desc: '사용자가 종목 상세 화면을 보는 중이라 구독' },
  { tone: 'holding', label: '보유 종목', desc: '보유 포지션 감시를 위해 구독' },
  { tone: 'pending_buy', label: '주문 대기', desc: '매수 주문 대기 중이라 체결가 추적' },
  { tone: 'watchlist', label: '관심목록', desc: '사용자 고정 관심목록' },
  { tone: 'screener', label: '스크리너', desc: '스크리너 후보 평가' },
]

/**
 * owner 문자열 → 인간화 view.
 *
 * @example ownerView('strategy:86')        // { label: '전략 #86', tone: 'strategy', ... }
 * @example ownerView('detail:74683223')    // { label: '상세 보기 (74683223)', tone: 'detail', ... }
 * @example ownerView('strategy:strategy:86')// collapse 후 '전략 #86'
 */
export function ownerView(raw: string): OwnerView {
  const normalized = collapseOwnerPrefix(raw.trim())
  const colon = normalized.indexOf(':')
  const prefix = (colon >= 0 ? normalized.slice(0, colon) : normalized).toLowerCase()
  const arg = colon >= 0 ? normalized.slice(colon + 1) : ''

  switch (prefix) {
    case 'strategy':
      return {
        label: arg ? `전략 #${arg}` : '전략',
        tone: 'strategy',
        title: `${raw} — 전략 #${arg || '?'}이 ACTIVE 상태라 이 종목 실시간 시세를 요구 중`,
      }
    case 'detail':
      return {
        label: arg ? `상세 보기 (${arg})` : '상세 보기',
        tone: 'detail',
        title: `${raw} — 사용자가 종목 상세 화면을 보는 중이라 구독(이탈 시 해제)`,
      }
    case 'holding':
      return {
        label: '보유 종목',
        tone: 'holding',
        title: `${raw} — 보유 포지션 감시를 위해 실시간 시세 구독`,
      }
    case 'pending_buy':
      return {
        label: '주문 대기',
        tone: 'pending_buy',
        title: `${raw} — 매수 주문 대기 중이라 체결가 추적`,
      }
    case 'watchlist':
      return {
        label: '관심목록',
        tone: 'watchlist',
        title: `${raw} — 사용자 고정 관심목록`,
      }
    case 'screener':
      return {
        label: '스크리너',
        tone: 'screener',
        title: `${raw} — 스크리너 후보 평가`,
      }
    default:
      return {
        label: normalized || '(owner 없음)',
        tone: 'default',
        title: raw,
      }
  }
}
