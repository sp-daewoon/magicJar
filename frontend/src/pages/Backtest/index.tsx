/**
 * 백테스트 페이지 — POST /api/backtest 트리거 + GET /api/backtest/{runId} 폴링.
 *
 * 흐름:
 *   1. 사용자 입력(전략/종목/기간) → trigger() → runId 즉시 반환
 *   2. TanStack Query refetchInterval 1.5s 폴링 — RUNNING 동안만
 *   3. COMPLETED → summaryJson 파싱 → 4 지표 카드
 *   4. FAILED → errorMessage 표시
 */
import { useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { backtestApi } from '@/api/client'
import type { BacktestSummary, BacktestTriggerRequest } from '@/api/types'

const STRATEGIES = [
  { id: 'sma-cross-5-20', label: 'MA 교차 (5/20)' },
  { id: 'sma-cross-10-60', label: 'MA 교차 (10/60)' },
  { id: 'rsi-14', label: 'RSI 과매수/과매도 (14)' },
  { id: 'bollinger-20', label: '볼린저 밴드 (20,2σ)' },
]

function parseSummary(json: string | null): BacktestSummary | null {
  if (!json) return null
  try {
    return JSON.parse(json) as BacktestSummary
  } catch {
    return null
  }
}

function formatPercent(rate: number | undefined, signed = true): string {
  if (rate == null || !isFinite(rate)) return '-'
  const v = (rate * 100).toFixed(2)
  return signed && rate >= 0 ? `+${v}%` : `${v}%`
}

export default function BacktestPage() {
  const [strategyId, setStrategyId] = useState(STRATEGIES[0].id)
  const [symbol, setSymbol] = useState('005930')
  const [from, setFrom] = useState('2024-01-01')
  const [to, setTo] = useState('2024-12-31')
  const [activeRunId, setActiveRunId] = useState<string | null>(null)

  const trigger = useMutation({
    mutationFn: (req: BacktestTriggerRequest) => backtestApi.trigger(req),
    onSuccess: (resp) => setActiveRunId(resp.runId),
  })

  const status = useQuery({
    queryKey: ['backtest', activeRunId],
    queryFn: () => backtestApi.status(activeRunId!),
    enabled: !!activeRunId,
    refetchInterval: (query) => {
      const data = query.state.data
      if (!data) return 1500
      if (data.status === 'COMPLETED' || data.status === 'FAILED') return false
      return 1500
    },
  })

  const summary = parseSummary(status.data?.summaryJson ?? null)

  function handleRun() {
    trigger.mutate({ strategyId, symbol, from, to })
  }

  const isRunning =
    trigger.isPending ||
    status.data?.status === 'QUEUED' ||
    status.data?.status === 'RUNNING'

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
                value={symbol}
                onChange={(e) => setSymbol(e.target.value.toUpperCase())}
                className="w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium">전략</label>
              <select
                value={strategyId}
                onChange={(e) => setStrategyId(e.target.value)}
                className="w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
              >
                {STRATEGIES.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium">시작일</label>
              <input
                type="date"
                value={from}
                onChange={(e) => setFrom(e.target.value)}
                className="w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium">종료일</label>
              <input
                type="date"
                value={to}
                onChange={(e) => setTo(e.target.value)}
                className="w-full rounded border px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
          </div>
          <div className="flex items-center gap-3">
            <Button onClick={handleRun} disabled={isRunning}>
              {isRunning ? '실행 중...' : '백테스트 실행'}
            </Button>
            {trigger.isError && (
              <span className="text-xs text-destructive">
                트리거 실패: {(trigger.error as { message?: string })?.message ?? 'unknown'}
              </span>
            )}
            {activeRunId && (
              <span className="text-xs text-muted-foreground">
                runId: <code className="font-mono">{activeRunId.slice(0, 8)}…</code>
                {' · '}status: <span className="font-medium">{status.data?.status ?? 'QUEUED'}</span>
              </span>
            )}
          </div>
        </CardContent>
      </Card>

      {/* 결과 */}
      {status.data?.status === 'COMPLETED' && summary && (
        <>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            {[
              { label: '총 수익률', value: formatPercent(summary.netReturn), raw: summary.netReturn, kind: 'pct' as const },
              { label: '최대 낙폭', value: formatPercent(summary.mdd, false), raw: summary.mdd, kind: 'pct' as const },
              { label: '승률', value: formatPercent(summary.winRate, false), raw: summary.winRate, kind: 'neutral' as const },
              { label: '거래 횟수', value: summary.trades != null ? `${summary.trades}회` : '-', raw: 0, kind: 'neutral' as const },
            ].map(({ label, value, raw, kind }) => {
              const positive = kind === 'pct' && raw != null && raw > 0
              const negative = kind === 'pct' && raw != null && raw < 0
              return (
                <Card key={label}>
                  <CardHeader>
                    <CardTitle className="text-xs font-medium text-muted-foreground">{label}</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <p
                      className={
                        positive
                          ? 'text-xl font-bold text-red-600'
                          : negative
                            ? 'text-xl font-bold text-blue-600'
                            : 'text-xl font-bold'
                      }
                    >
                      {value}
                    </p>
                  </CardContent>
                </Card>
              )
            })}
          </div>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">결과 메타</CardTitle>
            </CardHeader>
            <CardContent className="space-y-1 text-sm tabular-nums">
              <div className="flex gap-2">
                <span className="text-muted-foreground">기간:</span>
                <span>{status.data.from} ~ {status.data.to}</span>
              </div>
              <div className="flex gap-2">
                <span className="text-muted-foreground">전략:</span>
                <span>{status.data.strategyId}</span>
              </div>
              {status.data.finishedAt && (
                <div className="flex gap-2">
                  <span className="text-muted-foreground">완료:</span>
                  <span>{status.data.finishedAt}</span>
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">summaryJson (raw)</CardTitle>
            </CardHeader>
            <CardContent>
              <pre className="overflow-x-auto rounded bg-muted/30 p-3 text-xs">
                {JSON.stringify(summary, null, 2)}
              </pre>
            </CardContent>
          </Card>
        </>
      )}

      {/* 진행 중 */}
      {(status.data?.status === 'QUEUED' || status.data?.status === 'RUNNING') && (
        <Card>
          <CardContent className="flex items-center gap-3 py-6 text-sm text-muted-foreground">
            <span className="h-2 w-2 animate-pulse rounded-full bg-amber-400" />
            백테스트 실행 중... (1.5초 간격 폴링)
          </CardContent>
        </Card>
      )}

      {/* 실패 */}
      {status.data?.status === 'FAILED' && (
        <Card>
          <CardContent className="space-y-2 py-4 text-sm">
            <div className="font-medium text-destructive">백테스트 실패</div>
            <div className="text-muted-foreground">{status.data.errorMessage ?? '원인 알 수 없음'}</div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
