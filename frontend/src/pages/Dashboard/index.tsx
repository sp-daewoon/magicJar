import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import TickChart from '@/components/chart/TickChart'
import { useMarketTickStore } from '@/stores/marketTick'
import { usePositionStore } from '@/stores/position'

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
  const { ticks } = useMarketTickStore()
  const { positions, totalPnl } = usePositionStore()

  // 대표 종목 틱 (첫 번째 보유 종목 또는 빈 배열)
  const representativeCode = positions[0]?.code
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
            <CardTitle className="text-sm font-medium text-muted-foreground">연결 상태</CardTitle>
          </CardHeader>
          <CardContent>
            <span className="inline-flex items-center gap-1 text-sm">
              <span className="h-2 w-2 rounded-full bg-yellow-400" />
              대기 중 (P4 연결)
            </span>
          </CardContent>
        </Card>
      </div>

      {/* 실시간 틱 차트 */}
      <Card>
        <CardHeader>
          <CardTitle>
            실시간 시세
            {representativeCode && (
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                {representativeCode}
              </span>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0 pb-4">
          <TickChart
            ticks={representativeTicks.map((t) => ({ time: t.timestamp, value: t.price }))}
            height={300}
            className="px-4"
          />
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
