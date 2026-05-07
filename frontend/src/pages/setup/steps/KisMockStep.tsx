/**
 * Step 3 (내부 index) — 모의계좌 추가 (선택)
 *
 * spec §5-5 정합:
 * - 라디오 버튼 Yes/No
 * - Yes 시 mockAccountNumber·appKey·secretKey 입력 폼
 * - "건너뛰기" 버튼 → 다음 step 직행
 * - 검증 성공 시 hasMockRegistered = true
 */
import type { FormEvent } from 'react'
import { useEffect, useRef, useState } from 'react'
import type { KisMockRequest } from '../../../api/onboarding'
import { extractErrorMessage, useAddMockAccount } from '../../../api/onboarding'
import { useOnboardingStore } from '../../../stores/onboarding'

interface Props {
  userId: string
  onNext: () => void
}

export function KisMockStep({ userId, onNext }: Props) {
  const [wantMock, setWantMock] = useState<'yes' | 'no' | null>(null)
  const [mockAccountNumber, setMockAccountNumber] = useState('')
  const [appKey, setAppKey] = useState('')
  const [secretKey, setSecretKey] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [serverError, setServerError] = useState<string | null>(null)

  const setMockRegistered = useOnboardingStore((s) => s.setMockRegistered)
  const { mutate, isPending } = useAddMockAccount()

  const appKeyRef = useRef<HTMLInputElement>(null)
  const secretKeyRef = useRef<HTMLInputElement>(null)

  // unmount 시 민감 데이터 초기화
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
    if (!/^\d{8}$/.test(mockAccountNumber)) {
      newErrors.mockAccountNumber = '계좌번호는 숫자 8자리여야 합니다'
    }
    if (!appKey.trim()) newErrors.appKey = '모의 App Key를 입력해 주세요'
    if (!secretKey.trim()) newErrors.secretKey = '모의 Secret Key를 입력해 주세요'
    setErrors(newErrors)
    return Object.keys(newErrors).length === 0
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!validate()) return
    setServerError(null)

    const req: KisMockRequest = { userId, mockAccountNumber, appKey, secretKey }
    mutate(req, {
      onSuccess: () => {
        setMockRegistered()
        // 민감 데이터 즉시 초기화
        setAppKey('')
        setSecretKey('')
        if (appKeyRef.current) appKeyRef.current.value = ''
        if (secretKeyRef.current) secretKeyRef.current.value = ''
        onNext()
      },
      onError: (err) => {
        setServerError(
          extractErrorMessage(err, '모의계좌 등록에 실패했습니다. 다시 시도해 주세요.'),
        )
      },
    })
  }

  return (
    <div>
      <h2 className="mb-2 text-lg font-semibold text-white">모의계좌 추가 (선택)</h2>
      <p className="mb-6 text-sm text-gray-400">
        KIS 모의투자 계좌를 등록하면 실제 주문 없이 전략을 테스트할 수 있습니다.
      </p>

      {/* Yes / No 선택 */}
      <div className="mb-6 flex gap-3">
        <button
          type="button"
          onClick={() => setWantMock('yes')}
          className={`flex-1 rounded-lg border py-3 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 ${
            wantMock === 'yes'
              ? 'border-blue-500 bg-blue-950/50 text-blue-300'
              : 'border-gray-700 text-gray-400 hover:bg-gray-800'
          }`}
        >
          네, 등록할게요
        </button>
        <button
          type="button"
          onClick={() => setWantMock('no')}
          className={`flex-1 rounded-lg border py-3 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-gray-600 ${
            wantMock === 'no'
              ? 'border-gray-500 bg-gray-800 text-white'
              : 'border-gray-700 text-gray-400 hover:bg-gray-800'
          }`}
        >
          건너뛰기
        </button>
      </div>

      {/* Yes 선택 시 입력 폼 */}
      {wantMock === 'yes' && (
        <>
          {serverError && (
            <div className="mb-4 rounded-lg border border-red-800/50 bg-red-950/30 p-3">
              <p className="text-sm text-red-400">{serverError}</p>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-300">
                모의 계좌번호 <span className="text-red-400">*</span>
              </label>
              <input
                type="text"
                autoComplete="off"
                placeholder="8자리 모의 계좌번호"
                maxLength={8}
                value={mockAccountNumber}
                onChange={(e) => setMockAccountNumber(e.target.value.replace(/\D/g, ''))}
                className={`w-full rounded-lg border px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500 ${
                  errors.mockAccountNumber ? 'border-red-600' : 'border-gray-700'
                }`}
              />
              {errors.mockAccountNumber && (
                <p className="mt-1 text-xs text-red-400">{errors.mockAccountNumber}</p>
              )}
            </div>

            <div>
              <label className="mb-1 block text-sm font-medium text-gray-300">
                모의 App Key <span className="text-red-400">*</span>
              </label>
              <input
                ref={appKeyRef}
                type="password"
                autoComplete="off"
                placeholder="모의계좌 앱 키"
                value={appKey}
                onChange={(e) => setAppKey(e.target.value)}
                className={`w-full rounded-lg border px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500 ${
                  errors.appKey ? 'border-red-600' : 'border-gray-700'
                }`}
              />
              {errors.appKey && <p className="mt-1 text-xs text-red-400">{errors.appKey}</p>}
            </div>

            <div>
              <label className="mb-1 block text-sm font-medium text-gray-300">
                모의 Secret Key <span className="text-red-400">*</span>
              </label>
              <input
                ref={secretKeyRef}
                type="password"
                autoComplete="off"
                placeholder="모의계좌 시크릿 키"
                value={secretKey}
                onChange={(e) => setSecretKey(e.target.value)}
                className={`w-full rounded-lg border px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500 ${
                  errors.secretKey ? 'border-red-600' : 'border-gray-700'
                }`}
              />
              {errors.secretKey && (
                <p className="mt-1 text-xs text-red-400">{errors.secretKey}</p>
              )}
            </div>

            <button
              type="submit"
              disabled={isPending}
              className="w-full rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
            >
              {isPending ? '검증 중...' : '모의계좌 등록 →'}
            </button>
          </form>
        </>
      )}

      {/* No 선택 시 다음 버튼 */}
      {wantMock === 'no' && (
        <button
          type="button"
          onClick={onNext}
          className="w-full rounded-lg bg-gray-700 px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-gray-500"
        >
          다음 단계로 →
        </button>
      )}
    </div>
  )
}
