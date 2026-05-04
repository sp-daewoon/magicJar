/**
 * NotificationToasts — 화면 우상단 banner queue.
 *
 * 외부 라이브러리 의존 0 (sonner·react-hot-toast 등 미사용).
 * fixed position + Tailwind utilities로 단순 구현.
 *
 * 사용: 최상위 Layout 또는 Router 안에 한 번 마운트하면 STOMP `/topic/notifications`
 * 메시지를 자동 표시.
 */
import { useNotificationsStream } from '@/hooks/useNotificationsStream'
import type { NotificationMessage } from '@/api/types'

const LEVEL_STYLE: Record<NotificationMessage['level'], { bg: string; border: string; icon: string }> = {
  INFO: { bg: 'bg-blue-50', border: 'border-blue-300', icon: 'i' },
  WARN: { bg: 'bg-amber-50', border: 'border-amber-300', icon: '!' },
  ERROR: { bg: 'bg-red-50', border: 'border-red-300', icon: '×' },
}

export default function NotificationToasts() {
  const { toasts, dismiss } = useNotificationsStream()

  if (toasts.length === 0) return null

  return (
    <div className="pointer-events-none fixed right-4 top-4 z-50 flex w-80 flex-col gap-2">
      {toasts.map((t) => {
        const style = LEVEL_STYLE[t.level] ?? LEVEL_STYLE.INFO
        return (
          <div
            key={t.id}
            className={`pointer-events-auto rounded-md border px-3 py-2 shadow-md ${style.bg} ${style.border}`}
            role="status"
            aria-live="polite"
          >
            <div className="flex items-start justify-between gap-2">
              <div className="flex items-start gap-2">
                <span
                  className={`mt-0.5 flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full text-xs font-bold ${
                    t.level === 'ERROR'
                      ? 'bg-red-500 text-white'
                      : t.level === 'WARN'
                        ? 'bg-amber-500 text-white'
                        : 'bg-blue-500 text-white'
                  }`}
                >
                  {style.icon}
                </span>
                <div>
                  <div className="text-sm font-semibold text-gray-900">{t.title}</div>
                  <div className="mt-0.5 text-xs text-gray-700">{t.body}</div>
                </div>
              </div>
              <button
                type="button"
                aria-label="알림 닫기"
                onClick={() => dismiss(t.id)}
                className="flex-shrink-0 text-xs text-gray-400 hover:text-gray-600"
              >
                ✕
              </button>
            </div>
          </div>
        )
      })}
    </div>
  )
}
