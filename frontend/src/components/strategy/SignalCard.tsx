/**
 * SignalCard — 시그널 단건 표시 컴포넌트 (T-SIG Phase 3 — ADR 0066)
 *
 * 표시 항목: ts·code·interval·side·price·rule·strategyId 링크
 * 실제 데이터만 표시 (feedback_no_mock_data 정합)
 */
import { Link } from 'react-router-dom'
import { type SignalDto, type SignalSide } from '../../api/signal'

// ──────────────────────────────────────────────
// 스타일 헬퍼
// ──────────────────────────────────────────────

const SIDE_BADGE: Record<SignalSide, { label: string; cls: string }> = {
  BUY:  { label: '매수', cls: 'bg-emerald-900/80 text-emerald-300 border border-emerald-700' },
  SELL: { label: '매도', cls: 'bg-red-900/80 text-red-300 border border-red-700' },
  HOLD: { label: '홀드', cls: 'bg-gray-700/80 text-gray-300 border border-gray-600' },
}

/** ISO 8601 → KST 포맷 (YYYY.MM.DD HH:mm) */
function formatTs(iso: string): string {
  try {
    const d = new Date(iso)
    return d.toLocaleString('ko-KR', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    })
  } catch {
    return iso
  }
}

/** 가격 원화 포맷 */
function formatPrice(priceStr: string): string {
  const num = Number(priceStr.replace(/,/g, ''))
  if (isNaN(num)) return priceStr
  return `${num.toLocaleString('ko-KR')}원`
}

// ──────────────────────────────────────────────
// 컴포넌트
// ──────────────────────────────────────────────

interface SignalCardProps {
  signal: SignalDto
  /** 새 시그널 하이라이트 (prepend 시 잠깐 표시) */
  isNew?: boolean
}

export function SignalCard({ signal, isNew = false }: SignalCardProps) {
  const badge = SIDE_BADGE[signal.side]

  return (
    <article
      className={[
        'flex items-start gap-3 rounded-lg border border-gray-800 bg-gray-900 px-4 py-3 transition-colors',
        isNew ? 'border-blue-700 bg-blue-950/30' : '',
      ].join(' ')}
    >
      {/* Side 배지 */}
      <span
        className={`mt-0.5 shrink-0 rounded px-2 py-0.5 text-xs font-bold ${badge.cls}`}
      >
        {badge.label}
      </span>

      {/* 메인 정보 */}
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
          {/* 종목 */}
          <Link
            to={`/symbol/${signal.code}`}
            className="text-sm font-semibold text-white hover:text-blue-300 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900"
          >
            {signal.code}
            {signal.codeName && (
              <span className="ml-1 font-normal text-gray-400">{signal.codeName}</span>
            )}
          </Link>

          {/* 인터벌 */}
          <span className="rounded bg-gray-700 px-1.5 py-0.5 text-xs text-gray-300">
            {signal.interval}
          </span>

          {/* 가격 */}
          <span className="text-sm font-medium text-white">
            {formatPrice(signal.price)}
          </span>
        </div>

        {/* 규칙 */}
        {signal.rule && (
          <p className="mt-1 truncate text-xs text-gray-400" title={signal.rule}>
            {signal.rule}
          </p>
        )}

        {/* 하단 메타 */}
        <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-gray-500">
          <time dateTime={signal.ts}>{formatTs(signal.ts)}</time>
          {signal.strategyName && (
            <Link
              to={`/strategy/list`}
              className="hover:text-gray-300 focus:outline-none"
            >
              {signal.strategyName}
            </Link>
          )}
        </div>
      </div>
    </article>
  )
}
