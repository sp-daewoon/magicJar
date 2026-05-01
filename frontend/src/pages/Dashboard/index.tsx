import { useMemo } from 'react'
import { Link } from 'react-router-dom'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import TickChart from '@/components/chart/TickChart'
import { useMarketTickStore } from '@/stores/marketTick'
import { usePositionStore } from '@/stores/position'
import { useMarketTickStream } from '@/hooks/useMarketTickStream'
import { useWsConnectionState } from '@/hooks/useWsConnectionState'

/** 보유 포지션이 없을 때 watch할 default 종목 (대형주 5개) */
const DEFAULT_WATCHLIST = ['005930', '000660', '035720', '005380', '051910']

/** 숫자 천 단위 포맷 (KRW) */
function formatKrw(value: number): string {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    minimumFractionDigits: 0,
  }).format(value)
}

function formatPnlRate(rate: number): string {
  const sign = rate >= 0 ? '+' : ''
  return `${sign}${rate.toFixed(2)}%`
}

export default function DashboardPage() {
  const { ticks, latestTick } = useMarketTickStore()
  const { positions, totalPnl } = usePositionStore()
  const wsState = useWsConnectionState()

  // 구독할 종목: 보유 포지션이 있으면 그것, 없으면 default 5종목
  const watchCodes = useMemo(
    () => (positions.length > 0 ? positions.map((p) => p.code) : DEFAULT_WATCHLIST),
    [positions],
  )
  useMarketTickStream(watchCodes)

  // 대표 종목 틱 (첫 번째 보유 종목 또는 watch list 첫 항목)
  const representativeCode = positions[0]?.code ?? watchCodes[0]
  const representativeTicks = representativeCode ? (ticks[representativeCode] ?? []) : []

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">대시보드</h1>

      {/* 포지션 요약 */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <Card>
          <CardHeader>
            <CardTitle className="text-sm font-medium text-muted-foreground">
              보유 종목 수
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-bold">{positions.length}개</p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-sm font-medium text-muted-foreground">
              평가손익 합계
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className={`text-2xl font-bold ${totalPnl >= 0 ? 'text-red-500' : 'text-blue-500'}`}>
              {formatKrw(totalPnl)}
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-sm font-medium text-muted-foreground">실시간 연결</CardTitle>
          </CardHeader>
          <CardContent>
            <span className="inline-flex items-center gap-1 text-sm">
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
              {wsState === 'CONNECTED' && 'STOMP 연결됨'}
              {wsState === 'CONNECTING' && '연결 중...'}
              {wsState === 'ERROR' && '연결 오류'}
              {wsState === 'DISCONNECTED' && '미연결'}
            </span>
          </CardContent>
        </Card>
      </div>

      {/* 관심 종목 시세 그리드 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between text-base">
            <span>
              {positions.length > 0 ? '보유 종목 실시간' : '관심 종목 실시간'}
            </span>
            <span className="text-xs font-normal text-muted-foreground">
              {watchCodes.length}개 구독 중
            </span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
            {watchCodes.map((code) => {
              const tick = latestTick[code]
              return (
                <Link
                  key={code}
                  to={`/symbol/${code}`}
                  className="block rounded-lg border bg-white p-3 transition hover:border-primary hover:shadow-sm"
                >
                  <div className="text-xs font-medium text-muted-foreground">{code}</div>
                  <div className="mt-1 text-lg font-bold tabular-nums">
                    {tick ? tick.price.toLocaleString() : '-'}
                  </div>
                  <div className="mt-1 text-xs tabular-nums text-muted-foreground">
                    거래량: {tick ? tick.volume.toLocaleString() : '-'}
                  </div>
                  <div className="mt-1 text-[10px] text-muted-foreground">
                    {tick
                      ? new Date(tick.timestamp).toLocaleTimeString('ko-KR', {
                          hour: '2-digit',
                          minute: '2-digit',
                          second: '2-digit',
                          hour12: false,
                        })
                      : 'STOMP 대기'}
                  </div>
                </Link>
              )
            })}
          </div>
          {watchCodes.every((c) => !latestTick[c]) && (
            <div className="mt-4 rounded border border-dashed border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-800">
              실시간 시세 0건. 백엔드 \`market.tick.v1\` Kafka publisher가 가동되어야 표시됨
              (KIS WebSocket 연동 또는 mock tick generator 필요).
            </div>
          )}
        </CardContent>
      </Card>

      {/* 대표 종목 틱 차트 */}
      <Card>
        <CardHeader>
          <CardTitle>
            대표 종목 틱 차트
            {representativeCode && (
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                {representativeCode}
              </span>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0 pb-4">
          {representativeTicks.length > 0 ? (
            <TickChart
              ticks={representativeTicks.map((t) => ({ time: t.timestamp, value: t.price }))}
              height={300}
              className="px-4"
            />
          ) : (
            <div className="flex h-64 items-center justify-center px-4 text-sm text-muted-foreground">
              실시간 틱 메시지 수신 시 자동 표시
            </div>
          )}
        </CardContent>
      </Card>

      {/* 포지션 목록 */}
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
            보유 포지션 없음 — P4 통합 후 실시간 데이터 연결 예정
          </CardContent>
        </Card>
      )}
    </div>
  )
}
