/**
 * RiskUsageCards — 리스크 한도 "사용량" 게이지 카드 4종
 *
 * 설정 화면(RiskConfigForm) 아래에 보강되는 읽기 전용 사용량 보드.
 * 각 한도를 "지금 얼마나 쓰고 있는지" 게이지로 노출한다.
 *
 *   A. 포지션 한도 (계좌 전체 공유) — 종목별이 아니라 계좌 전체 1개 풀
 *   B. 일일 손실 한도 (DLL)
 *   C. 동시 보유 종목 — 점(●●●○○) + 게이지
 *   D. 거래 빈도 (전략당) — 전략 드롭다운 선택
 *
 * 데이터: GET /api/risk/usage (useRiskUsage, 10초 polling + 저장 시 강제 갱신)
 *
 * 정책:
 *   - mock 합성 금지 — 응답/섹션 null 이면 "준비 중 / 데이터 없음" 표기.
 *   - 게이지 색: 여유 초록 → 70%+ 노랑 → 90%+ 빨강.
 *   - 금액 천단위 콤마, 한국어 라벨.
 *
 * 룰 5: LLM provider import 0건
 */
import { useMemo, useState } from 'react'
import { useRiskUsage } from '../../api/hooks/useRiskUsage'
import { useStrategies } from '../../api/hooks/useStrategy'
import type {
  RiskUsageConcurrent,
  RiskUsageDailyLoss,
  RiskUsagePosition,
  RiskUsageTradeFrequency,
} from '../../api/riskUsage'

// ──────────────────────────────────────────────
// 포맷 유틸
// ──────────────────────────────────────────────

function won(v: number | null | undefined): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—'
  return `${Math.round(v).toLocaleString('ko-KR')}원`
}

function pct(used: number | null, limit: number | null): number | null {
  if (used === null || limit === null || limit <= 0) return null
  return (used / limit) * 100
}

/** 사용률 → 색상 등급 (여유/주의/임박) */
function gaugeLevel(ratio: number | null): 'safe' | 'warn' | 'danger' | 'none' {
  if (ratio === null) return 'none'
  if (ratio >= 90) return 'danger'
  if (ratio >= 70) return 'warn'
  return 'safe'
}

const BAR_COLOR: Record<'safe' | 'warn' | 'danger' | 'none', string> = {
  safe: 'bg-emerald-500',
  warn: 'bg-amber-400',
  danger: 'bg-red-500',
  none: 'bg-gray-600',
}

const TEXT_COLOR: Record<'safe' | 'warn' | 'danger' | 'none', string> = {
  safe: 'text-emerald-400',
  warn: 'text-amber-300',
  danger: 'text-red-400',
  none: 'text-gray-500',
}

// ──────────────────────────────────────────────
// 공통 프리미티브
// ──────────────────────────────────────────────

function CardShell({
  label,
  desc,
  badge,
  children,
}: {
  label: string
  desc?: string
  badge?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900/60 p-5">
      <div className="mb-3 flex items-start justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-gray-200">{label}</h3>
          {desc && <p className="mt-0.5 text-xs text-gray-500">{desc}</p>}
        </div>
        {badge}
      </div>
      {children}
    </div>
  )
}

/** 가로 게이지 바 + 우상단 % 라벨 */
function Gauge({
  ratio,
  caption,
}: {
  ratio: number | null
  caption?: string
}) {
  const level = gaugeLevel(ratio)
  const width = ratio === null ? 0 : Math.min(100, Math.max(0, ratio))
  return (
    <div>
      <div className="flex items-baseline justify-between">
        <span className="text-xs text-gray-500">{caption ?? '사용률'}</span>
        <span className={['text-sm font-semibold tabular-nums', TEXT_COLOR[level]].join(' ')}>
          {ratio === null ? '—' : `${ratio.toFixed(1)}%`}
        </span>
      </div>
      <div
        className="mt-1.5 h-2.5 w-full overflow-hidden rounded-full bg-gray-800"
        role="progressbar"
        aria-valuenow={ratio === null ? undefined : Math.round(width)}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={caption ?? '사용률'}
      >
        <div
          className={['h-full rounded-full transition-all duration-500', BAR_COLOR[level]].join(' ')}
          style={{ width: `${width}%` }}
        />
      </div>
    </div>
  )
}

/** 한 줄 라벨/값 */
function Row({
  label,
  value,
  emphasis,
}: {
  label: string
  value: React.ReactNode
  emphasis?: boolean
}) {
  return (
    <div className="flex items-center justify-between text-xs">
      <span className="text-gray-500">{label}</span>
      <span
        className={[
          'tabular-nums',
          emphasis ? 'text-sm font-semibold text-white' : 'text-gray-300',
        ].join(' ')}
      >
        {value}
      </span>
    </div>
  )
}

function EmptySection({ label, desc }: { label: string; desc?: string }) {
  return (
    <CardShell label={label} desc={desc}>
      <div className="rounded-lg border border-dashed border-gray-700 bg-gray-900/40 px-3 py-6 text-center text-xs text-gray-500">
        데이터 없음 · 준비 중
      </div>
    </CardShell>
  )
}

