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
 *   invalidateQueries(['strategy','list']) — useCreateStrategy 내부
 *
 * 룰 5: LLM provider import 0건
 */
import { useState, useEffect, FormEvent } from 'react'
import { useCreateStrategy } from '../../api/hooks/useStrategy'

interface StrategySaveModalProps {
  /** 매수 조건 DSL 텍스트 */
  entry: string
  /** 매도 조건 DSL 텍스트 (optional) */
  exit?: string
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

export function StrategySaveModal({ entry, exit, onSaved, onClose }: StrategySaveModalProps) {
  const [name, setName] = useState('')
  const [nameKo, setNameKo] = useState('')
  const [universe, setUniverse] = useState('KOSPI')
  const [tf, setTf] = useState('1d')
  const [formError, setFormError] = useState<string | null>(null)

  const { mutate: createStrategy, isPending, isError, error } = useCreateStrategy()

  useEffect(() => {
    if (isError && error) {
      setFormError(`저장 실패: ${error.message}`)
    }
  }, [isError, error])

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    setFormError(null)

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

    createStrategy(
      {
        name: name.trim(),
        nameKo: nameKo.trim() || undefined,
        entry: entry.trim(),
        exit: exit?.trim() || undefined,
        universe,
        tf,
      },
      {
        onSuccess: (resp) => {
          onSaved(resp.strategyId)
          onClose()
        },
        onError: (err) => {
          setFormError(`저장 실패: ${err.message}`)
        },
      },
    )
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
      <div className="w-full max-w-md rounded-xl border border-gray-700 bg-gray-900 p-6 shadow-2xl">
        {/* 헤더 */}
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-white">전략 저장</h2>
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
          <div className="grid grid-cols-2 gap-3">
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
              {isPending ? '저장 중...' : '저장'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
