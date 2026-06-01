/**
 * StrategySaveModal — 전략 저장 모달
 *
 * 입력 필드:
 *   name     — 전략 식별자 (영문·숫자·언더스코어)
 *   nameKo   — 한국어 이름
 *   universe — 종목 유니버스 (KOSPI/KOSDAQ/ALL)
 *   tf       — 타임프레임 (1m/3m/5m/15m/30m/90m/1h/4h/12h/1d/1wk/1mo/1y)
 *
 * 저장 후:
 *   onSaved(strategyId) 콜백 호출
 *   invalidateQueries(['sig-strategies','list']) — useCreateSigStrategy/useUpdateSigStrategy 내부
 *
 * 룰 5: LLM provider import 0건
 */
import { useState, useEffect, FormEvent } from 'react'
import { useCreateSigStrategy, useUpdateSigStrategy } from '../../api/hooks/useSigStrategy'
import { InstrumentSearchInput } from '../InstrumentSearchInput'

interface StrategySaveModalProps {
  /** 기존 전략 ID — 있으면 수정, 없으면 신규 생성 */
  strategyId?: string
  /** 소유자 사용자 ID */
  userId: number | null
  /** 매수 조건 DSL 텍스트 */
  entry: string
  /** 매도 조건 DSL 텍스트 (optional) */
  exit?: string
  initialName?: string
  initialNameKo?: string
  initialUniverse?: string
  initialTf?: string
  initialCode?: string
  initialStopLossPct?: number | string | null
  initialTakeProfitPct?: number | string | null
  /** 저장 완료 콜백 — strategyId 반환 */
  onSaved: (strategyId: string) => void
  /** 모달 닫기 */
  onClose: () => void
}

const UNIVERSE_OPTIONS = [
  { value: 'ALL', label: '전체 (ALL)' },
  { value: 'KOSPI', label: 'KOSPI' },
  { value: 'KOSDAQ', label: 'KOSDAQ' },
  { value: 'KOSPI200', label: 'KOSPI200' },
]

const TF_OPTIONS = [
  { value: '1m', label: '1분봉' },
  { value: '3m', label: '3분봉' },
  { value: '5m', label: '5분봉' },
  { value: '15m', label: '15분봉' },
  { value: '30m', label: '30분봉' },
  { value: '90m', label: '90분봉' },
  { value: '1h', label: '1시간봉' },
  { value: '4h', label: '4시간봉' },
  { value: '12h', label: '정규장 전체' },
  { value: '1d', label: '일봉' },
  { value: '1wk', label: '주봉' },
  { value: '1mo', label: '월봉' },
  { value: '1y', label: '년봉' },
]

