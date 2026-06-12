/**
 * RealtimeCandleFreshnessPanel — 캔들 신선도 (④ 섹션)
 *
 * 종목·interval 별 마지막 캔들 갱신 경과 시간(ageSeconds) 표시.
 *  - ageSeconds > 180 (3분) → 경고색 (시세 유입 정지 의심)
 *  - 그 외 → 정상색
 *
 * 장애 판독 힌트: 정체 항목이 있으면
 *   "시세 유입 정지 의심 — 15:20 이후는 마감 동시호가로 정상"
 * (장 마감/동시호가 시간대에는 정상적으로 캔들이 멈추므로 오탐 방지 안내.)
 *
 * 경과 시간은 사람이 읽기 쉬운 한국어 포맷(초/분)으로 변환.
 */
import { Link } from 'react-router-dom'
import type { RealtimeCandleFreshness } from '../../api/adminRealtime'

interface Props {
  items: RealtimeCandleFreshness[]
}

/** 신선도 임계 — 초과 시 경고 (사용자 결재 LOCK) */
const STALE_THRESHOLD_SECONDS = 180

/** ageSeconds → 한국어 경과 포맷 ("12초 전" / "3분 20초 전") */
function formatAge(seconds: number): string {
  if (seconds < 0) return '-'
  if (seconds < 60) return `${seconds}초 전`
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return s === 0 ? `${m}분 전` : `${m}분 ${s}초 전`
}

export function RealtimeCandleFreshnessPanel({ items }: Props) {
  const staleItems = items.filter((it) => it.ageSeconds > STALE_THRESHOLD_SECONDS)
  const hasStale = staleItems.length > 0

  return (
    <section
      aria-label="캔들 신선도"
      className="rounded-xl border border-gray-800 bg-gray-900/40 p-4"
    >
      <div className="mb-3 flex items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-gray-200">캔들 신선도</h2>
        <span className="text-xs text-gray-400">
          {items.length.toLocaleString('ko-KR')}건
          {hasStale && (
            <span className="ml-1 font-semibold text-amber-300">
              · 정체 {staleItems.length}건
            </span>
          )}
        </span>
      </div>

      {hasStale && (
        <p className="mb-3 rounded bg-amber-950/40 px-2 py-1.5 text-xs text-amber-300">
          시세 유입 정지 의심 — 일부 종목 캔들이 3분 이상 갱신되지 않았습니다. 단, 15:20 이후는 마감
          동시호가로 정상입니다.
        </p>
      )}

      {items.length === 0 ? (
        <p className="rounded-lg border border-gray-800 bg-gray-950/40 p-8 text-center text-sm text-gray-500">
          캔들 신선도 데이터가 없습니다.
        </p>
      ) : (
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {items.map((it) => {
            const isStale = it.ageSeconds > STALE_THRESHOLD_SECONDS
            return (
              <div
                key={`${it.code}:${it.interval}`}
                className={[
                  'flex items-center justify-between gap-2 rounded-lg border px-3 py-2',
                  isStale
                    ? 'border-amber-700/70 bg-amber-950/20'
                    : 'border-gray-800 bg-gray-950/40',
                ].join(' ')}
                data-stale={isStale}
              >
                <div className="min-w-0">
                  <Link
                    to={`/symbol/${it.code}`}
                    className="inline-flex max-w-full items-baseline gap-1.5 hover:underline"
                    title={`${it.codeName ?? it.code} 상세 보기`}
                  >
                    {it.codeName && (
                      <span className="truncate text-xs text-gray-100">{it.codeName}</span>
                    )}
                    <span className="font-mono text-xs text-blue-400">{it.code}</span>
                  </Link>
                  <span className="ml-1.5 rounded bg-gray-800 px-1.5 py-0.5 text-[11px] text-gray-400">
                    {it.interval}
                  </span>
                </div>
                <span
                  className={[
                    'shrink-0 text-xs font-medium tabular-nums',
                    isStale ? 'text-amber-300' : 'text-emerald-300',
                  ].join(' ')}
                >
                  {formatAge(it.ageSeconds)}
                </span>
              </div>
            )
          })}
        </div>
      )}
    </section>
  )
}
