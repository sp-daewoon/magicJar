/**
 * Orderbook — 10단계 매도/매수 호가창 컴포넌트.
 *
 * 레이아웃:
 *  - 매도 1~10 (위, 빨강) ↓ 가격 내림차순 (askp10이 가장 위, askp1이 중앙 위)
 *  - 매수 1~10 (아래, 파랑) ↓ 가격 내림차순 (bidp1이 중앙 아래, bidp10이 가장 아래)
 *  - 우측에 잔량 막대 (max 잔량 대비 비율)
 *  - 데이터 없음 시 "데이터 없음" 안내 (mock 합성 X — 사용자 정책)
 *
 * Refs: plan T2-R2-A2 §T16.
 */
import { useOrderbookStream } from '@/hooks/useOrderbookStream'
import type { OrderbookMessage, OrderbookLevelMessage } from '@/api/types'

interface OrderbookProps {
  code: string
}

function formatKrw(value: number): string {
  return value.toLocaleString('ko-KR')
}

function formatQty(value: number): string {
  return value.toLocaleString('ko-KR')
}

function maxQty(book: OrderbookMessage | null): number {
  if (!book) return 1
  const all = [...book.asks, ...book.bids].map((l) => l.quantity)
  return Math.max(1, ...all)
}

function LevelRow({
  level,
  side,
  maxQ,
}: {
  level: OrderbookLevelMessage
  side: 'ask' | 'bid'
  maxQ: number
}) {
  const colorClass = side === 'ask' ? 'text-blue-600' : 'text-red-600'
  const bgClass = side === 'ask' ? 'bg-blue-100' : 'bg-red-100'
  const ratio = Math.min(100, (level.quantity / maxQ) * 100)

  return (
    <div className="grid grid-cols-[1fr_2fr] items-center gap-2 px-2 py-1 text-xs">
      <span className={`text-right font-semibold tabular-nums ${colorClass}`}>
        {level.price > 0 ? formatKrw(level.price) : '—'}
      </span>
      <div className="relative h-5 overflow-hidden rounded">
        <div
          className={`absolute inset-y-0 left-0 ${bgClass}`}
          style={{ width: `${ratio}%` }}
        />
        <span className="absolute inset-0 flex items-center justify-end pr-2 text-right tabular-nums text-gray-700">
          {level.quantity > 0 ? formatQty(level.quantity) : '—'}
        </span>
      </div>
    </div>
  )
}

export default function Orderbook({ code }: OrderbookProps) {
  const book = useOrderbookStream(code)

  if (!book) {
    return (
      <div className="flex h-[400px] items-center justify-center rounded border border-dashed border-gray-300 text-sm text-muted-foreground">
        호가 데이터 미수신 — 장 시작 후 KIS WS 1초 갱신
      </div>
    )
  }

  const maxQ = maxQty(book)
  // 매도: 가격 내림차순 (askp10 → askp1) 이 위에서 아래로
  const asksDesc = [...book.asks].reverse()
  // 매수: 가격 내림차순 (bidp1 → bidp10)
  const bidsDesc = book.bids

  return (
    <div className="flex flex-col rounded border border-gray-200 bg-white">
      <div className="border-b px-3 py-2 text-xs font-semibold text-muted-foreground">
        호가 (10단계)
        {book.stale && (
          <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] text-amber-700">
            stale
          </span>
        )}
      </div>
      {/* 매도 10단계 — 위에서 아래로 askp10 → askp1 */}
      <div className="border-b">
        {asksDesc.map((lvl, i) => (
          <LevelRow key={`ask-${i}`} level={lvl} side="ask" maxQ={maxQ} />
        ))}
      </div>
      {/* 매수 10단계 — bidp1 → bidp10 */}
      <div>
        {bidsDesc.map((lvl, i) => (
          <LevelRow key={`bid-${i}`} level={lvl} side="bid" maxQ={maxQ} />
        ))}
      </div>
      {/* 합계 */}
      <div className="grid grid-cols-2 gap-2 border-t px-3 py-2 text-[11px] text-muted-foreground">
        <div className="text-right">
          매도 합 <span className="ml-1 tabular-nums text-blue-600">{formatQty(book.totalAskQty)}</span>
        </div>
        <div className="text-right">
          매수 합 <span className="ml-1 tabular-nums text-red-600">{formatQty(book.totalBidQty)}</span>
        </div>
      </div>
    </div>
  )
}
