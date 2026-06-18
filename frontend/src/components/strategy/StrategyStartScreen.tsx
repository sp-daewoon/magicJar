/**
 * StrategyStartScreen — 전략 빌더 시작 화면 (spec 2026-06-18 §4.2)
 *
 * 빌더 진입 시 빈 캔버스 대신 시작점을 준다. 새로 큐레이션할 게 0인 재료로만 채운다:
 *   ⭐ 내 전략·즐겨찾기 (DB, owner=USER)
 *   📦 검증 템플릿 (DB, owner=SYSTEM 시드 — 개수는 API가 주는 그대로 렌더, 하드코딩 가정 0)
 *   💬 말로 설명 (기존 AI 생성 모달 트리거)
 *   "빈 캔버스로 직접" 탈출구
 *
 * 데이터 0건이면 "아직 없음 + 빈 캔버스로" 안내 — mock 합성 0 (feedback_no_mock_data).
 * GET 실패해도 빌더 접근을 막지 않음 — 빈 캔버스로 폴백 노출 (spec §9).
 * 룰 5: LLM provider import 0건 (AI 모달 트리거만 위임)
 */
import { useMemo, useState } from 'react'
import { useStrategyStart, useToggleFavorite } from '../../api/hooks/useStrategyStart'
import type { StrategyStartItem } from '../../api/strategy'

interface StrategyStartScreenProps {
  userId: number | null | undefined
  /** 템플릿·내 전략 카드 클릭 → 빌더로 로드 */
  onPick: (item: StrategyStartItem) => void
  /** "빈 캔버스로 직접" */
  onBlank: () => void
  /** "말로 설명" — 기존 AI 생성 모달 트리거 */
  onDescribe: () => void
}

function matchesQuery(item: StrategyStartItem, q: string): boolean {
  if (!q) return true
  const hay = `${item.name} ${item.nameKo ?? ''} ${item.tf} ${item.dslEntry}`.toLowerCase()
  return hay.includes(q)
}

function riskBadges(item: StrategyStartItem): string {
  const parts: string[] = []
  if (item.takeProfitPct != null) parts.push(`익절 ${item.takeProfitPct}%`)
  if (item.fixedStopLossPct != null) parts.push(`손절 ${item.fixedStopLossPct}%`)
  return parts.join(' · ')
}

function StrategyCard({
  item,
  onPick,
  onToggleFavorite,
  favoritePending,
}: {
  item: StrategyStartItem
  onPick: (item: StrategyStartItem) => void
  onToggleFavorite: (item: StrategyStartItem) => void
  favoritePending: boolean
}) {
  const risk = riskBadges(item)
  return (
    <div className="group relative flex min-w-0 flex-col rounded-xl border border-gray-700 bg-gray-900 p-3 transition-colors hover:border-blue-500 hover:bg-blue-950/20">
      <button
        type="button"
        onClick={() => onPick(item)}
        className="min-w-0 flex-1 text-left focus:outline-none"
        aria-label={`${item.nameKo || item.name} 불러오기`}
      >
        <span className="block break-words pr-7 text-sm font-semibold text-gray-100">
          {item.nameKo || item.name}
        </span>
        <span className="mt-1 block break-words font-mono text-[11px] leading-4 text-gray-500">
          {item.dslEntry || '(매수 조건 없음)'}
        </span>
        <span className="mt-2 flex flex-wrap items-center gap-1.5 text-[11px]">
          <span className="rounded bg-gray-800 px-1.5 py-0.5 text-amber-300">기준봉 {item.tf}</span>
          {risk && <span className="rounded bg-gray-800 px-1.5 py-0.5 text-gray-400">{risk}</span>}
        </span>
      </button>
      <button
        type="button"
        onClick={() => onToggleFavorite(item)}
        disabled={favoritePending}
        aria-label={item.favorite ? `${item.nameKo || item.name} 즐겨찾기 해제` : `${item.nameKo || item.name} 즐겨찾기 추가`}
        aria-pressed={item.favorite}
        className="absolute right-2 top-2 rounded p-1 text-lg leading-none transition-colors hover:bg-gray-800 disabled:opacity-40"
        title={item.favorite ? '즐겨찾기 해제' : '즐겨찾기 추가'}
      >
        <span className={item.favorite ? 'text-amber-400' : 'text-gray-600'}>
          {item.favorite ? '★' : '☆'}
        </span>
      </button>
    </div>
  )
}

