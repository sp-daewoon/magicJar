/**
 * StrategyPerformanceContent — 전략 성과 히스토리 + 메모 공용 본문 (T-PERF R3 — ADR 0126)
 *
 * 모달(StrategyPerformanceModal)과 풀스크린 페이지(StrategyPerformancePage)가 동일하게 렌더하는 본문.
 *   탭1 "성과 히스토리": 누적 요약(평균 수익률·승률·신뢰도 배지·레짐별 승률·평균 α) + 일자별 목록
 *   탭2 "메모": 당일치 입력(textarea + 추천 태그칩) + 과거 메모 타임라인
 *
 * 데이터 0건/null → "데이터 없음" 표기 (feedback_no_mock_data — mock 합성 0).
 * 금액 천단위 콤마·한국어. 룰 5: LLM provider import 0건.
 */
import { useMemo, useState } from 'react'
import {
  useStrategyPerformanceHistory,
  useStrategyPerformanceSummary,
  useStrategyNotes,
  useUpsertStrategyNote,
} from '../../api/hooks/useStrategyPerformance'
import type {
  MarketRegime,
  StrategyPerformanceRow,
  StrategyConfidence,
} from '../../api/strategyPerformance'

type ActiveTab = 'history' | 'note'

interface StrategyPerformanceContentProps {
  strategyId: number
  strategyName?: string
  userId: number | null
}

// ──────────────────────────────────────────────
// 표시 상수
// ──────────────────────────────────────────────

/** 조회 범위: canary 시작(2026-06-11) ~ 오늘 (KST) */
const HISTORY_FROM = '2026-06-11'

const REGIME_BADGE: Record<MarketRegime, { label: string; cls: string }> = {
  UPTREND: { label: '상승추세', cls: 'bg-emerald-900/70 text-emerald-300 border border-emerald-700' },
  DOWNTREND: { label: '하락추세', cls: 'bg-red-900/70 text-red-300 border border-red-800' },
  SIDEWAYS: { label: '횡보', cls: 'bg-gray-700/70 text-gray-300 border border-gray-600' },
  HIGH_VOL: { label: '고변동', cls: 'bg-amber-900/70 text-amber-300 border border-amber-700' },
}

const CONFIDENCE_CLS: Record<string, string> = {
  HIGH: 'bg-emerald-900/70 text-emerald-300 border border-emerald-700',
  MEDIUM: 'bg-sky-900/70 text-sky-300 border border-sky-700',
  LOW: 'bg-amber-900/70 text-amber-300 border border-amber-700',
}

/** 추천 태그 시드 (자유 입력 허용) — spec §7-4 */
const TAG_SEEDS = [
  '거래량부족',
  '변동성과다',
  '뉴스이벤트',
  '손절잘됨',
  '익절놓침',
  '추세양호',
  '슬리피지큼',
  '갭상승',
  '갭하락',
]

// ──────────────────────────────────────────────
// 포맷 유틸 (한국어 · 천단위 콤마)
// ──────────────────────────────────────────────

/** 오늘 날짜 (KST, yyyy-MM-dd) */
function todayKst(): string {
  const fmt = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
  return fmt.format(new Date()) // en-CA → yyyy-MM-dd
}

function fmtKrw(v: number | null): string {
  if (v === null) return '데이터 없음'
  return `${Math.round(v).toLocaleString('ko-KR')}원`
}

function fmtPct(v: number | null, withSign = false): string {
  if (v === null) return '—'
  const sign = withSign && v > 0 ? '+' : ''
  return `${sign}${v.toFixed(2)}%`
}

/** 양수=초록, 음수=빨강, 0/null=회색 */
function signedCls(v: number | null): string {
  if (v === null || v === 0) return 'text-gray-400'
  return v > 0 ? 'text-emerald-400' : 'text-red-400'
}

function fmtDateKo(iso: string): string {
  // iso = yyyy-MM-dd → MM/dd (요일)
  const d = new Date(`${iso}T00:00:00+09:00`)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleDateString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    weekday: 'short',
  })
}

