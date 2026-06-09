/**
 * DaytradeDiscoverPage — 단타 발굴 화면 (ADR 0117 R2 + spec §3-R2)
 *
 * "전략을 미리 작성하지 않아도 시스템이 오늘의 단타 후보 TOP N을 수치로 제시" 진입점.
 *
 * 흐름:
 *   빌트인 프리셋 선택(3종 + 전체) → 발굴 실행 → 후보 TOP N 랭킹 리스트(점수·breakdown).
 *   startRealtimeWatch 토글 → STRATEGY owner WS 수요 등록(실시간 관찰 30분, watchlist write 0).
 *
 * ### 가드 (방향성 보존)
 * - 룰 기반 — LLM 호출 0 (anthropic·openai import 0건).
 * - 발굴은 후보 출발점 — 자동 주문·자동 watchlist 삽입 0.
 * - 실시간 관찰 토글 ≠ watchlist 등록 (owner 가드 — STRATEGY owner TTL 30분).
 * - 결과 0건/skip 시 mock 합성 0 — 정직 표기. 단, "수집 중" 어조 금지:
 *   · evaluatedCount>0 && matchedCount===0 → "조건 미충족"(정상 안내).
 *   · evaluatedCount===0 → "평가할 데이터 없음".
 *   skippedReasons["캔들 미수집"]은 비-보통주(ETF·우선주·리츠·SPAC) 설계상 범위 외이며
 *   "수집 진행 중"이 아니다 (ADR 0118 §2-A-1 — 보통주 일봉 누락 사실상 0).
 */
import { useMemo, useState } from 'react'
import { useDaytradeDiscover } from '../../features/screener/hooks/useDaytradeDiscover'
import { DaytradeMatchTable } from '../../features/screener/components/DaytradeMatchTable'
import { ScreenerSkipReasonsBadge } from '../../features/screener/components/ScreenerSkipReasonsBadge'
import {
  DAYTRADE_PRESET_OPTIONS,
  type DaytradeDiscoverResultDto,
} from '../../features/screener/api/daytradeDiscoverApi'
import { useWatchlist, useAddWatchlist } from '../../api/hooks/useWatchlist'
import { useOnboardingStore } from '../../stores/onboarding'

const DEFAULT_LIMIT = 30

function Spinner() {
  return (
    <div
      className="flex flex-col items-center justify-center gap-3 py-16"
      role="status"
      aria-label="universe 전체 발굴 중"
    >
      <div className="h-8 w-8 animate-spin rounded-full border-2 border-gray-700 border-t-emerald-500" />
      <p className="text-sm text-gray-500">오늘의 단타 후보 발굴 중 (최대 ~30초)...</p>
    </div>
  )
}

/**
 * 발굴에 사용된 프리셋명 합성 — 빈 상태 안내 문구용.
 * presets[].title 우선, 없으면 "단타" fallback.
 */
function presetDisplayName(result: DaytradeDiscoverResultDto): string {
  const titles = result.presets.map((p) => p.title).filter(Boolean)
  if (titles.length === 0) return '단타'
  if (titles.length === 1) return titles[0]
  return `${titles[0]} 외 ${titles.length - 1}건`
}

/**
 * "캔들 미수집"(CandleMissing) skip은 비-보통주(ETF·우선주·리츠·SPAC) 설계상 범위 외다.
 * ADR 0118 §2-A-1 — 보통주 일봉 누락은 사실상 0. "수집 진행 중"으로 해석 금지.
 */
function candleMissingCount(skippedReasons: Record<string, number>): number {
  return Object.entries(skippedReasons)
    .filter(
      ([key]) =>
        key === 'CandleMissing' || key.includes('캔들 미수집'),
    )
    .reduce((sum, [, count]) => sum + count, 0)
}

