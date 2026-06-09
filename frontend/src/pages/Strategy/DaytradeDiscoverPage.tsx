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
 * - 결과 0건/skip 시 mock 합성 0 — "데이터 없음 / 일봉 수집 중 / 후보 없음" 명시.
 *   (현재 일봉 전수 backfill 진행 중이라 부분 데이터일 수 있음 — graceful 처리)
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

function hasCandleDataSkips(skippedReasons: Record<string, number>): boolean {
  return Object.keys(skippedReasons).some(
    (reason) => reason.includes('캔들') || reason.includes('Candle'),
  )
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
              <p className="mb-1.5 text-xs text-gray-500">skip 사유</p>
              <ScreenerSkipReasonsBadge reasons={data.skippedReasons} />
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
            <div className="rounded-xl border border-gray-800 bg-gray-900/40 px-4 py-10 text-center">
              <p className="text-sm text-gray-400">
                {data.evaluatedCount === 0
                  ? '평가 가능한 일봉 데이터가 아직 없습니다.'
                  : hasCandleDataSkips(data.skippedReasons)
                    ? '일봉 데이터 수집 중인 종목이 제외되어 오늘의 후보가 없습니다.'
                    : '조건을 충족한 단타 후보가 없습니다.'}
              </p>
              <p className="mt-2 text-xs text-gray-600">
                현재 일봉 전수 수집이 진행 중일 수 있습니다. 수집 완료 후 다시 발굴해 주세요.
                (mock 후보를 합성하지 않습니다)
              </p>
            </div>
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