// ──────────────────────────────────────────────
// A. 포지션 한도 (계좌 전체 공유)
// ──────────────────────────────────────────────

function PositionCard({ d }: { d: RiskUsagePosition | null }) {
  if (!d || d.totalLimit === null) {
    return (
      <EmptySection
        label="A. 포지션 한도 (계좌 전체)"
        desc="계좌 전체가 공유하는 단일 풀 — 종목별 한도가 아닙니다."
      />
    )
  }
  const ratio = pct(d.totalExposure, d.totalLimit)
  return (
    <CardShell
      label="A. 포지션 한도 (계좌 전체)"
      desc="계좌 전체가 공유하는 단일 풀 — 종목별 한도가 아닙니다."
    >
      <Gauge
        ratio={ratio}
        caption={`전체노출 ${won(d.totalExposure)} / ${won(d.totalLimit)}`}
      />
      <div className="mt-3 space-y-1.5">
        <Row label="남은 한도" value={won(d.remaining)} emphasis />
        <Row label="예수금(주문가능 현금)" value={won(d.orderableCash)} />
        <Row label="실제 매수가능 (남은한도·예수금 중 작은 값)" value={won(d.buyable)} emphasis />
      </div>
      <p className="mt-3 text-[11px] leading-relaxed text-gray-600">
        총한도 = min(절대 {won(d.absoluteLimit)}, 순자산
        {d.maxPositionPercent !== null ? ` ${d.maxPositionPercent}%` : ''} =
        {' '}
        {won(d.percentLimit)})
      </p>
    </CardShell>
  )
}

// ──────────────────────────────────────────────
// B. 일일 손실 한도 (DLL)
// ──────────────────────────────────────────────

function DailyLossCard({ d }: { d: RiskUsageDailyLoss | null }) {
  if (!d) {
    return <EmptySection label="B. 일일 손실 한도 (DLL)" desc="하루 최대 허용 손실" />
  }
  if (!d.enabled) {
    return (
      <CardShell label="B. 일일 손실 한도 (DLL)" desc="하루 최대 허용 손실">
        <div className="rounded-lg border border-dashed border-gray-700 bg-gray-900/40 px-3 py-6 text-center text-xs text-gray-500">
          미설정 — DLL이 비활성화되어 있습니다.
        </div>
      </CardShell>
    )
  }
  // 오늘 손실액 = todayRealizedPnl 이 음수일 때의 절대값
  const lossUsed =
    d.todayRealizedPnl !== null && d.todayRealizedPnl < 0 ? Math.abs(d.todayRealizedPnl) : 0
  const ratio = pct(lossUsed, d.limitAmount)
  const pnlPositive = (d.todayRealizedPnl ?? 0) >= 0
  return (
    <CardShell label="B. 일일 손실 한도 (DLL)" desc="하루 최대 허용 손실 — 초과 시 신규 BUY 차단">
      <Gauge ratio={ratio} caption={`손실 ${won(lossUsed)} / 한도 ${won(d.limitAmount)}`} />
      <div className="mt-3 space-y-1.5">
        <Row
          label="오늘 실현손익"
          value={
            <span className={pnlPositive ? 'text-emerald-400' : 'text-red-400'}>
              {d.todayRealizedPnl === null
                ? '—'
                : `${pnlPositive ? '+' : ''}${Math.round(d.todayRealizedPnl).toLocaleString('ko-KR')}원`}
            </span>
          }
          emphasis
        />
        <Row label="한도" value={won(d.limitAmount)} />
        <Row label="남은 손실 여유" value={won(d.remaining)} emphasis />
      </div>
    </CardShell>
  )
}

// ──────────────────────────────────────────────
// C. 동시 보유 종목
// ──────────────────────────────────────────────

function ConcurrentDots({ current, limit }: { current: number; limit: number }) {
  // 한도 과다 시 점이 너무 많아지지 않게 최대 20개까지만 점, 이후 텍스트
  const showDots = limit <= 20
  if (!showDots) return null
  return (
    <div className="mt-3 flex flex-wrap gap-1.5" aria-hidden="true">
      {Array.from({ length: limit }).map((_, i) => (
        <span
          key={i}
          className={[
            'h-2.5 w-2.5 rounded-full',
            i < current ? 'bg-blue-500' : 'bg-gray-700',
          ].join(' ')}
        />
      ))}
    </div>
  )
}

function ConcurrentCard({ d }: { d: RiskUsageConcurrent | null }) {
  if (!d || d.limit === null || d.current === null) {
    return <EmptySection label="C. 동시 보유 종목" desc="동시에 보유할 수 있는 종목 수" />
  }
  const ratio = pct(d.current, d.limit)
  return (
    <CardShell label="C. 동시 보유 종목" desc="동시에 보유할 수 있는 종목 수">
      <Gauge ratio={ratio} caption={`보유 ${d.current} / ${d.limit}종목`} />
      <ConcurrentDots current={d.current} limit={d.limit} />
      <div className="mt-3 space-y-1.5">
        <Row
          label="현재 보유"
          value={`${d.current} / ${d.limit}종목`}
          emphasis
        />
        <Row label="추가 가능" value={`${d.remaining ?? '—'}종목 더`} emphasis />
      </div>
    </CardShell>
  )
}