/** 통계 요약 row */
function DiscoverSummary({ result }: { result: DaytradeDiscoverResultDto }) {
  return (
    <div className="flex flex-wrap gap-x-6 gap-y-1 rounded-lg bg-gray-800/60 px-4 py-3 text-sm">
      <span className="text-gray-400">
        Universe{' '}
        <span className="font-semibold text-white">
          {result.universeSize.toLocaleString()}
        </span>
      </span>
      <span className="text-gray-400">
        평가{' '}
        <span className="font-semibold text-white">
          {result.evaluatedCount.toLocaleString()}
        </span>
      </span>
      <span className="text-gray-400">
        후보{' '}
        <span className="font-semibold text-emerald-400">
          {result.matchedCount.toLocaleString()}
        </span>
      </span>
      <span className="text-gray-400">
        소요{' '}
        <span className="font-semibold text-white">
          {result.elapsedMs < 1000
            ? `${result.elapsedMs}ms`
            : `${(result.elapsedMs / 1000).toFixed(1)}s`}
        </span>
      </span>
      {result.realtimeWatchRegistered > 0 && (
        <span className="rounded-full bg-blue-900/40 px-2 py-0.5 text-xs font-medium text-blue-300">
          실시간 관찰 {result.realtimeWatchRegistered}종목 등록
        </span>
      )}
    </div>
  )
}

/** 발굴에 사용된 빌트인 프리셋 메타 + 진입 조건 노출 */
function PresetMetaCard({ result }: { result: DaytradeDiscoverResultDto }) {
  if (result.presets.length === 0) return null
  return (
    <div className="space-y-3">
      {result.presets.map((preset) => (
        <div
          key={preset.presetKey}
          className="rounded-lg border border-gray-800 bg-gray-900/60 px-4 py-3"
        >
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium text-white">{preset.title}</span>
            <span className="rounded bg-gray-800 px-1.5 py-0.5 text-[10px] text-gray-400">
              빌트인
            </span>
            <span className="font-mono text-[10px] text-gray-600">
              {preset.presetKey}
            </span>
          </div>
          {preset.entryRules.length > 0 && (
            <ul className="mt-2 list-inside list-disc space-y-0.5 text-xs text-gray-400">
              {preset.entryRules.map((rule, idx) => (
                <li key={idx}>{rule}</li>
              ))}
            </ul>
          )}
        </div>
      ))}
    </div>
  )
}

/**
 * 빈 상태(후보 0건) 안내 — 응답 필드 기반 정확한 분기.
 *
 * - evaluatedCount === 0 → 정말 평가할 데이터가 없음 (드문 케이스).
 * - evaluatedCount > 0 && matchedCount === 0 → 보통주는 전수 평가됐으나 조건 미충족 (정상).
 *
 * "수집 진행 중" 어조 금지. skippedReasons["캔들 미수집"]은
 * 비-보통주(ETF·우선주·리츠·SPAC) 설계상 범위 외 (ADR 0118 §2-A-1).
 */
function DiscoverEmptyState({ result }: { result: DaytradeDiscoverResultDto }) {
  const noData = result.evaluatedCount === 0
  const missing = candleMissingCount(result.skippedReasons)

  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900/40 px-4 py-10 text-center">
      {noData ? (
        <>
          <p className="text-sm text-gray-400">평가할 일봉 데이터가 없습니다.</p>
          <p className="mt-2 text-xs text-gray-600">
            아직 평가 대상 종목이 산출되지 않았습니다. 잠시 후 다시 발굴해 주세요. (mock 후보를
            합성하지 않습니다)
          </p>
        </>
      ) : (
        <>
          <p className="text-sm text-gray-300">
            오늘 '{presetDisplayName(result)}' 조건을 충족한 종목이 없습니다.{' '}
            <span className="text-gray-500">
              (보통주 {result.evaluatedCount.toLocaleString()}종목 평가 완료)
            </span>
          </p>
          <p className="mt-2 text-xs text-gray-500">
            다른 프리셋을 시도하거나, 조건이 완화된 setup을 선택해 보세요. (mock 후보를 합성하지
            않습니다)
          </p>
        </>
      )}

      {missing > 0 && (
        <details className="mx-auto mt-4 max-w-md text-left">
          <summary className="cursor-pointer text-[11px] text-gray-600 hover:text-gray-500">
            범위 외 {missing.toLocaleString()}종목은 왜 제외되나요?
          </summary>
          <p className="mt-1.5 text-[11px] leading-relaxed text-gray-600">
            ETF·우선주·리츠·SPAC 등 비보통주는 단타 발굴 대상(보통주)이 아니므로 설계상 일봉을
            적재하지 않습니다 (ADR 0118). 데이터가 덜 모인 것이 아니라 범위 밖이며, 보통주 일봉
            누락은 사실상 없습니다.
          </p>
        </details>
      )}
    </div>
  )
}

