/**
 * 관심종목 등록 폼 컴포넌트
 *
 * spec §7 옵션 C: clientside 6자리 숫자 검증 + raw code 그대로 등록
 * spec §6 검증 룰:
 * - 입력 형식: /^\d{6}$/ — 6자리 숫자만
 * - 409 WATCHLIST_DUPLICATE → "이미 등록된 종목입니다"
 * - 400 INVALID_TICKER_FORMAT → "종목 코드는 6자리 숫자여야 합니다 (예: 005930)"
 */
import axios from 'axios'
import { useRef, useState } from 'react'
import { useAddWatchlist } from '../../api/hooks/useWatchlist'
import type { WatchlistErrorResponse } from '../../api/watchlist'

/** 종목 코드 6자리 숫자 정규식 (spec §7 옵션 C LOCK) */
const TICKER_REGEX = /^\d{6}$/

interface WatchlistAddFormProps {
  /** 외부에서 input에 포커스를 줄 수 있도록 ref 노출 */
  inputRef?: React.RefObject<HTMLInputElement | null>
}

export function WatchlistAddForm({ inputRef }: WatchlistAddFormProps) {
  const [code, setCode] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  const successTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const { mutate: addWatchlist, isPending } = useAddWatchlist()

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setValidationError(null)
    setSuccessMessage(null)

    const trimmed = code.trim()

    // clientside 6자리 숫자 검증 (spec §7 옵션 C)
    if (!TICKER_REGEX.test(trimmed)) {
      setValidationError('6자리 숫자만 가능합니다 (예: 005930)')
      return
    }

    addWatchlist(
      { code: trimmed },
      {
        onSuccess: () => {
          setCode('')
          setValidationError(null)
          setSuccessMessage(`${trimmed} 등록되었습니다`)
          // 1초 후 성공 메시지 제거
          if (successTimerRef.current) clearTimeout(successTimerRef.current)
          successTimerRef.current = setTimeout(() => setSuccessMessage(null), 1000)
        },
        onError: (err) => {
          if (axios.isAxiosError(err)) {
            const data = err.response?.data as Partial<WatchlistErrorResponse> | undefined
            if (err.response?.status === 409) {
              // spec §4 409 WATCHLIST_DUPLICATE (함정 4)
              setValidationError(data?.message ?? '이미 등록된 종목입니다')
            } else if (err.response?.status === 400) {
              setValidationError(data?.message ?? '종목 코드는 6자리 숫자여야 합니다 (예: 005930)')
            } else {
              setValidationError('등록 중 오류가 발생했습니다. 다시 시도해 주세요.')
            }
          } else {
            setValidationError('등록 중 오류가 발생했습니다. 다시 시도해 주세요.')
          }
        },
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-2" noValidate>
      <div className="flex gap-2">
        <div className="flex-1">
          <label htmlFor="watchlist-code-input" className="sr-only">
            종목 코드 (6자리 숫자)
          </label>
          <input
            id="watchlist-code-input"
            ref={inputRef}
            type="text"
            inputMode="numeric"
            maxLength={6}
            value={code}
            onChange={(e) => {
              setCode(e.target.value)
              setValidationError(null)
              setSuccessMessage(null)
            }}
            placeholder="종목 코드 6자리 (예: 005930)"
            disabled={isPending}
            className="w-full rounded-xl border border-gray-700 bg-gray-800 px-4 py-2.5 text-sm text-white placeholder-gray-500 transition-colors focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
            aria-invalid={validationError != null}
            aria-describedby={
              validationError != null
                ? 'watchlist-add-error'
                : successMessage != null
                  ? 'watchlist-add-success'
                  : undefined
            }
          />
        </div>
        <button
          type="submit"
          disabled={isPending || code.trim().length === 0}
          className="rounded-xl bg-blue-600 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {isPending ? '등록 중...' : '등록'}
        </button>
      </div>

      {/* 에러 메시지 */}
      {validationError != null && (
        <p id="watchlist-add-error" className="text-xs text-red-400" role="alert">
          {validationError}
        </p>
      )}

      {/* 성공 메시지 (1초 후 자동 제거) */}
      {successMessage != null && (
        <p id="watchlist-add-success" className="text-xs text-green-400" role="status">
          {successMessage}
        </p>
      )}
    </form>
  )
}