export function StrategyStartScreen({ userId, onPick, onBlank, onDescribe }: StrategyStartScreenProps) {
  const { data, isLoading, isError, error } = useStrategyStart(userId)
  const toggleFavorite = useToggleFavorite(userId)
  const [query, setQuery] = useState('')

  const q = query.trim().toLowerCase()

  const templates = data?.templates ?? []
  const myStrategies = data?.myStrategies ?? []

  const filteredTemplates = useMemo(
    () => templates.filter((item) => matchesQuery(item, q)),
    [templates, q],
  )
  const filteredMine = useMemo(
    () => myStrategies.filter((item) => matchesQuery(item, q)),
    [myStrategies, q],
  )

  const handleToggleFavorite = (item: StrategyStartItem) => {
    toggleFavorite.mutate({ id: item.id, on: !item.favorite })
  }

  const isEmpty = !isLoading && !isError && templates.length === 0 && myStrategies.length === 0

  return (
    <div className="flex h-full flex-col overflow-y-auto bg-gray-950 p-6" aria-label="전략 시작 화면">
      <div className="mx-auto w-full max-w-4xl">
        <header className="mb-5">
          <h1 className="text-lg font-semibold text-white">전략 만들기 시작</h1>
          <p className="mt-1 text-sm text-gray-400">
            검증된 템플릿이나 내 전략에서 출발하거나, 말로 설명해 초안을 만들거나, 빈 캔버스에서 직접 조립하세요.
          </p>
        </header>

        {/* 입구 버튼 행 — 말로 설명 / 빈 캔버스 */}
        <div className="mb-5 flex flex-wrap gap-2">
          <button
            type="button"
            onClick={onDescribe}
            className="rounded-lg border border-violet-700 bg-violet-950/30 px-4 py-2 text-sm font-medium text-violet-100 transition-colors hover:border-violet-500 hover:bg-violet-900/40"
          >
            💬 말로 설명해서 만들기
          </button>
          <button
            type="button"
            onClick={onBlank}
            className="rounded-lg border border-gray-700 bg-gray-900 px-4 py-2 text-sm font-medium text-gray-200 transition-colors hover:border-blue-500 hover:bg-blue-950/20"
          >
            빈 캔버스로 직접
          </button>
        </div>

        {/* 검색 */}
        <div className="mb-5">
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="전략명·조건으로 검색 (예: FVG, 미너비니, ma)"
            className="w-full rounded-lg border border-gray-700 bg-gray-900 px-3 py-2 text-sm text-gray-200 placeholder-gray-500 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            aria-label="전략 검색"
          />
        </div>

        {/* 로딩 */}
        {isLoading && (
          <div className="space-y-3" role="status" aria-label="시작 화면 불러오는 중">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-20 animate-pulse rounded-xl border border-gray-800 bg-gray-900" />
            ))}
          </div>
        )}

        {/* 에러 — 빌더 접근을 막지 않음, 빈 캔버스 폴백 (spec §9) */}
        {isError && (
          <div className="rounded-xl border border-red-900/50 bg-red-900/20 p-4 text-sm text-red-300">
            <p className="font-medium">시작 화면을 불러오지 못했습니다</p>
            <p className="mt-1 text-xs text-red-400/80">
              {error instanceof Error ? error.message : '알 수 없는 오류'}
            </p>
            <button
              type="button"
              onClick={onBlank}
              className="mt-3 rounded-md border border-red-800 px-3 py-1.5 text-xs text-red-100 hover:bg-red-900/40"
            >
              빈 캔버스로 직접 시작하기
            </button>
          </div>
        )}

        {/* 빈 상태 — mock 0 (feedback_no_mock_data) */}
        {isEmpty && (
          <div className="rounded-xl border border-dashed border-gray-800 p-8 text-center">
            <p className="text-sm text-gray-400">아직 템플릿·전략이 없습니다.</p>
            <p className="mt-1 text-xs text-gray-600">말로 설명하거나 빈 캔버스에서 직접 만들어 보세요.</p>
            <button
              type="button"
              onClick={onBlank}
              className="mt-4 rounded-lg border border-gray-700 bg-gray-900 px-4 py-2 text-sm font-medium text-gray-200 hover:border-blue-500 hover:bg-blue-950/20"
            >
              빈 캔버스로 직접 시작
            </button>
          </div>
        )}

        {/* 내 전략·즐겨찾기 */}
        {!isLoading && !isError && !isEmpty && (
          <>
            {myStrategies.length > 0 && (
              <section className="mb-6">
                <h2 className="mb-2 text-sm font-semibold text-gray-300">⭐ 내 전략·즐겨찾기</h2>
                {filteredMine.length === 0 ? (
                  <p className="rounded-lg border border-dashed border-gray-800 px-3 py-4 text-center text-xs text-gray-500">
                    검색 결과 없음
                  </p>
                ) : (
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    {filteredMine.map((item) => (
                      <StrategyCard
                        key={item.id}
                        item={item}
                        onPick={onPick}
                        onToggleFavorite={handleToggleFavorite}
                        favoritePending={toggleFavorite.isPending}
                      />
                    ))}
                  </div>
                )}
              </section>
            )}

            {templates.length > 0 && (
              <section className="mb-6">
                <h2 className="mb-2 text-sm font-semibold text-gray-300">
                  📦 검증 템플릿{' '}
                  <span className="text-xs font-normal text-gray-500">({templates.length})</span>
                </h2>
                {filteredTemplates.length === 0 ? (
                  <p className="rounded-lg border border-dashed border-gray-800 px-3 py-4 text-center text-xs text-gray-500">
                    검색 결과 없음
                  </p>
                ) : (
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    {filteredTemplates.map((item) => (
                      <StrategyCard
                        key={item.id}
                        item={item}
                        onPick={onPick}
                        onToggleFavorite={handleToggleFavorite}
                        favoritePending={toggleFavorite.isPending}
                      />
                    ))}
                  </div>
                )}
              </section>
            )}
          </>
        )}
      </div>
    </div>
  )
}
