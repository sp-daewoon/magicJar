/**
 * Step 1 (내부 index) — KIS 실전 키 입력 화면
 *
 * spec §5-3 정합:
 * - 4 input: accountNumber·displayName·appKey·secretKey
 * - autocomplete="off" (ADR 0046 §2-5)
 * - 클라이언트 검증: accountNumber `^\d{8}$`
 * - unmount 시 state 비우기 (보안)
 */
import type { FormEvent } from 'react'
import { useEffect, useRef, useState } from 'react'
import { useOnboardingStore } from '../../../stores/onboarding'

interface Props {
  onNext: (data: {
    accountNumber: string
    displayName: string
    appKey: string
    secretKey: string
  }) => void
}

export function KisRealStep({ onNext }: Props) {
  const [accountNumber, setAccountNumber] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [appKey, setAppKey] = useState('')
  const [secretKey, setSecretKey] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const validationError = useOnboardingStore((s) => s.validationError)

  const appKeyRef = useRef<HTMLInputElement>(null)
  const secretKeyRef = useRef<HTMLInputElement>(null)

  // unmount 시 민감 데이터 메모리 초기화 (ADR 0046 §2-5)
  useEffect(() => {
    return () => {
      setAppKey('')
      setSecretKey('')
      if (appKeyRef.current) appKeyRef.current.value = ''
      if (secretKeyRef.current) secretKeyRef.current.value = ''
    }
  }, [])

  function validate() {
    const newErrors: Record<string, string> = {}
    if (!/^\d{8}$/.test(accountNumber)) {
      newErrors.accountNumber = '계좌번호는 숫자 8자리여야 합니다'
    }
    if (displayName.length > 100) {
      newErrors.displayName = '닉네임은 최대 100자입니다'
    }
    if (!appKey.trim()) {
      newErrors.appKey = 'App Key를 입력해 주세요'
    }
    if (!secretKey.trim()) {
      newErrors.secretKey = 'Secret Key를 입력해 주세요'
    }
    setErrors(newErrors)
    return Object.keys(newErrors).length === 0
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!validate()) return
    onNext({ accountNumber, displayName, appKey, secretKey })
    // 제출 즉시 state 비우기 (ADR 0046 §2-5)
    setAppKey('')
    setSecretKey('')
    if (appKeyRef.current) appKeyRef.current.value = ''
    if (secretKeyRef.current) secretKeyRef.current.value = ''
  }

  function handleReset() {
    setAccountNumber('')
    setDisplayName('')
    setAppKey('')
    setSecretKey('')
    setErrors({})
    if (appKeyRef.current) appKeyRef.current.value = ''
    if (secretKeyRef.current) secretKeyRef.current.value = ''
  }

  return (
    <div>
      <h2 className="mb-2 text-lg font-semibold text-white">KIS 실전 계좌 정보 입력</h2>
      <p className="mb-6 text-sm text-gray-400">
        KIS 개발자 콘솔에서 발급받은 앱 키와 시크릿을 입력해 주세요.
        입력값은 AES-256-GCM으로 암호화되어 저장됩니다.
      </p>

      {/* 이전 검증 실패 에러 메시지 */}
      {validationError && (
        <div className="mb-4 rounded-lg border border-red-800/50 bg-red-950/30 p-3">
          <p className="text-sm text-red-400">{validationError}</p>
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4">
        {/* 계좌번호 */}
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-300">
            실전 계좌번호 <span className="text-red-400">*</span>
          </label>
          <input
            type="text"
            autoComplete="off"
            placeholder="8자리 계좌번호 (예: 74683223)"
            maxLength={8}
            value={accountNumber}
            onChange={(e) => setAccountNumber(e.target.value.replace(/\D/g, ''))}
            className={`w-full rounded-lg border px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500 ${
              errors.accountNumber ? 'border-red-600' : 'border-gray-700'
            }`}
          />
          {errors.accountNumber && (
            <p className="mt-1 text-xs text-red-400">{errors.accountNumber}</p>
          )}
        </div>

        {/* 닉네임 (선택) */}
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-300">
            닉네임 <span className="text-gray-500 text-xs">(선택)</span>
          </label>
          <input
            type="text"
            autoComplete="off"
            placeholder="내 실전 계좌 (최대 100자)"
            maxLength={100}
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            className={`w-full rounded-lg border px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500 ${
              errors.displayName ? 'border-red-600' : 'border-gray-700'
            }`}
          />
          {errors.displayName && (
            <p className="mt-1 text-xs text-red-400">{errors.displayName}</p>
          )}
        </div>

        {/* App Key */}
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-300">
            KIS App Key <span className="text-red-400">*</span>
          </label>
          <input
            ref={appKeyRef}
            type="password"
            autoComplete="off"
            placeholder="PS로 시작하는 앱 키"
            value={appKey}
            onChange={(e) => setAppKey(e.target.value)}
            className={`w-full rounded-lg border px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500 ${
              errors.appKey ? 'border-red-600' : 'border-gray-700'
            }`}
          />
          {errors.appKey && <p className="mt-1 text-xs text-red-400">{errors.appKey}</p>}
        </div>

        {/* Secret Key */}
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-300">
            KIS Secret Key <span className="text-red-400">*</span>
          </label>
          <input
            ref={secretKeyRef}
            type="password"
            autoComplete="off"
            placeholder="시크릿 키"
            value={secretKey}
            onChange={(e) => setSecretKey(e.target.value)}
            className={`w-full rounded-lg border px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500 ${
              errors.secretKey ? 'border-red-600' : 'border-gray-700'
            }`}
          />
          {errors.secretKey && <p className="mt-1 text-xs text-red-400">{errors.secretKey}</p>}
        </div>

        {/* 보안 안내 */}
        <div className="rounded-lg border border-amber-800/30 bg-amber-950/30 p-3">
          <p className="text-xs leading-relaxed text-amber-400/80">
            입력한 키는 전송 즉시 메모리에서 삭제됩니다.
            서버에는 AES-256-GCM 암호화 후 저장되며 로그에 노출되지 않습니다.
          </p>
        </div>

        {/* 버튼 */}
        <div className="flex gap-3 pt-2">
          <button
            type="button"
            onClick={handleReset}
            className="flex-1 rounded-lg border border-gray-700 px-4 py-2.5 text-sm font-medium text-gray-400 transition-colors hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-gray-600"
          >
            초기화
          </button>
          <button
            type="submit"
            className="flex-1 rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            검증 시작 →
          </button>
        </div>
      </form>
    </div>
  )
}
