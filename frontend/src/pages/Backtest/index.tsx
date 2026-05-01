import { useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import CandleChart from '@/components/chart/CandleChart'
import type { Candle } from '@/components/chart/Chart'

/** 백테스트 결과 mock 타입 — P4에서 api-app 스펙 동기 */
interface BacktestResult {
  jobId: string
  strategy: string
  symbol: string
  startDate: string
  endDate: string
  totalReturn: number
  maxDrawdown: number
  winRate: number
  tradeCount: number
  candles: Candle[]
}

export default function BacktestPage() {
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<BacktestResult | null>(null)

  function handleRun() {
    setRunning(true)
    // 추후 backtestApi.trigger()/status() 실 연결 — 현재는 mock
    setTimeout(() => {
      setResult({
        jobId: 'mock-001',
        strategy: 'MA 교차 (5/20)',
        symbol: '005930',
        startDate: '2024-01-01',
        endDate: '2024-12-31',
        totalReturn: 18.4,
        maxDrawdown: -7.2,
        winRate: 58.3,
        tradeCount: 24,
        candles: [],
      })
      setRunning(false)
    }, 1000)
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">백테스트</h1>

      {/* 설정 패널 */}
      <Card>
        <CardHeader>
          <CardTitle>전략 설정</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-sm font-medium">종목 코드</label>
              <input
                type="text"
                defaultValue="005930"
                className="w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium">전략</label>
              <select className="w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-ring">
                <option>MA 교차 (5/20)</option>
                <option>RSI 과매수/과매도</option>
                <option>볼린저 밴드</option>
              </select>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium">시작일</label>
              <input
                type="date"
                defaultValue="2024-01-01"
                className="w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium">종료일</label>
              <input
                type="date"
                defaultValue="2024-12-31"
                className="w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
          </div>
          <Button onClick={handleRun} disabled={running}>
            {running ? '실행 중...' : '백테스트 실행'}
          </Button>
        </CardContent>
      </Card>

      {/* 결과 */}
      {result && (
        <>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            {[
              { label: '총 수익률', value: `${result.totalReturn > 0 ? '+' : ''}${result.totalReturn}%` },
              { label: '최대 낙폭', value: `${result.maxDrawdown}%` },
              { label: '승률', value: `${result.winRate}%` },
              { label: '거래 횟수', value: `${result.tradeCount}회` },
            ].map(({ label, value }) => (
              <Card key={label}>
                <CardHeader>
                  <CardTitle className="text-xs font-medium text-muted-foreground">{label}</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="text-xl font-bold">{value}</p>
                </CardContent>
              </Card>
            ))}
          </div>

          <Card>
            <CardHeader>
              <CardTitle>차트 (리플레이)</CardTitle>
            </CardHeader>
            <CardContent className="p-0 pb-4">
              {result.candles.length > 0 ? (
                <CandleChart candles={result.candles} height={400} className="px-4" />
              ) : (
                <div className="flex h-32 items-center justify-center text-sm text-muted-foreground px-4">
                  P4 통합 후 캔들 데이터 및 매매 마커 표시 예정
                </div>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}
