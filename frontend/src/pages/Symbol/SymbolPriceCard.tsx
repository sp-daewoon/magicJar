/**
 * SymbolPriceCard — 현재가·전일대비·등락률·high·low·open·volume + stale 배지
 *
 * spec §6-2 정합:
 * - stale=true → 노란색 "장 마감" 배지 (ADR 0014 T3)
 * - source='closed' → "장 마감" 표시
 * - 상승(change>0) → 빨간색, 하락(change<0) → 파란색 (한국 주식 관례)
 * - 숫자 포맷: 천 단위 콤마 (ko-KR) + 원화 표시
 */
import type { SymbolDto, TickPayload } from '../../api/symbol'

interface SymbolPriceCardProps {
  symbol: SymbolDto
  realtimeTick?: TickPayload | null
}

function formatPrice(value: string | number): string {
  const num = typeof value === 'string' ? parseFloat(value) : value
  if (isNaN(num)) return '-'
  return num.toLocaleString('ko-KR')
}

function formatVolume(value: number): string {
  if (!value) return '-'
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}백만`
  if (value >= 1_000) return `${(value / 1_000).toFixed(0)}천`
  return value.toLocaleString('ko-KR')
}

function formatChangeRate(value: string): string {
  const num = parseFloat(value)
  if (isNaN(num)) return '-'
  const sign = num >= 0 ? '+' : ''
  return `${sign}${num.toFixed(2)}%`
}

function formatChange(value: string): string {
  const num = parseFloat(value)
  if (isNaN(num)) return '-'
  const sign = num >= 0 ? '+' : ''
  return `${sign}${num.toLocaleString('ko-KR')}`
}

/** 가격 변동에 따른 색상 클래스 (한국: 상승=빨강, 하락=파랑) */
function getChangeColorClass(changeStr: string): string {
  const change = parseFloat(changeStr)
  if (isNaN(change) || change === 0) return 'text-gray-300'
  return change > 0 ? 'text-red-400' : 'text-blue-400'
}

export function SymbolPriceCard({ symbol, realtimeTick }: SymbolPriceCardProps) {
  // STOMP tick이 있으면 실시간 가격 사용, 없으면 REST 응답 사용
  const currentPrice = realtimeTick?.price ?? symbol.currentPrice
  const isStale = realtimeTick?.stale ?? symbol.stale
  const source = realtimeTick?.source ?? symbol.source

  const changeColorClass = getChangeColorClass(symbol.change)

  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      {/* 현재가 헤더 */}
      <div className="flex items-start justify-between gap-3 mb-3">
        <div>
          <div className="text-xs text-gray-500 mb-1">현재가</div>
          <div className="flex items-baseline gap-2">
            <span className="text-2xl font-bold text-white tabular-nums">
              {formatPrice(currentPrice)}
            </span>
            <span className="text-sm text-gray-500">원</span>
          </div>
        </div>

        {/* 등락 정보 */}
        <div className={`text-right ${changeColorClass}`}>
          <div className="text-base font-semibold tabular-nums">
            {formatChange(symbol.change)}
          </div>
          <div className="text-sm tabular-nums">
            {formatChangeRate(symbol.changeRate)}
          </div>
        </div>
      </div>

      {/* stale / source 배지 */}
      {(isStale || source === 'closed') && (
        <div className="mb-3">
          <span className="inline-flex items-center gap-1 rounded-full bg-yellow-900/40 px-2 py-0.5 text-xs text-yellow-400">
            <span className="h-1.5 w-1.5 rounded-full bg-yellow-400" aria-hidden="true" />
            {source === 'closed' ? '장 마감' : '지연 데이터'}
          </span>
        </div>
      )}
      {!isStale && source === 'live' && (
        <div className="mb-3">
          <span className="inline-flex items-center gap-1 rounded-full bg-green-900/40 px-2 py-0.5 text-xs text-green-400">
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-green-400" aria-hidden="true" />
            실시간
          </span>
        </div>
      )}

      {/* 보조 지표: 고·저·시·거래량 */}
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 border-t border-gray-800 pt-3">
        <div className="flex justify-between">
          <dt className="text-xs text-gray-500">고가</dt>
          <dd className="text-xs font-mono text-red-300 tabular-nums">{formatPrice(symbol.high)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-xs text-gray-500">저가</dt>
          <dd className="text-xs font-mono text-blue-300 tabular-nums">{formatPrice(symbol.low)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-xs text-gray-500">시가</dt>
          <dd className="text-xs font-mono text-gray-300 tabular-nums">{formatPrice(symbol.open)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-xs text-gray-500">전일종가</dt>
          <dd className="text-xs font-mono text-gray-300 tabular-nums">{formatPrice(symbol.previousClose)}</dd>
        </div>
        <div className="flex justify-between col-span-2">
          <dt className="text-xs text-gray-500">거래량</dt>
          <dd className="text-xs font-mono text-gray-300 tabular-nums">{formatVolume(symbol.volume)}</dd>
        </div>
      </dl>
    </div>
  )
}
