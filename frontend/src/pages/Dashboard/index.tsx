/**
 * Dashboard 페이지 — /
 *
 * 구성:
 *  - 상단: MarketSessionBadge + 수동 주문 패널
 *  - KOSPI 카드 + KOSDAQ 카드 (2열 grid)
 *    - 지수명·현재값·전일대비·등락률 색상 (빨강=양수, 파랑=음수)
 *  - 보유 포지션 목록 (포지션 있을 때만)
 *
 * 데이터:
 *  - GET /api/market/index?codes=KOSPI,KOSDAQ (1분 refetch)
 *  - API 응답 없음 시 "지수 데이터 미수신" 안내 표시 (mock 합성 없음)
 */
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import MarketSessionBadge from '@/components/market/MarketSessionBadge'
import ManualOrderPanel from '@/components/ManualOrderPanel'
import { useMarketIndex } from '@/hooks/useMarketIndex'
import { usePositionStore } from '@/stores/position'
import { useWsConnectionState } from '@/hooks/useWsConnectionState'
import type { MarketIndex } from '@/api/types'

/** 지수 등락률 색상 */
function changeColorClass(changePercent: number): string {
  if (changePercent > 0) return 'text-red-500'
  if (changePercent < 0) return 'text-blue-500'
  return 'text-muted-foreground'
}

/** 등락률 포맷 (부호 포함) */
function formatChangePercent(changePercent: number): string {
  const sign = changePercent >= 0 ? '+' : ''
  return `${sign}${changePercent.toFixed(2)}%`
}

/** 포인트 변동 포맷 (부호 포함) */
function formatChangeAmount(changeAmount: number): string {
  const sign = changeAmount >= 0 ? '+' : ''
  return `${sign}${changeAmount.toFixed(2)}`
}

/** 천 단위 콤마 포맷 */
function formatKrw(value: number): string {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    minimumFractionDigits: 0,
  }).format(value)
}

// ── 지수 카드 ─────────────────────────────────────────────────────────────────

interface IndexCardProps {
  index: MarketIndex
}

