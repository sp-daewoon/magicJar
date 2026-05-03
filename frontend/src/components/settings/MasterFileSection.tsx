/**
 * MasterFileSection — 종목 마스터파일 탭 컴포넌트
 * 시장 선택 체크박스 + 수집 버튼 + 진행 상태 폴링
 */

import { useState, useEffect, useRef } from 'react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { masterApi } from '@/api/auth'
import type { MasterSyncResponse, MasterSyncStatusResponse } from '@/api/auth'

type MarketKey = 'KOSPI' | 'KOSDAQ' | 'ETF' | 'ETN'

const MARKET_LABELS: Record<MarketKey, string> = {
  KOSPI: 'KOSPI (유가증권)',
  KOSDAQ: 'KOSDAQ (코스닥)',
  ETF: 'ETF (상장지수펀드)',
  ETN: 'ETN (상장지수채권)',
}

const ALL_MARKETS: MarketKey[] = ['KOSPI', 'KOSDAQ', 'ETF', 'ETN']

type SyncStatus = 'IDLE' | 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'

function statusBadge(status: SyncStatus) {
  switch (status) {
    case 'IDLE':
      return null
    case 'QUEUED':
      return <Badge variant="secondary">대기 중</Badge>
    case 'RUNNING':
      return <Badge className="bg-blue-500 text-white hover:bg-blue-500 animate-pulse">수집 중</Badge>
    case 'COMPLETED':
      return <Badge className="bg-green-600 text-white hover:bg-green-600">완료</Badge>
    case 'FAILED':
      return <Badge variant="destructive">실패</Badge>
  }
}

export function MasterFileSection() {
  const [selectedMarkets, setSelectedMarkets] = useState<Set<MarketKey>>(
    new Set(ALL_MARKETS),
  )
  const [syncStatus, setSyncStatus] = useState<SyncStatus>('IDLE')
  const [syncResult, setSyncResult] = useState<MasterSyncStatusResponse | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [runId, setRunId] = useState<string | null>(null)

  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null)

  // runId가 생기면 상태 폴링 시작
  useEffect(() => {
    if (!runId) return
    if (syncStatus === 'COMPLETED' || syncStatus === 'FAILED') return

    pollingRef.current = setInterval(async () => {
      try {
        const status = await masterApi.getStatus(runId)
        setSyncResult(status)
        setSyncStatus(status.status)

        if (status.status === 'COMPLETED' || status.status === 'FAILED') {
          if (pollingRef.current) clearInterval(pollingRef.current)
        }
      } catch {
        // 폴링 실패 시 조용히 무시 (네트워크 일시 불안)
      }
    }, 2_000)

    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current)
    }
  }, [runId, syncStatus])

  function toggleMarket(market: MarketKey) {
    setSelectedMarkets((prev) => {
      const next = new Set(prev)
      if (next.has(market)) {
        next.delete(market)
      } else {
        next.add(market)
      }
      return next
    })
  }

  async function handleSync() {
    if (selectedMarkets.size === 0) {
      setErrorMsg('시장을 하나 이상 선택하세요.')
      return
    }

    setErrorMsg(null)
    setSyncStatus('QUEUED')
    setSyncResult(null)
    setRunId(null)
    if (pollingRef.current) clearInterval(pollingRef.current)

    try {
      const res: MasterSyncResponse = await masterApi.sync({
        markets: Array.from(selectedMarkets),
      })
      setRunId(res.runId)
      setSyncStatus(res.status)
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e)
      setErrorMsg(`수집 요청 실패: ${msg}`)
      setSyncStatus('FAILED')
    }
  }

  const isRunning = syncStatus === 'QUEUED' || syncStatus === 'RUNNING'

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <h3 className="text-sm font-semibold">수집 대상 시장</h3>
        <p className="text-xs text-muted-foreground">
          마스터파일 수집 대상을 선택하세요. 전체 선택 권장.
        </p>
      </div>

      {/* 시장 체크박스 */}
      <div className="space-y-2">
        {ALL_MARKETS.map((market) => (
          <label key={market} className="flex items-center gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={selectedMarkets.has(market)}
              onChange={() => toggleMarket(market)}
              disabled={isRunning}
              className="h-4 w-4 rounded border-input accent-primary"
            />
            <span className="text-sm">{MARKET_LABELS[market]}</span>
          </label>
        ))}
      </div>

      {/* 수집 버튼 */}
      <Button
        onClick={() => { void handleSync() }}
        disabled={isRunning || selectedMarkets.size === 0}
        className="w-full"
        variant={isRunning ? 'secondary' : 'default'}
      >
        {isRunning ? '수집 중...' : '마스터파일 수집'}
      </Button>

      {/* 에러 메시지 */}
      {errorMsg && (
        <p className="text-xs text-destructive">{errorMsg}</p>
      )}

      {/* 진행 상태 */}
      {syncStatus !== 'IDLE' && (
        <div className="rounded-md border p-4 space-y-3">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">수집 상태</span>
            {statusBadge(syncStatus)}
          </div>

          {runId && (
            <p className="text-xs text-muted-foreground font-mono">
              Run ID: {runId}
            </p>
          )}

          {syncResult && (
            <div className="text-xs space-y-1 text-muted-foreground">
              {syncResult.processedCount !== undefined && syncResult.totalCount !== undefined && (
                <p>
                  진행: {syncResult.processedCount.toLocaleString()} /{' '}
                  {syncResult.totalCount.toLocaleString()} 종목
                </p>
              )}
              {syncResult.completedAt && (
                <p>
                  완료 시각:{' '}
                  {new Date(syncResult.completedAt).toLocaleString('ko-KR', {
                    timeZone: 'Asia/Seoul',
                  })}
                </p>
              )}
              {syncResult.errorMessage && (
                <p className="text-destructive">{syncResult.errorMessage}</p>
              )}
            </div>
          )}

          {isRunning && (
            <p className="text-xs text-muted-foreground">
              2초 간격으로 상태를 확인하고 있습니다...
            </p>
          )}
        </div>
      )}

      {/* 안내 */}
      <div className="text-xs text-muted-foreground space-y-1">
        <p>- 마스터파일 수집은 전체 종목 메타정보를 KIS API에서 가져옵니다.</p>
        <p>- 신규 상장·종목코드 변경 시 수동으로 실행하세요.</p>
        <p>- 인증이 완료된 상태에서만 정상 동작합니다.</p>
      </div>
    </div>
  )
}
