/**
 * Step 4 (내부 index) — DART + Slack 시스템 설정 (선택)
 *
 * spec §5-6 정합:
 * - 2 input: dartApiKey (선택) + slackWebhookUrl (선택, https URL pattern)
 * - "건너뛰기" 버튼 → system_config UPDATE 호출 없이 다음 step
 * - Slack URL 등록 시 backend에서 test message 발송 (실패 시 503)
 */
import type { FormEvent } from 'react'
import { useState } from 'react'
import type { SystemConfigRequest } from '../../../api/onboarding'
import { extractErrorMessage, useUpdateSystemConfig } from '../../../api/onboarding'
import { useOnboardingStore } from '../../../stores/onboarding'

interface Props {
  onNext: () => void
  onSkip: () => void
}

export function SystemConfigStep({ onNext, onSkip }: Props) {
  const [dartApiKey, setDartApiKey] = useState('')
  const [slackWebhookUrl, setSlackWebhookUrl] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [serverError, setServerError] = useState<string | null>(null)

  const setSystemConfig = useOnboardingStore((s) => s.setSystemConfig)
  const { mutate, isPending } = useUpdateSystemConfig()

  function validate() {
    const newErrors: Record<string, string> = {}
    if (
      slackWebhookUrl.trim() &&
      !slackWebhookUrl.startsWith('https://hooks.slack.com/')
    ) {
      newErrors.slackWebhookUrl = 'Slack webhook URL은 https://hooks.slack.com/으로 시작해야 합니다'
    }
    setErrors(newErrors)
    return Object.keys(newErrors).length === 0
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!validate()) return
    setServerError(null)

    const req: SystemConfigRequest = {
      dartApiKey: dartApiKey.trim() || undefined,
      slackWebhookUrl: slackWebhookUrl.trim() || undefined,
    }

    mutate(req, {
      onSuccess: () => {
        setSystemConfig()
        onNext()
      },
      onError: (err) => {
        setServerError(
          extractErrorMessage(err, '설정 저장에 실패했습니다. 다시 시도해 주세요.'),
        )
      },
    })
  }

  return (
    <div>
      <h2 className="mb-2 text-lg font-semibold text-white">시스템 설정 (선택)</h2>
      <p className="mb-6 text-sm text-gray-400">
        DART API 키와 Slack 알림 webhook을 등록하면 더 많은 기능을 사용할 수 있습니다.
        지금 건너뛰고 나중에 설정할 수도 있습니다.
      </p>

      {serverError && (
        <div className="mb-4 rounded-lg border border-red-800/50 bg-red-950/30 p-3">
          <p className="text-sm text-red-400">{serverError}</p>
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4">
        {/* DART API 키 */}
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-300">
            DART API 키 <span className="text-gray-500 text-xs">(선택)</span>
          </label>
          <input
            type="text"
            autoComplete="off"
            placeholder="open.dart.fss.or.kr 발급 API 키"
            value={dartApiKey}
            onChange={(e) => setDartApiKey(e.target.value)}
            className="w-full rounded-lg border border-gray-700 px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p className="mt-1 text-xs text-gray-600">공시 정보 조회에 사용됩니다</p>
        </div>

        {/* Slack Webhook URL */}
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-300">
            Slack Webhook URL <span className="text-gray-500 text-xs">(선택)</span>
          </label>
          <input
            type="url"
            autoComplete="off"
            placeholder="https://hooks.slack.com/services/..."
            value={slackWebhookUrl}
            onChange={(e) => setSlackWebhookUrl(e.target.value)}
            className={`w-full rounded-lg border px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500 ${
              errors.slackWebhookUrl ? 'border-red-600' : 'border-gray-700'
            }`}
          />
          {errors.slackWebhookUrl && (
            <p className="mt-1 text-xs text-red-400">{errors.slackWebhookUrl}</p>
          )}
          <p className="mt-1 text-xs text-gray-600">
            등록 시 테스트 메시지가 발송됩니다 — Slack에서 확인하세요
          </p>
        </div>

        {/* 버튼 */}
        <div className="flex gap-3 pt-2">
          <button
            type="button"
            onClick={onSkip}
            disabled={isPending}
            className="flex-1 rounded-lg border border-gray-700 px-4 py-2.5 text-sm font-medium text-gray-400 transition-colors hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-gray-600 disabled:opacity-50"
          >
            건너뛰기
          </button>
          <button
            type="submit"
            disabled={isPending}
            className="flex-1 rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
          >
            {isPending ? '저장 중...' : '저장 후 다음 →'}
          </button>
        </div>
      </form>
    </div>
  )
}