export function StrategySaveModal({
  strategyId,
  userId,
  entry,
  exit,
  initialName,
  initialNameKo,
  initialUniverse,
  initialTf,
  initialCode,
  initialStopLossPct,
  initialTakeProfitPct,
  onSaved,
  onClose,
}: StrategySaveModalProps) {
  const [name, setName] = useState(initialName ?? '')
  const [nameKo, setNameKo] = useState(initialNameKo ?? '')
  const [universe, setUniverse] = useState(initialUniverse ?? 'KOSPI')
  const [tf, setTf] = useState(initialTf ?? '1d')
  const [targetCode, setTargetCode] = useState(initialCode ?? '')
  const [stopLossPct, setStopLossPct] = useState(initialStopLossPct?.toString() ?? '5')
  const [takeProfitPct, setTakeProfitPct] = useState(initialTakeProfitPct?.toString() ?? '10')
  const [formError, setFormError] = useState<string | null>(null)

  const createMutation = useCreateSigStrategy()
  const updateMutation = useUpdateSigStrategy()
  const isPending = createMutation.isPending || updateMutation.isPending
  const mutationError = createMutation.error ?? updateMutation.error
  const isError = createMutation.isError || updateMutation.isError

  useEffect(() => {
    if (isError && mutationError) {
      setFormError(`저장 실패: ${mutationError.message}`)
    }
  }, [isError, mutationError])

  useEffect(() => {
    setName(initialName ?? '')
    setNameKo(initialNameKo ?? '')
    setUniverse(initialUniverse ?? 'KOSPI')
    setTf(initialTf ?? '1d')
    setTargetCode(initialCode ?? '')
    setStopLossPct(initialStopLossPct?.toString() ?? '5')
    setTakeProfitPct(initialTakeProfitPct?.toString() ?? '10')
  }, [
    strategyId,
    initialName,
    initialNameKo,
    initialUniverse,
    initialTf,
    initialCode,
    initialStopLossPct,
    initialTakeProfitPct,
  ])

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    setFormError(null)

    if (userId === null) {
      setFormError('계좌 등록 후 전략을 저장할 수 있습니다')
      return
    }
    if (!name.trim()) {
      setFormError('전략 식별자(영문)를 입력하세요')
      return
    }
    if (!/^[a-zA-Z0-9_]+$/.test(name.trim())) {
      setFormError('전략 식별자는 영문·숫자·언더스코어만 허용됩니다')
      return
    }
    if (!entry.trim()) {
      setFormError('매수 조건(Entry)이 없습니다')
      return
    }
    const normalizedCode = targetCode.trim()
    if (!normalizedCode) {
      setFormError('자동매매 대상 종목을 선택하세요')
      return
    }
    if (!/^\d{6}$/.test(normalizedCode)) {
      setFormError('자동매매 대상 종목은 6자리 종목코드로 저장됩니다')
      return
    }
    const parsedStopLossPct = Number(stopLossPct.trim())
    if (!Number.isFinite(parsedStopLossPct) || parsedStopLossPct <= 0) {
      setFormError('손절 기준은 0보다 큰 숫자로 입력하세요')
      return
    }
    const parsedTakeProfitPct = Number(takeProfitPct.trim())
    if (!Number.isFinite(parsedTakeProfitPct) || parsedTakeProfitPct <= 0) {
      setFormError('익절 기준은 0보다 큰 숫자로 입력하세요')
      return
    }
    if (parsedTakeProfitPct <= parsedStopLossPct) {
      setFormError('익절 기준은 손절 기준보다 커야 합니다')
      return
    }

    const payload = {
      userId,
      name: name.trim(),
      nameKo: nameKo.trim() || undefined,
      entry: entry.trim(),
      exit: exit?.trim() || undefined,
      universe,
      tf,
      stopLossPct: parsedStopLossPct,
      takeProfitPct: parsedTakeProfitPct,
      codes: [normalizedCode],
      intervals: [tf],
    }
    const callbacks = {
      onSuccess: (resp: { id: string }) => {
        onSaved(resp.id)
        onClose()
      },
      onError: (err: Error) => {
        setFormError(`저장 실패: ${err.message}`)
      },
    }

    if (strategyId) {
      updateMutation.mutate(
        {
          id: strategyId,
          req: payload,
        },
        callbacks,
      )
    } else {
      createMutation.mutate(payload, callbacks)
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
      role="dialog"
      aria-modal="true"
      aria-label="전략 저장"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div className="max-h-[92vh] w-full max-w-lg overflow-y-auto rounded-xl border border-gray-700 bg-gray-900 p-6 shadow-2xl">
        {/* 헤더 */}
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-white">{strategyId ? '전략 수정' : '전략 저장'}</h2>
          <button
            className="rounded p-1 text-gray-500 hover:text-gray-300 focus:outline-none"
            onClick={onClose}
            aria-label="모달 닫기"
          >
            ×
          </button>
        </div>

        {/* DSL 미리보기 */}
        <div className="mb-4 rounded-lg bg-gray-950 p-3">
          <div className="mb-1 text-xs text-gray-500">매수 조건 (Entry)</div>
          <code className="block truncate text-xs text-blue-300">{entry || '(없음)'}</code>
          {exit && (
            <>
              <div className="mb-1 mt-2 text-xs text-gray-500">매도 조건 (Exit)</div>
              <code className="block truncate text-xs text-orange-300">{exit}</code>
            </>
          )}
        </div>

        <form onSubmit={handleSubmit} className="space-y-3">
          {/* name */}
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-400">
              전략 식별자 <span className="text-red-400">*</span>
              <span className="ml-1 font-normal text-gray-600">(영문·숫자·_ 조합)</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="예: rsi_golden_cross"
              className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              autoFocus
            />
          </div>

          {/* nameKo */}
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-400">
              한국어 이름
              <span className="ml-1 font-normal text-gray-600">(optional)</span>
            </label>
            <input
              type="text"
              value={nameKo}
              onChange={(e) => setNameKo(e.target.value)}
              placeholder="예: RSI 골든크로스 전략"
              className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
          </div>

          {/* universe + tf */}
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-400">유니버스</label>
              <select
                value={universe}
                onChange={(e) => setUniverse(e.target.value)}
                className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                {UNIVERSE_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-400">타임프레임</label>
              <select
                value={tf}
                onChange={(e) => setTf(e.target.value)}
                className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                {TF_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* target code */}
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-400">
              자동매매 대상 종목 <span className="text-red-400">*</span>
            </label>
            <span className="mb-1 block text-xs text-gray-600">
              전략 목록과 자동매매는 이 종목 매핑을 기준으로 평가됩니다.
            </span>
            <InstrumentSearchInput
              value={targetCode}
              onRawChange={(value) => setTargetCode(value.trim())}
              onSelect={(result) => setTargetCode(result.code)}
              placeholder="종목명 또는 코드 검색"
            />
          </div>

          {/* mandatory risk plan */}
          <div className="rounded-lg border border-amber-900/60 bg-amber-950/20 p-3">
            <div className="mb-2 text-xs font-semibold text-amber-200">자동매매 RiskPlan</div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-400">
                  손절 기준 <span className="text-red-400">*</span>
                  <span className="ml-1 font-normal text-gray-600">(%)</span>
                </label>
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  value={stopLossPct}
                  onChange={(e) => setStopLossPct(e.target.value)}
                  placeholder="예: 5"
                  className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-400">
                  익절 기준 <span className="text-red-400">*</span>
                  <span className="ml-1 font-normal text-gray-600">(%)</span>
                </label>
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  value={takeProfitPct}
                  onChange={(e) => setTakeProfitPct(e.target.value)}
                  placeholder="예: 10"
                  className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>
            </div>
            <p className="mt-2 text-xs leading-5 text-amber-100/70">
              자동매매는 손절과 익절이 모두 있어야 시작됩니다. 차트 범위 손절은 후속 단계에서 이 기본 손절을 대체합니다.
            </p>
          </div>

          {/* 에러 */}
          {formError && (
            <div className="rounded bg-red-900/20 px-3 py-2 text-sm text-red-400" role="alert">
              {formError}
            </div>
          )}

          {/* 버튼 */}
          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              className="rounded-lg border border-gray-700 px-4 py-2 text-sm text-gray-400 hover:text-gray-200 focus:outline-none"
              onClick={onClose}
            >
              취소
            </button>
            <button
              type="submit"
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-blue-400"
              disabled={isPending}
            >
              {isPending ? '저장 중...' : strategyId ? '수정' : '저장'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
