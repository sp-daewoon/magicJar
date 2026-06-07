import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { strategyApi, type StrategyDto } from '../../api/strategy'
import {
  strategyResearchApi,
  strategyResearchErrorMessage,
  type StrategyResearchCandidateDefinitionDto,
} from '../../api/strategyResearch'

interface Props {
  /** 등록된 사용자 ID (숫자). null이면 내 전략 추가 기능 비활성. */
  registeredUserId: number | null
}

/**
 * 리서치 후보 전략 라이브러리 카드 (research-candidates-db / V74).
 *
 * - DB(strategy_research_candidate)에 등록된 후보 목록을 렌더한다(리서치 실행이 사용하는 후보).
 * - "내 전략을 후보로 추가" 진입점: 전략 선택 드롭다운 + 추가 버튼.
 * - 후보 삭제(builtin 포함). 리서치 실행 동작 자체는 무변경.
 */
export default function ResearchCandidateLibraryCard({ registeredUserId }: Props) {
  const queryClient = useQueryClient()
  const [selectedStrategyId, setSelectedStrategyId] = useState<string>('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionNotice, setActionNotice] = useState<string | null>(null)

  const candidatesQuery = useQuery({
    queryKey: ['strategy-research', 'candidates'],
    queryFn: () => strategyResearchApi.getCandidates(false),
  })

  const strategiesQuery = useQuery({
    queryKey: ['strategy-research', 'my-strategies', registeredUserId],
    queryFn: () => strategyApi.list(registeredUserId ?? undefined),
    enabled: registeredUserId != null,
  })

  const addMutation = useMutation({
    mutationFn: (strategyId: number) =>
      strategyResearchApi.addCandidateFromStrategy({
        userId: String(registeredUserId),
        strategyId,
      }),
    onSuccess: (created) => {
      setActionError(null)
      setActionNotice(`후보로 추가되었습니다: ${created.title}`)
      setSelectedStrategyId('')
      void queryClient.invalidateQueries({ queryKey: ['strategy-research', 'candidates'] })
    },
    onError: (error) => {
      setActionNotice(null)
      setActionError(strategyResearchErrorMessage(error))
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: number) => strategyResearchApi.deleteCandidate(id),
    onSuccess: () => {
      setActionError(null)
      setActionNotice('후보를 삭제했습니다.')
      void queryClient.invalidateQueries({ queryKey: ['strategy-research', 'candidates'] })
    },
    onError: (error) => {
      setActionNotice(null)
      setActionError(strategyResearchErrorMessage(error))
    },
  })

  const candidates = candidatesQuery.data ?? []
  const builtinCount = useMemo(() => candidates.filter((c) => c.builtIn).length, [candidates])
  const userCount = candidates.length - builtinCount

  const handleAdd = () => {
    const strategyId = Number(selectedStrategyId)
    if (!Number.isFinite(strategyId) || strategyId <= 0) {
      setActionNotice(null)
      setActionError('후보로 추가할 전략을 선택하세요.')
      return
    }
    addMutation.mutate(strategyId)
  }

  return (
    <section
      className="mb-4 rounded-lg border border-gray-800 bg-gray-900 p-4"
      aria-labelledby="research-candidate-library-heading"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="research-candidate-library-heading" className="text-sm font-semibold text-gray-200">
            후보 전략 라이브러리
          </h2>
          <p className="mt-0.5 text-xs text-gray-500">
            리서치 실행이 평가하는 후보 전략 목록(DB 관리) · 기본 {builtinCount}개 · 내 전략 {userCount}개
          </p>
        </div>
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <label htmlFor="research-candidate-strategy-select" className="text-xs text-gray-400">
          내 전략을 후보로 추가
        </label>
        <select
          id="research-candidate-strategy-select"
          className="min-w-[14rem] rounded border border-gray-700 bg-gray-950 px-2 py-1 text-xs text-gray-200 disabled:opacity-50"
          value={selectedStrategyId}
          onChange={(e) => setSelectedStrategyId(e.target.value)}
          disabled={registeredUserId == null || strategiesQuery.isLoading}
        >
          <option value="">전략 선택…</option>
          {(strategiesQuery.data ?? []).map((s: StrategyDto) => (
            <option key={s.id} value={s.id}>
              {s.nameKo || s.name}
            </option>
          ))}
        </select>
        <button
          type="button"
          className="rounded bg-indigo-600 px-3 py-1 text-xs font-medium text-white hover:bg-indigo-500 disabled:opacity-50"
          onClick={handleAdd}
          disabled={registeredUserId == null || !selectedStrategyId || addMutation.isPending}
        >
          {addMutation.isPending ? '추가 중…' : '후보로 추가'}
        </button>
      </div>

      {registeredUserId == null && (
        <p className="mt-2 text-xs text-amber-300">사용자 등록 후 내 전략을 후보로 추가할 수 있습니다.</p>
      )}
      {actionError && (
        <div className="mt-2 rounded border border-red-900 bg-red-950/30 px-3 py-2 text-xs text-red-200">{actionError}</div>
      )}
      {actionNotice && (
        <div className="mt-2 rounded border border-emerald-900 bg-emerald-950/30 px-3 py-2 text-xs text-emerald-200">
          {actionNotice}
        </div>
      )}

      <div className="mt-3 space-y-2">
        {candidatesQuery.isLoading && <p className="text-xs text-gray-500">후보를 불러오는 중…</p>}
        {!candidatesQuery.isLoading && candidates.length === 0 && (
          <p className="text-xs text-gray-500">등록된 후보 전략이 없습니다.</p>
        )}
        {candidates.map((candidate: StrategyResearchCandidateDefinitionDto) => (
          <div
            key={candidate.id}
            className="rounded border border-gray-800 bg-gray-950/60 px-3 py-2"
            data-testid="research-candidate-row"
          >
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium text-gray-100">{candidate.title}</span>
                <span
                  className={`rounded px-1.5 py-0.5 text-[10px] ${
                    candidate.builtIn ? 'bg-gray-800 text-gray-400' : 'bg-indigo-950 text-indigo-300'
                  }`}
                >
                  {candidate.builtIn ? '기본' : '내 전략'}
                </span>
              </div>
              <button
                type="button"
                className="rounded border border-red-900 px-2 py-0.5 text-[11px] text-red-300 hover:bg-red-950/40 disabled:opacity-50"
                onClick={() => deleteMutation.mutate(candidate.id)}
                disabled={deleteMutation.isPending}
                aria-label={`후보 삭제: ${candidate.title}`}
              >
                후보 삭제
              </button>
            </div>
            <p className="mt-1 text-xs text-gray-500">{candidate.marketFit}</p>
            <code className="mt-1 block break-all text-[11px] text-sky-300">{candidate.entryDsl}</code>
          </div>
        ))}
      </div>
    </section>
  )
}
