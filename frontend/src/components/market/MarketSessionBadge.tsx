/**
 * MarketSessionBadge — 백엔드 MarketSession 열거형 기반 배지 컴포넌트.
 *
 * 5 상태:
 *  - Open       초록  "장중"          (09:00~15:30 KST)
 *  - PreMarket  주황  "개장 전"       (00:00~09:00 KST 평일)
 *  - PostMarket 노랑  "장 마감 후"    (15:30 이후 평일)
 *  - Closed     회색  "장외/주말"     (토·일)
 *  - Holiday    빨강  "공휴일/임시휴장"
 *
 * 사용 방법:
 *  <MarketSessionBadge session="Open" />
 *  <MarketSessionBadge session={quoteResponse.session} />
 *
 * shadcn/ui Badge primitive 미설치 시 인라인 스타일로 fallback.
 * 토픽 시간대 tooltip 포함 (title attribute).
 *
 * T2-R4: 트랙2 D12 스펙 구현.
 */
import { cn } from '@/lib/cn'
import type { MarketSession, MarketSessionStateValue } from '@/api/types'

interface MarketSessionBadgeProps {
  /**
   * 세션 정보. 다음 형식 모두 허용:
   *  - 객체: `{ state: 'Closed', lastBusinessDate, nextOpenAt }` (백엔드 응답)
   *  - 문자열: `'Closed'` (간단 케이스)
   *  - null/undefined (로딩 중)
   */
  session: MarketSession | MarketSessionStateValue | null | undefined
  className?: string
  /** 작게 표시 (아이콘+라벨만). 기본은 normal. */
  compact?: boolean
}

/** 객체이든 문자열이든 state 값만 추출 */
function extractState(
  session: MarketSession | MarketSessionStateValue | null | undefined,
): MarketSessionStateValue | undefined {
  if (!session) return undefined
  if (typeof session === 'string') return session
  return session.state
}

interface SessionStyle {
  bg: string
  fg: string
  dot: string
  label: string
  tooltip: string
}

const SESSION_STYLE: Record<MarketSessionStateValue, SessionStyle> = {
  Open: {
    bg: 'bg-green-50',
    fg: 'text-green-700',
    dot: 'bg-green-500 animate-pulse',
    label: '장중',
    tooltip: '장중 09:00~15:30 KST',
  },
  PreMarket: {
    bg: 'bg-orange-50',
    fg: 'text-orange-700',
    dot: 'bg-orange-400',
    label: '개장 전',
    tooltip: '장 시작 전 (09:00 KST 개장)',
  },
  PostMarket: {
    bg: 'bg-yellow-50',
    fg: 'text-yellow-700',
    dot: 'bg-yellow-500',
    label: '장 마감 후',
    tooltip: '장 마감 후 (15:30 KST 마감)',
  },
  Closed: {
    bg: 'bg-gray-100',
    fg: 'text-gray-600',
    dot: 'bg-gray-400',
    label: '장외/주말',
    tooltip: '주말 또는 장외 시간',
  },
  Holiday: {
    bg: 'bg-red-50',
    fg: 'text-red-700',
    dot: 'bg-red-400',
    label: '공휴일/임시휴장',
    tooltip: '공휴일 또는 임시 휴장일',
  },
}

/** session이 null/undefined 일 때 사용할 skeleton 스타일 */
const LOADING_STYLE: Omit<SessionStyle, 'label'> & { label: string } = {
  bg: 'bg-gray-100',
  fg: 'text-gray-400',
  dot: 'bg-gray-300 animate-pulse',
  label: '확인 중',
  tooltip: '세션 정보 로딩 중',
}

export default function MarketSessionBadge({
  session,
  className,
  compact = false,
}: MarketSessionBadgeProps) {
  const stateValue = extractState(session)
  const style = stateValue ? SESSION_STYLE[stateValue] : LOADING_STYLE

  if (compact) {
    return (
      <span
        title={style.tooltip}
        className={cn(
          'inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium',
          style.bg,
          style.fg,
          className,
        )}
      >
        <span className={cn('h-1.5 w-1.5 rounded-full flex-shrink-0', style.dot)} />
        {style.label}
      </span>
    )
  }

  return (
    <div
      title={style.tooltip}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border border-gray-200 bg-white/60 px-3 py-1.5 text-xs font-semibold',
        style.fg,
        className,
      )}
    >
      <span className={cn('h-2 w-2 rounded-full flex-shrink-0', style.dot)} />
      {style.label}
      <span className="ml-0.5 font-normal text-[10px] text-muted-foreground">
        {getSessionSubtext(stateValue)}
      </span>
    </div>
  )
}

/** 세션별 보조 문구 (짧게) */
function getSessionSubtext(session: MarketSessionStateValue | null | undefined): string {
  switch (session) {
    case 'Open':
      return '09:00~15:30'
    case 'PreMarket':
      return '개장 대기'
    case 'PostMarket':
      return '시간외'
    case 'Closed':
      return '주말'
    case 'Holiday':
      return '휴장'
    default:
      return ''
  }
}
