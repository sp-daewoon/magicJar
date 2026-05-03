/**
 * StaleIndicator — 시세 데이터 신선도 표시 컴포넌트.
 *
 * source별 동작:
 *  - LIVE           표시 X (아무것도 렌더링 안 함)
 *  - CACHED         시계 아이콘 + "X시 X분 기준" tooltip
 *  - LAST_DAY_CLOSE "전일 종가" 라벨 (회색 배경)
 *
 * stale=true 시 가격 영역을 회색으로 처리하는 className prop 지원.
 *  → 부모에서 <span className={staleIndicator.priceClassName}>가격</span> 패턴.
 *
 * 사용 방법:
 *  <StaleIndicator source={quote.source} stale={quote.stale} asOf={quote.asOf} />
 *
 * T2-R4: 트랙2 D12 스펙 구현.
 */
import { cn } from '@/lib/cn'
import type { QuoteSource } from '@/api/types'

interface StaleIndicatorProps {
  source: QuoteSource | null | undefined
  stale: boolean
  asOf: Date | null | undefined
  className?: string
}

/** asOf Date를 "HH:mm" KST 포맷으로 변환 */
function formatAsOf(date: Date): string {
  return date.toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Seoul',
  })
}

/** asOf Date를 tooltip용 전체 문구로 변환 */
function formatAsOfFull(date: Date): string {
  return date.toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
    timeZone: 'Asia/Seoul',
  })
}

export default function StaleIndicator({
  source,
  stale,
  asOf,
  className,
}: StaleIndicatorProps) {
  // LIVE 또는 stale 아닌 경우 → 아무것도 표시하지 않음
  if (!stale || !source || source === 'LIVE') {
    return null
  }

  if (source === 'LAST_DAY_CLOSE') {
    return (
      <span
        title="전일 종가 기준 (실시간 시세 없음)"
        className={cn(
          'inline-flex items-center rounded bg-gray-200 px-1.5 py-0.5 text-[10px] font-medium text-gray-600',
          className,
        )}
      >
        전일 종가
      </span>
    )
  }

  // CACHED
  const timeText = asOf ? formatAsOf(asOf) : null
  const tooltipText = asOf
    ? `캐시 데이터 — ${formatAsOfFull(asOf)} 기준`
    : '캐시 데이터 (시각 불명)'

  return (
    <span
      title={tooltipText}
      className={cn(
        'inline-flex items-center gap-0.5 rounded bg-amber-50 px-1.5 py-0.5 text-[10px] font-medium text-amber-700',
        className,
      )}
    >
      {/* 시계 아이콘 (SVG inline, 외부 아이콘 라이브러리 미사용) */}
      <svg
        xmlns="http://www.w3.org/2000/svg"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={2}
        strokeLinecap="round"
        strokeLinejoin="round"
        className="h-2.5 w-2.5 flex-shrink-0"
        aria-hidden="true"
      >
        <circle cx="12" cy="12" r="10" />
        <polyline points="12 6 12 12 16 14" />
      </svg>
      {timeText ? `${timeText} 기준` : '캐시'}
    </span>
  )
}

/**
 * stale 여부에 따른 가격 텍스트 CSS 클래스 반환.
 * stale=true → 회색, 아니면 정상 색상 (빨강/파랑 등 부모가 결정).
 */
export function stalePriceClassName(stale: boolean, defaultClass: string = ''): string {
  return stale ? 'text-gray-400' : defaultClass
}
