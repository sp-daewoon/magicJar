/**
 * BacktestForm — 백테스트 실행 폼 (T-BT R3)
 *
 * 입력 항목:
 *   - 전략 선택 (GET /api/strategy → strategyId)
 *   - 종목코드 (복수 쉼표 구분)
 *   - 인터벌 (1d · 12h · 4h · 1h · 90m · 30m · 15m · 5m · 3m · 1m)
 *   - 기간 (from · to, yyyy-MM-dd)
 *   - 초기자본 (KRW)
 *
 * 실행 버튼 → POST /api/backtests
 *
 * feedback_no_mock_data.md 정합: 오류 시 에러 메시지 표시
 * 룰 5: LLM provider import 0건
 */
import { useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { strategyApi } from '../../api/strategy'
import { backtestApi } from '../../api/backtest'
import type { BacktestRunPersistResponse } from '../../api/backtest'

interface BacktestFormProps {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  onSuccess: (response: BacktestRunPersistResponse) => void
}

const INTERVAL_OPTIONS = [
  { value: '1d', label: '일봉 (1d)' },
  { value: '12h', label: '정규장 전체 (12h)' },
  { value: '4h', label: '4시간 (4h)' },
  { value: '1h', label: '1시간 (1h)' },
  { value: '90m', label: '90분 (90m)' },
  { value: '30m', label: '30분 (30m)' },
  { value: '15m', label: '15분 (15m)' },
  { value: '5m', label: '5분 (5m)' },
  { value: '3m', label: '3분 (3m)' },
  { value: '1m', label: '1분 (1m)' },
]

/** 기본 from: 오늘 기준 90거래일 전 (약 4.5개월) */
function defaultFrom(): string {
  const d = new Date()
  d.setDate(d.getDate() - 130)
  return d.toISOString().slice(0, 10)
}

function defaultTo(): string {
  return new Date().toISOString().slice(0, 10)
}

/** 천 단위 콤마 입력 → 숫자 문자열 */
function parseCapital(v: string): string {
  return v.replace(/,/g, '').trim()
}

function formatCapital(v: string): string {
  const n = parseInt(v.replace(/,/g, ''), 10)
  if (isNaN(n)) return v
  return n.toLocaleString('ko-KR')
}

export function BacktestForm({ userId, onSuccess }: BacktestFormProps) {
  const [strategyId, setStrategyId] = useState<string>('')
  const [universe, setUniverse] = useState('005930')
  const [interval, setInterval] = useState('1d')
  const [from, setFrom] = useState(defaultFrom())
  const [to, setTo] = useState(defaultTo())
  const [capitalDisplay, setCapitalDisplay] = useState('10,000,000')
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  // 전략 목록 조회
  const { data: strategies, isLoading: isStrategiesLoading } = useQuery({
    queryKey: ['strategies'],
    queryFn: () => strategyApi.list(),
    staleTime: 30_000,
  })

  const { mutate: runBacktest, isPending: isRunning } = useMutation({
    mutationFn: () => {
      const capitalStr = parseCapital(capitalDisplay)
      if (!strategyId) throw new Error('전략을 선택해주세요')
      const universeList = universe
        .split(',')
        .map((s) => s.trim())
        .filter((s) => s.length > 0)
      if (universeList.length === 0) throw new Error('종목코드를 입력해주세요')
      if (!from || !to) throw new Error('기간을 입력해주세요')
      if (from >= to) throw new Error('시작일은 종료일보다 이전이어야 합니다')

      return backtestApi.create(userId, {
        strategyId: parseInt(strategyId, 10),
        universe: universeList,
        interval,
        from,
        to,
        initialCapital: capitalStr,
        taxModel: 'korean_default',
        commissionModel: 'kis_direct',
        slippageModel: 'fixed_0.05',
        priceAdjustmentMode: 'ADJUSTED',
      })
    },
    onSuccess: (res) => {
      setErrorMsg(null)
      onSuccess(res)
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : '백테스트 실행 중 오류가 발생했습니다'
      setErrorMsg(msg)
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setErrorMsg(null)
    runBacktest()
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="rounded-xl border border-gray-800 bg-gray-900 p-4"
    >
      <h2 className="mb-4 text-sm font-semibold text-gray-200">백테스트 설정</h2>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {/* 전략 선택 */}
        <div className="flex flex-col gap-1 lg:col-span-2">
          <label className="text-xs text-gray-500">전략 선택</label>
          <select
            value={strategyId}
            onChange={(e) => setStrategyId(e.target.value)}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            required
          >
            <option value="">-- 전략을 선택하세요 --</option>
            {isStrategiesLoading && <option disabled>불러오는 중...</option>}
            {strategies?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.nameKo ?? s.name} ({s.tf ?? '1d'})
              </option>
            ))}
          </select>
          {!isStrategiesLoading && strategies?.length === 0 && (
            <p className="text-xs text-amber-500">
              저장된 전략이 없습니다. 전략 빌더에서 먼저 전략을 저장해주세요.
            </p>
          )}
        </div>

        {/* 인터벌 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500">캔들 인터벌</label>
          <select
            value={interval}
            onChange={(e) => setInterval(e.target.value)}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
          >
            {INTERVAL_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>

        {/* 종목코드 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500">종목코드 (쉼표로 복수 입력)</label>
          <input
            type="text"
            value={universe}
            onChange={(e) => setUniverse(e.target.value)}
            placeholder="005930,000660"
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-blue-500"
            required
          />
        </div>

        {/* 시작일 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500">시작일</label>
          <input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            max={to}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            required
          />
        </div>

        {/* 종료일 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500">종료일</label>
          <input
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            min={from}
            max={new Date().toISOString().slice(0, 10)}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            required
          />
        </div>

        {/* 초기 자본 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500">초기 자본 (원)</label>
          <input
            type="text"
            inputMode="numeric"
            value={capitalDisplay}
            onChange={(e) => {
              const raw = e.target.value.replace(/[^0-9]/g, '')
              setCapitalDisplay(formatCapital(raw))
            }}
            placeholder="10,000,000"
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-blue-500"
            required
          />
        </div>
      </div>

      {/* 에러 메시지 */}
      {errorMsg && (
        <div className="mt-3 rounded-lg border border-red-900/50 bg-red-900/20 px-3 py-2 text-sm text-red-400">
          {errorMsg}
        </div>
      )}

      {/* 실행 버튼 */}
      <div className="mt-4 flex justify-end">
        <button
          type="submit"
          disabled={isRunning || !strategyId}
          className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
        >
          {isRunning ? (
            <span className="flex items-center gap-2">
              <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/30 border-t-white" />
              실행 중...
            </span>
          ) : (
            '백테스트 실행'
          )}
        </button>
      </div>
    </form>
  )
}