export function DaytradeDiscoverPage() {
  const registeredUserId = useOnboardingStore((s) => s.registeredUserId)

  const [presetKey, setPresetKey] = useState<string | null>(null)
  const [startRealtimeWatch, setStartRealtimeWatch] = useState(false)

  const { mutate, data, isPending, error, reset } = useDaytradeDiscover()

  // 관심종목 등록 여부 표시 + 명시적 단건 추가 (owner 가드 — 자동 삽입 아님)
  const { data: watchlist } = useWatchlist()
  const addWatchlist = useAddWatchlist()
  const watchedCodes = useMemo(
    () => new Set((watchlist ?? []).map((w) => w.code)),
    [watchlist],
  )
  const [addingCode, setAddingCode] = useState<string | null>(null)

  function handleDiscover() {
    if (registeredUserId === null) return
    reset()
    mutate({
      userId: registeredUserId,
      presetKey,
      interval: '1d',
      limit: DEFAULT_LIMIT,
      startRealtimeWatch,
    })
  }

  function handleAddWatchlist(code: string) {
    if (watchedCodes.has(code)) return
    setAddingCode(code)
    addWatchlist.mutate(
      { code },
      {
        onSettled: () => setAddingCode(null),
      },
    )
  }

  return (
    <div className="mx-auto max-w-4xl px-4 py-6">
      {/* 헤더 */}
      <header className="mb-5">
        <h1 className="text-xl font-bold text-white">단타 발굴</h1>
        <p className="mt-1 text-sm text-gray-500">
          전략을 미리 작성하지 않아도, 시스템 빌트인 단타 setup 프리셋으로 오늘의 후보 TOP{' '}
          {DEFAULT_LIMIT}을 점수와 함께 제시합니다. 장전 setup = 일봉 기준 · KIS 추가 호출 0 · 룰 기반(AI
          호출 0).
        </p>
      </header>

      {/* 컨트롤 패널 */}
      <section className="mb-5 space-y-4 rounded-xl border border-gray-800 bg-gray-900/40 p-4">
        {/* 프리셋 선택 */}
        <div>
          <p className="mb-2 text-xs font-medium text-gray-400">빌트인 프리셋</p>
          <div className="flex flex-wrap gap-2">
            {DAYTRADE_PRESET_OPTIONS.map((option) => {
              const active = presetKey === option.key
              return (
                <button
                  key={option.key ?? '__all__'}
                  type="button"
                  onClick={() => setPresetKey(option.key)}
                  title={option.description}
                  className={[
                    'rounded-lg px-3 py-2 text-sm transition-colors',
                    active
                      ? 'bg-emerald-600 text-white'
                      : 'border border-gray-700 bg-gray-900 text-gray-300 hover:bg-gray-800',
                  ].join(' ')}
                >
                  {option.label}
                </button>
              )
            })}
          </div>
          <p className="mt-2 text-xs text-gray-600">
            {DAYTRADE_PRESET_OPTIONS.find((o) => o.key === presetKey)?.description}
          </p>
        </div>

        {/* 실시간 관찰 토글 + 발굴 실행 */}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <label className="flex cursor-pointer items-start gap-2 text-sm text-gray-300">
            <input
              type="checkbox"
              checked={startRealtimeWatch}
              onChange={(e) => setStartRealtimeWatch(e.target.checked)}
              className="mt-0.5 cursor-pointer accent-blue-500"
            />
            <span>
              발굴 후보 실시간 관찰 시작 (30분)
              <span className="ml-1 block text-xs text-gray-600">
                STRATEGY 실시간 시세 수요로만 등록됩니다. 관심종목(watchlist)에 추가되지 않습니다.
              </span>
            </span>
          </label>

          <button
            type="button"
            disabled={registeredUserId === null || isPending}
            onClick={handleDiscover}
            className={[
              'rounded-lg px-5 py-2.5 text-sm font-semibold transition-colors',
              registeredUserId !== null && !isPending
                ? 'bg-emerald-600 text-white hover:bg-emerald-700'
                : 'cursor-not-allowed bg-gray-700 text-gray-500',
            ].join(' ')}
            aria-busy={isPending}
          >
            {isPending ? '발굴 중...' : '후보 발굴'}
          </button>
        </div>
      </section>

      {/* userId 누락 (setup 미완) */}
      {registeredUserId === null && (
        <div className="flex flex-col items-center justify-center gap-3 rounded-xl border border-gray-800 bg-gray-900/40 py-16">
          <p className="text-sm text-yellow-400">사용자 설정이 완료되지 않았습니다.</p>
          <p className="text-xs text-gray-500">
            설정 화면에서 KIS 키 등록 후 다시 시도해 주세요.
          </p>
        </div>
      )}

      {/* 발굴 중 */}
      {registeredUserId !== null && isPending && <Spinner />}

      {/* 에러 */}
      {registeredUserId !== null && error && !isPending && (
        <div className="flex flex-col items-center justify-center gap-3 rounded-xl border border-red-900/40 bg-red-950/20 py-12">
          <p className="text-sm text-red-400">발굴 중 오류가 발생했습니다.</p>
          <p className="max-w-md text-center text-xs text-gray-500">{error.message}</p>
          <button
            type="button"
            onClick={handleDiscover}
            className="mt-2 rounded-lg bg-gray-800 px-4 py-2 text-sm text-gray-300 hover:bg-gray-700"
          >
            재시도
          </button>
        </div>
      )}

      {/* 결과 */}
      {data && !isPending && (
        <div className="space-y-4">
          <DiscoverSummary result={data} />

          <PresetMetaCard result={data} />

          {/* skip 매트릭스 (캔들 미수집·부족 등 — mock 0, 정직 표기) */}
          {Object.keys(data.skippedReasons).length > 0 && (
            <div>
              <p className="mb-1.5 text-xs text-gray-500">평가 제외 사유</p>
              <ScreenerSkipReasonsBadge reasons={data.skippedReasons} />
              {candleMissingCount(data.skippedReasons) > 0 && (
                <p className="mt-1.5 text-[11px] text-gray-600">
                  ※ '캔들 미수집'은 ETF·우선주 등 비보통주(설계상 발굴 범위 외)이며 수집 진행 중이
                  아닙니다 (ADR 0118).
                </p>
              )}
            </div>
          )}

          {/* 후보 리스트 또는 빈 상태 */}
          {data.matches.length > 0 ? (
            <DaytradeMatchTable
              matches={data.matches}
              onAddWatchlist={handleAddWatchlist}
              addingCode={addingCode}
              watchedCodes={watchedCodes}
            />
          ) : (
            <DiscoverEmptyState result={data} />
          )}
        </div>
      )}

      {/* 초기 안내 (아직 발굴 전) */}
      {registeredUserId !== null && !data && !isPending && !error && (
        <div className="rounded-xl border border-dashed border-gray-800 px-4 py-12 text-center text-sm text-gray-500">
          프리셋을 선택하고 "후보 발굴"을 누르면 오늘의 단타 후보가 표시됩니다.
        </div>
      )}
    </div>
  )
}