// ──────────────────────────────────────────────
// D. 거래 빈도 (전략당)
// ──────────────────────────────────────────────

function TradeFrequencyCard({
  d,
  userId,
  selectedStrategyId,
  onSelectStrategy,
}: {
  d: RiskUsageTradeFrequency | null
  userId: number
  selectedStrategyId: number | null
  onSelectStrategy: (id: number | null) => void
}) {
  const { data: strategies, isLoading } = useStrategies(userId)

  const selector = (
    <select
      value={selectedStrategyId ?? ''}
      onChange={(e) => onSelectStrategy(e.target.value === '' ? null : Number(e.target.value))}
      className="max-w-[55%] truncate rounded-lg border border-gray-700 bg-gray-800 px-2.5 py-1 text-xs text-white focus:border-blue-500 focus:outline-none"
      aria-label="거래 빈도 전략 선택"
    >
      <option value="">전략 선택…</option>
      {(strategies ?? []).map((s) => (
        <option key={s.id} value={s.id}>
          {s.nameKo || s.name}
        </option>
      ))}
    </select>
  )

  return (
    <CardShell
      label="D. 거래 빈도 (전략당)"
      desc="선택한 전략의 분당·일별 주문 사용량"
      badge={selector}
    >
      {!isLoading && (strategies ?? []).length === 0 ? (
        <div className="rounded-lg border border-dashed border-gray-700 bg-gray-900/40 px-3 py-6 text-center text-xs text-gray-500">
          등록된 전략이 없습니다.
        </div>
      ) : selectedStrategyId === null ? (
        <div className="rounded-lg border border-dashed border-gray-700 bg-gray-900/40 px-3 py-6 text-center text-xs text-gray-500">
          전략을 선택하면 빈도 사용량이 표시됩니다.
        </div>
      ) : !d || d.perDayLimit === null ? (
        <div className="rounded-lg border border-dashed border-gray-700 bg-gray-900/40 px-3 py-6 text-center text-xs text-gray-500">
          데이터 없음 · 준비 중
        </div>
      ) : (
        <div className="space-y-4">
          <div>
            <Gauge
              ratio={pct(d.perDayUsed, d.perDayLimit)}
              caption={`오늘 ${d.perDayUsed ?? '—'} / ${d.perDayLimit}회`}
            />
            <div className="mt-1.5">
              <Row label="오늘 남은 거래" value={`${d.perDayRemaining ?? '—'}회`} emphasis />
            </div>
          </div>
          <div>
            <Gauge
              ratio={pct(d.perMinuteUsed, d.perMinuteLimit)}
              caption={`이번 분 ${d.perMinuteUsed ?? '—'} / ${d.perMinuteLimit ?? '—'}회`}
            />
          </div>
        </div>
      )}
    </CardShell>
  )
}

// ──────────────────────────────────────────────
// 본체
// ──────────────────────────────────────────────

interface RiskUsageCardsProps {
  userId: number
}

export function RiskUsageCards({ userId }: RiskUsageCardsProps) {
  const [selectedStrategyId, setSelectedStrategyId] = useState<number | null>(null)
  const { data, isLoading, isError, isFetching, dataUpdatedAt } = useRiskUsage(
    userId,
    selectedStrategyId,
  )

  const updatedLabel = useMemo(() => {
    if (!dataUpdatedAt) return null
    return new Date(dataUpdatedAt).toLocaleTimeString('ko-KR', { hour12: false })
  }, [dataUpdatedAt])

  return (
    <section className="mt-10">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h2 className="text-lg font-bold text-white">한도 사용 현황</h2>
          <p className="mt-0.5 text-xs text-gray-500">
            10초마다 자동 갱신 · 설정 저장 시 즉시 반영
          </p>
        </div>
        <span className="text-[11px] text-gray-600 tabular-nums">
          {isFetching ? '갱신 중…' : updatedLabel ? `갱신 ${updatedLabel}` : ''}
        </span>
      </div>

      {isLoading ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="h-40 animate-pulse rounded-xl bg-gray-800" />
          ))}
        </div>
      ) : isError ? (
        <div className="rounded-xl border border-red-800 bg-red-900/20 p-6 text-center text-sm text-red-400">
          한도 사용량을 불러오지 못했습니다. 잠시 후 다시 시도됩니다.
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <PositionCard d={data?.position ?? null} />
          <DailyLossCard d={data?.dailyLoss ?? null} />
          <ConcurrentCard d={data?.concurrent ?? null} />
          <TradeFrequencyCard
            d={data?.tradeFrequency ?? null}
            userId={userId}
            selectedStrategyId={selectedStrategyId}
            onSelectStrategy={setSelectedStrategyId}
          />
        </div>
      )}
    </section>
  )
}