function fmtTimestampKo(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}

function RegimeBadge({ regime }: { regime: MarketRegime | string | null }) {
  if (!regime || !(regime in REGIME_BADGE)) {
    return <span className="rounded bg-gray-800 px-1.5 py-0.5 text-[11px] text-gray-500">지수 없음</span>
  }
  const meta = REGIME_BADGE[regime as MarketRegime]
  return <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${meta.cls}`}>{meta.label}</span>
}

function ConfidenceBadge({ confidence }: { confidence: StrategyConfidence }) {
  const cls = CONFIDENCE_CLS[confidence.label] ?? CONFIDENCE_CLS.LOW
  return (
    <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${cls}`} title="신뢰도(표본수·일관성 기준)">
      {confidence.labelText}
    </span>
  )
}

// ──────────────────────────────────────────────
// 컴포넌트
// ──────────────────────────────────────────────

export function StrategyPerformanceContent({
  strategyId,
  strategyName,
  userId,
}: StrategyPerformanceContentProps) {
  const [activeTab, setActiveTab] = useState<ActiveTab>('history')
  const today = useMemo(() => todayKst(), [])

  if (userId === null) {
    return (
      <div className="px-4 py-10 text-center">
        <p className="text-sm text-gray-400">사용자 정보가 없습니다</p>
        <p className="mt-1 text-xs text-gray-600">셋업 마법사를 완료한 후 이용해주세요</p>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {strategyName && (
        <p className="text-xs text-gray-500">
          전략 <span className="font-medium text-gray-300">{strategyName}</span> · ID {strategyId}
        </p>
      )}

      {/* 탭 네비게이션 (BacktestPage 패턴) */}
      <div className="flex gap-1 rounded-lg border border-gray-800 bg-gray-900 p-1">
        <button
          type="button"
          className={[
            'flex-1 rounded-md py-1.5 text-sm font-medium transition-colors',
            activeTab === 'history' ? 'bg-gray-800 text-white' : 'text-gray-500 hover:text-gray-300',
          ].join(' ')}
          onClick={() => setActiveTab('history')}
        >
          성과 히스토리
        </button>
        <button
          type="button"
          className={[
            'flex-1 rounded-md py-1.5 text-sm font-medium transition-colors',
            activeTab === 'note' ? 'bg-gray-800 text-white' : 'text-gray-500 hover:text-gray-300',
          ].join(' ')}
          onClick={() => setActiveTab('note')}
        >
          메모
        </button>
      </div>

      {activeTab === 'history' && (
        <HistoryTab strategyId={strategyId} userId={userId} from={HISTORY_FROM} to={today} />
      )}
      {activeTab === 'note' && (
        <NoteTab strategyId={strategyId} userId={userId} from={HISTORY_FROM} to={today} today={today} />
      )}
    </div>
  )
}

// ──────────────────────────────────────────────
// 탭1 — 성과 히스토리
// ──────────────────────────────────────────────

function HistoryTab({
  strategyId,
  userId,
  from,
  to,
}: {
  strategyId: number
  userId: number
  from: string
  to: string
}) {
  const summaryQuery = useStrategyPerformanceSummary(strategyId, userId)
  const historyQuery = useStrategyPerformanceHistory(strategyId, userId, from, to)

  return (
    <div className="space-y-4">
      {/* 누적 요약 */}
      {summaryQuery.isLoading ? (
        <SkeletonBox label="요약을 불러오는 중..." />
      ) : summaryQuery.isError ? (
        <ErrorBox onRetry={() => summaryQuery.refetch()} />
      ) : summaryQuery.data && !summaryQuery.data.hasData ? (
        <div className="rounded-xl border border-gray-800 bg-gray-900 p-5 text-center">
          <p className="text-sm text-gray-300">아직 청산된 거래가 없습니다</p>
          <p className="mt-1 text-xs text-gray-600">
            왕복(매수→완전 청산)이 발생하면 다음 일배치(00:30) 후 성과가 누적됩니다
          </p>
        </div>
      ) : summaryQuery.data ? (
        <SummaryPanel summary={summaryQuery.data} />
      ) : null}

      {/* 일자별 목록 */}
      <div>
        <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-500">일자별 성과</h4>
        {historyQuery.isLoading ? (
          <SkeletonBox label="히스토리를 불러오는 중..." />
        ) : historyQuery.isError ? (
          <ErrorBox onRetry={() => historyQuery.refetch()} />
        ) : !historyQuery.data || historyQuery.data.rows.length === 0 ? (
          <div className="rounded-lg border border-gray-800 bg-gray-900 px-4 py-6 text-center text-sm text-gray-500">
            표시할 일자별 성과가 없습니다
          </div>
        ) : (
          <ul className="space-y-2">
            {historyQuery.data.rows.map((row) => (
              <HistoryRow key={row.tradeDate} row={row} />
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function SummaryPanel({
  summary,
}: {
  summary: NonNullable<ReturnType<typeof useStrategyPerformanceSummary>['data']>
}) {
  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <ConfidenceBadge confidence={summary.confidence} />
        <span className="text-xs text-gray-500">
          표본 {summary.totalRoundTrips}왕복 · 거래일 {summary.tradingDays}일
        </span>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Metric label="누적 승률" value={fmtPct(summary.winRatePct)} sub={`${summary.totalWins}승 ${summary.totalLosses}패`} />
        <Metric
          label="일평균 수익률"
          value={fmtPct(summary.avgDailyReturnPct, true)}
          valueCls={signedCls(summary.avgDailyReturnPct)}
        />
        <Metric label="평균 초과수익 α" value={fmtPct(summary.avgAlphaPct, true)} valueCls={signedCls(summary.avgAlphaPct)} />
        <Metric label="누적 실현손익" value={fmtKrw(summary.totalRealizedPnl)} valueCls={signedCls(summary.totalRealizedPnl)} />
      </div>

      {/* 레짐별 승률 */}
      {summary.regimeBreakdown.length > 0 && (
        <div className="mt-4 border-t border-gray-800 pt-3">
          <p className="mb-2 text-xs font-semibold text-gray-400">레짐별 승률 — 이런 장에 좋았다</p>
          <div className="flex flex-wrap gap-2">
            {summary.regimeBreakdown.map((r) => (
              <div
                key={r.regime}
                className="flex items-center gap-1.5 rounded-lg border border-gray-800 bg-gray-950 px-2.5 py-1.5"
              >
                <RegimeBadge regime={r.regime} />
                <span className="text-sm font-semibold text-gray-200">{fmtPct(r.winRatePct)}</span>
                <span className="text-[11px] text-gray-500">
                  ({r.winCount}/{r.winCount + r.lossCount} · {r.tradingDays}일)
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function Metric({
  label,
  value,
  sub,
  valueCls,
}: {
  label: string
  value: string
  sub?: string
  valueCls?: string
}) {
  return (
    <div className="rounded-lg bg-gray-950 px-3 py-2">
      <p className="text-[11px] text-gray-500">{label}</p>
      <p className={`mt-0.5 text-base font-bold ${valueCls ?? 'text-gray-100'}`}>{value}</p>
      {sub && <p className="text-[11px] text-gray-600">{sub}</p>}
    </div>
  )
}

function HistoryRow({ row }: { row: StrategyPerformanceRow }) {
  return (
    <li className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2.5">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-gray-200">{fmtDateKo(row.tradeDate)}</span>
          <RegimeBadge regime={row.marketRegime} />
          {row.benchmarkChangePct !== null && (
            <span className="text-[11px] text-gray-500" title="그날 벤치마크 지수 등락률">
              지수 <span className={signedCls(row.benchmarkChangePct)}>{fmtPct(row.benchmarkChangePct, true)}</span>
            </span>
          )}
        </div>
        <span className={`text-sm font-bold ${signedCls(row.realizedPnl)}`}>{fmtKrw(row.realizedPnl)}</span>
      </div>
      <div className="mt-1.5 grid grid-cols-4 gap-1 text-[11px]">
        <span className="text-gray-500">
          왕복 <span className="font-medium text-gray-300">{row.roundTripCount}</span>
        </span>
        <span className="text-gray-500">
          승패 <span className="font-medium text-gray-300">{row.winCount}/{row.lossCount}</span>
        </span>
        <span className="text-gray-500">
          수익률 <span className={`font-medium ${signedCls(row.returnPct)}`}>{fmtPct(row.returnPct, true)}</span>
        </span>
        <span className="text-gray-500" title="초과수익 α (수익률 − 지수 등락률)">
          α <span className={`font-medium ${signedCls(row.alphaPct)}`}>{fmtPct(row.alphaPct, true)}</span>
        </span>
      </div>
    </li>
  )
}

// ──────────────────────────────────────────────
// 탭2 — 메모
// ──────────────────────────────────────────────

function NoteTab({
  strategyId,
  userId,
  from,
  to,
  today,
}: {
  strategyId: number
  userId: number
  from: string
  to: string
  today: string
}) {
  const notesQuery = useStrategyNotes(strategyId, userId, from, to)
  const upsert = useUpsertStrategyNote()

  const [noteText, setNoteText] = useState('')
  const [tags, setTags] = useState<string[]>([])
  const [tagInput, setTagInput] = useState('')
  const [saved, setSaved] = useState(false)

  // 오늘치 기존 메모 → 편집 시드 (1회만)
  const todaysNote = notesQuery.data?.notes.find((n) => n.tradeDate === today)
  const [seeded, setSeeded] = useState(false)
  if (!seeded && todaysNote && !notesQuery.isLoading) {
    setNoteText(todaysNote.noteText ?? '')
    setTags(todaysNote.tags)
    setSeeded(true)
  }

  function toggleTag(tag: string) {
    setSaved(false)
    setTags((prev) => (prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag]))
  }

  function addFreeTag() {
    const t = tagInput.trim()
    if (!t) return
    if (!tags.includes(t)) setTags((prev) => [...prev, t])
    setTagInput('')
    setSaved(false)
  }

  function handleSave() {
    const trimmed = noteText.trim()
    upsert.mutate(
      {
        strategyId,
        userId,
        date: today,
        body: { noteText: trimmed === '' ? null : trimmed, tags },
      },
      {
        onSuccess: () => {
          setSaved(true)
        },
      },
    )
  }

  const pastNotes = notesQuery.data?.notes.filter((n) => n.tradeDate !== today) ?? []

  return (
    <div className="space-y-4">
      {/* 당일치 입력 */}
      <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
        <div className="mb-2 flex items-center justify-between">
          <h4 className="text-sm font-semibold text-gray-200">오늘 메모 ({fmtDateKo(today)})</h4>
          {todaysNote && <span className="text-[11px] text-gray-500">기존 메모 수정 중</span>}
        </div>
        <textarea
          value={noteText}
          onChange={(e) => {
            setNoteText(e.target.value)
            setSaved(false)
          }}
          rows={3}
          placeholder="예: 오후 변동성 확대, 손절 잘 작동 / 갭상승 후 추세 양호"
          className="w-full resize-none rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 placeholder-gray-600 focus:border-blue-500 focus:outline-none"
        />

        {/* 추천 태그칩 */}
        <div className="mt-2 flex flex-wrap gap-1.5">
          {TAG_SEEDS.map((tag) => {
            const on = tags.includes(tag)
            return (
              <button
                key={tag}
                type="button"
                onClick={() => toggleTag(tag)}
                aria-pressed={on}
                className={[
                  'rounded-full px-2.5 py-0.5 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
                  on
                    ? 'bg-blue-900/70 text-blue-200 border border-blue-700'
                    : 'bg-gray-800 text-gray-400 border border-gray-700 hover:bg-gray-700',
                ].join(' ')}
              >
                {tag}
              </button>
            )
          })}
        </div>

        {/* 자유 태그 입력 + 선택 태그 */}
        <div className="mt-2 flex items-center gap-2">
          <input
            value={tagInput}
            onChange={(e) => setTagInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
                e.preventDefault()
                addFreeTag()
              }
            }}
            placeholder="자유 태그 입력 후 Enter"
            className="flex-1 rounded border border-gray-700 bg-gray-950 px-2.5 py-1 text-xs text-gray-100 placeholder-gray-600 focus:border-blue-500 focus:outline-none"
          />
          <button
            type="button"
            onClick={addFreeTag}
            className="rounded border border-gray-700 px-2.5 py-1 text-xs text-gray-300 hover:bg-gray-800"
          >
            추가
          </button>
        </div>
        {tags.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1.5">
            {tags.map((tag) => (
              <span
                key={tag}
                className="inline-flex items-center gap-1 rounded-full bg-blue-950/60 px-2 py-0.5 text-[11px] text-blue-300"
              >
                {tag}
                <button
                  type="button"
                  onClick={() => toggleTag(tag)}
                  aria-label={`${tag} 태그 제거`}
                  className="text-blue-400 hover:text-blue-200"
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        )}

        {/* 저장 */}
        <div className="mt-3 flex items-center gap-2">
          <button
            type="button"
            onClick={handleSave}
            disabled={upsert.isPending}
            className="rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-blue-400"
          >
            {upsert.isPending ? '저장 중...' : '메모 저장'}
          </button>
          {saved && !upsert.isPending && <span className="text-xs text-emerald-400">저장되었습니다</span>}
          {upsert.isError && <span className="text-xs text-red-400">저장 실패. 다시 시도해 주세요.</span>}
        </div>
      </div>

      {/* 과거 메모 타임라인 */}
      <div>
        <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-500">과거 메모</h4>
        {notesQuery.isLoading ? (
          <SkeletonBox label="메모를 불러오는 중..." />
        ) : notesQuery.isError ? (
          <ErrorBox onRetry={() => notesQuery.refetch()} />
        ) : pastNotes.length === 0 ? (
          <div className="rounded-lg border border-gray-800 bg-gray-900 px-4 py-6 text-center text-sm text-gray-500">
            아직 작성된 과거 메모가 없습니다
          </div>
        ) : (
          <ul className="space-y-2">
            {pastNotes.map((n) => (
              <li key={n.id} className="rounded-lg border border-gray-800 bg-gray-900 px-3 py-2.5">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-semibold text-gray-300">{fmtDateKo(n.tradeDate)}</span>
                  <span className="text-[11px] text-gray-600">수정 {fmtTimestampKo(n.updatedAt)}</span>
                </div>
                {n.noteText && <p className="mt-1 whitespace-pre-wrap text-sm text-gray-300">{n.noteText}</p>}
                {n.tags.length > 0 && (
                  <div className="mt-1.5 flex flex-wrap gap-1">
                    {n.tags.map((tag) => (
                      <span key={tag} className="rounded-full bg-blue-950/60 px-2 py-0.5 text-[11px] text-blue-300">
                        {tag}
                      </span>
                    ))}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

// ──────────────────────────────────────────────
// 공용 상태 박스
// ──────────────────────────────────────────────

function SkeletonBox({ label }: { label: string }) {
  return (
    <div className="flex items-center gap-2 rounded-lg border border-gray-800 bg-gray-900 px-4 py-6 text-sm text-gray-500">
      <span className="h-4 w-4 animate-spin rounded-full border-2 border-gray-700 border-t-blue-500" />
      {label}
    </div>
  )
}

function ErrorBox({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="rounded-lg border border-red-900/50 bg-red-950/20 px-4 py-4 text-center">
      <p className="text-sm text-red-300">데이터를 불러오지 못했습니다</p>
      <button
        type="button"
        onClick={onRetry}
        className="mt-2 rounded border border-red-800 px-3 py-1 text-xs text-red-200 hover:bg-red-900/40"
      >
        다시 시도
      </button>
    </div>
  )
}