function IndexCard({ index }: IndexCardProps) {
  // Backend 응답이 일부 필드 미제공 또는 0 가능 — 안전 기본값.
  const currentValue = index.currentValue ?? 0
  const prevClose = index.prevClose ?? 0
  const changePercent = index.changePercent ?? 0
  // changeAmount는 backend 응답에 없음 — 직접 계산 (prevClose=0이면 0).
  const changeAmount = prevClose > 0 ? currentValue - prevClose : 0
  const colorClass = changeColorClass(changePercent)

  return (
    <Card>
      <CardHeader className="pb-1">
        <CardTitle className="flex items-center justify-between text-sm font-semibold">
          <span>{index.name}</span>
          {index.session && (
            <MarketSessionBadge session={index.session} compact />
          )}
        </CardTitle>
      </CardHeader>
      <CardContent className="pb-2">
        {/* 지수값 */}
        <div className="text-2xl font-bold tabular-nums">
          {currentValue.toLocaleString('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
        </div>
        {/* 전일 대비 — prevClose 없으면 표시 안 함 (mock 합성 X) */}
        {prevClose > 0 ? (
          <div className={`mt-0.5 text-sm font-medium tabular-nums ${colorClass}`}>
            {formatChangeAmount(changeAmount)}
            <span className="ml-2">({formatChangePercent(changePercent)})</span>
          </div>
        ) : (
          <div className="mt-0.5 text-xs text-muted-foreground">전일 종가 미수신</div>
        )}
        {/* 거래량(주) — KIS index 응답의 volume은 거래량(체결주식수). 거래대금은 tradingValue. */}
        {index.volume != null && index.volume > 0 && (
          <div className="mt-0.5 text-xs text-muted-foreground">
            거래량: {index.volume.toLocaleString('ko-KR')}주
          </div>
        )}
        {/* 거래대금 (KRW) */}
        {index.tradingValue != null && index.tradingValue > 0 && (
          <div className="text-xs text-muted-foreground">
            거래대금: {formatKrw(index.tradingValue)}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

// ── IndexCard 스켈레톤 ─────────────────────────────────────────────────────────

function IndexCardSkeleton() {
  return (
    <Card>
      <CardHeader className="pb-1">
        <div className="h-4 w-20 animate-pulse rounded bg-muted" />
      </CardHeader>
      <CardContent className="pb-2 space-y-2">
        <div className="h-7 w-32 animate-pulse rounded bg-muted" />
        <div className="h-4 w-24 animate-pulse rounded bg-muted" />
      </CardContent>
    </Card>
  )
}

// ── 메인 컴포넌트 ─────────────────────────────────────────────────────────────

function formatPnlRate(rate: number): string {
  const sign = rate >= 0 ? '+' : ''
  return `${sign}${rate.toFixed(2)}%`
}

export default function DashboardPage() {
  const { positions, totalPnl } = usePositionStore()
  const wsState = useWsConnectionState()

  // KOSPI·KOSDAQ 지수 조회 (1분 refetch)
  const { data: indices = [], isLoading: indexLoading } = useMarketIndex(['KOSPI', 'KOSDAQ'])

  return (
    <div className="space-y-4">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">대시보드</h1>
        <div className="flex items-center gap-2">
          <MarketSessionBadge session={indices[0]?.session ?? null} />
          {/* WebSocket 연결 상태 표시 */}
          <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
            <span
              className={`h-2 w-2 rounded-full ${
                wsState === 'CONNECTED'
                  ? 'bg-green-500'
                  : wsState === 'CONNECTING'
                    ? 'bg-yellow-400 animate-pulse'
                    : wsState === 'ERROR'
                      ? 'bg-red-500'
                      : 'bg-gray-400'
              }`}
            />
            {wsState === 'CONNECTED' ? 'STOMP 연결됨' : wsState === 'CONNECTING' ? '연결 중' : wsState === 'ERROR' ? '연결 오류' : '미연결'}
          </span>
        </div>
      </div>

      {/* KOSPI·KOSDAQ 지수 카드 2열 */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {indexLoading ? (
          <>
            <IndexCardSkeleton />
            <IndexCardSkeleton />
          </>
        ) : indices.length === 0 ? (
          <div className="col-span-2 rounded-lg border border-dashed border-amber-300 bg-amber-50 px-4 py-6 text-center text-sm text-amber-800">
            지수 데이터 미수신 (KIS API) — <code>GET /api/market/index</code> 응답 없음
          </div>
        ) : (
          indices.map((idx) => <IndexCard key={idx.code} index={idx} />)
        )}
      </div>

      {/* 포지션 요약 카드 */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="text-sm font-medium text-muted-foreground">보유 종목 수</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-bold">{positions.length}개</p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="text-sm font-medium text-muted-foreground">평가손익 합계</CardTitle>
          </CardHeader>
          <CardContent>
            <p className={`text-2xl font-bold ${totalPnl >= 0 ? 'text-red-500' : 'text-blue-500'}`}>
              {formatKrw(totalPnl)}
            </p>
          </CardContent>
        </Card>
      </div>

      {/* 보유 포지션 목록 */}
      {positions.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>보유 포지션</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-muted-foreground">
                    <th className="pb-2 text-left">종목</th>
                    <th className="pb-2 text-right">수량</th>
                    <th className="pb-2 text-right">평균단가</th>
                    <th className="pb-2 text-right">현재가</th>
                    <th className="pb-2 text-right">평가손익</th>
                  </tr>
                </thead>
                <tbody>
                  {positions.map((p) => (
                    <tr key={p.code} className="border-b last:border-0">
                      <td className="py-2">
                        <span className="font-medium">{p.name}</span>
                        <span className="ml-1 text-xs text-muted-foreground">{p.code}</span>
                      </td>
                      <td className="py-2 text-right">{p.quantity.toLocaleString('ko-KR')}</td>
                      <td className="py-2 text-right">{formatKrw(p.avgPrice)}</td>
                      <td className="py-2 text-right">{formatKrw(p.currentPrice)}</td>
                      <td
                        className={`py-2 text-right font-medium ${
                          p.unrealizedPnl >= 0 ? 'text-red-500' : 'text-blue-500'
                        }`}
                      >
                        {formatKrw(p.unrealizedPnl)}
                        <span className="ml-1 text-xs">
                          ({formatPnlRate(p.unrealizedPnlRate)})
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </CardContent>
        </Card>
      )}

      {positions.length === 0 && (
        <Card>
          <CardContent className="flex h-24 items-center justify-center text-muted-foreground">
            보유 포지션 없음
          </CardContent>
        </Card>
      )}

      {/* 1번 트랙 — 수동 주문 입구 (dogfooding) */}
      <ManualOrderPanel />
    </div>
  )
}
