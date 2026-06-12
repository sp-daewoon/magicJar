/**
 * RealtimeSlotGauge — KIS WS 구독 슬롯 게이지 (② 섹션)
 *
 * KIS OpenAPI 동시 WS 구독 슬롯 상한(max) 대비 사용량(used) 가시화.
 *  - 전체 게이지 바 + used/max 텍스트
 *  - 사용률 임계: < 70% 녹색, 70~90% amber, >= 90% 빨강 (슬롯 고갈 임박 경고)
 *  - trId 별 분해 (byTrId) — 어떤 거래 ID가 슬롯을 점유하는지
 *
 * 슬롯 고갈은 신규 종목 상세/전략 후보 구독 실패의 직접 원인 → 운영자 핵심 지표.
 */
import type { RealtimeSlots } from '../../api/adminRealtime'

interface Props {
  slots: RealtimeSlots
}

/** 사용률 → 게이지 색상 + 한국어 힌트 */
function gaugeTone(ratio: number): { bar: string; text: string; hint: string | null } {
  if (ratio >= 0.9) {
    return {
      bar: 'bg-red-500',
      text: 'text-red-300',
      hint: '슬롯 고갈 임박 — 신규 구독(종목 상세·전략 후보) 실패 위험. 불필요 구독 정리 필요.',
    }
  }
  if (ratio >= 0.7) {
    return { bar: 'bg-amber-500', text: 'text-amber-300', hint: '슬롯 사용률 높음 — 추이 주시.' }
  }
  return { bar: 'bg-emerald-500', text: 'text-emerald-300', hint: null }
}

export function RealtimeSlotGauge({ slots }: Props) {
  const { used, max, byTrId } = slots
  const ratio = max > 0 ? used / max : 0
  const pct = Math.min(100, Math.round(ratio * 100))
  const tone = gaugeTone(ratio)

  // byTrId 내림차순 (점유 큰 trId 먼저)
  const trIdEntries = Object.entries(byTrId ?? {}).sort(([, a], [, b]) => b - a)

  return (
    <section
      aria-label="구독 슬롯 사용량"
      className="rounded-xl border border-gray-800 bg-gray-900/40 p-4"
    >
      <div className="mb-3 flex items-end justify-between gap-2">
        <h2 className="text-sm font-semibold text-gray-200">구독 슬롯</h2>
        <p className={['text-sm font-bold tabular-nums', tone.text].join(' ')}>
          {used.toLocaleString('ko-KR')}
          <span className="text-gray-500"> / {max.toLocaleString('ko-KR')}</span>
          <span className="ml-1 text-xs text-gray-400">({pct}%)</span>
        </p>
      </div>

      {/* 게이지 바 */}
      <div
        className="h-2.5 w-full overflow-hidden rounded-full bg-gray-800"
        role="progressbar"
        aria-valuenow={used}
        aria-valuemin={0}
        aria-valuemax={max}
        aria-label="슬롯 사용률"
      >
        <div
          className={['h-full rounded-full transition-all', tone.bar].join(' ')}
          style={{ width: `${pct}%` }}
        />
      </div>

      {tone.hint && (
        <p className="mt-2 rounded bg-gray-950/60 px-2 py-1.5 text-xs text-amber-300">
          {tone.hint}
        </p>
      )}

      {/* trId 별 분해 */}
      <div className="mt-4">
        <p className="mb-2 text-xs font-medium uppercase tracking-wide text-gray-500">
          trId 별 점유
        </p>
        {trIdEntries.length === 0 ? (
          <p className="text-xs text-gray-500">점유 중인 trId가 없습니다.</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {trIdEntries.map(([trId, count]) => (
              <span
                key={trId}
                className="inline-flex items-center gap-1.5 rounded-md border border-gray-700 bg-gray-950/60 px-2.5 py-1 text-xs"
              >
                <span className="font-mono text-gray-300">{trId}</span>
                <span className="rounded bg-gray-800 px-1.5 font-semibold text-gray-200 tabular-nums">
                  {count.toLocaleString('ko-KR')}
                </span>
              </span>
            ))}
          </div>
        )}
      </div>
    </section>
  )
}
