/**
 * StrategyBuilderCanvas — 전략 빌더 중앙 캔버스
 *
 * entry / exit 두 트리 탭 토글
 * 트리 렌더 (BlockNode 재귀)
 * DSL 미리보기 readonly textarea
 * 새 블록 추가 — 팔레트에서 onSelect → 현재 탭 루트에 설정
 *
 * ADR 0055 B+C-lite 하이브리드
 * 룰 5: LLM provider import 0건
 */
import { useMemo, useState } from 'react'
import type { BlockNode as BlockNodeType, BlockTree } from '../../lib/dsl/blockTree'
import { serializeBlockTree } from '../../lib/dsl/serializer'
import type { StrategyStartItem } from '../../api/strategy'
import { BlockNode } from './BlockNode'
import { validateTreeRoot } from './builderTreeValidation'

type TreeTab = 'entry' | 'exit'

interface StrategyBuilderCanvasProps {
  tree: BlockTree
  onTreeChange: (updated: BlockTree) => void
  /** 현재 탭 (entry/exit) — Page에서 lift up (BlockPalette 클릭 흐름 정합 hotfix 2026-05-14).
   *  미전달 시 내부 state fallback (test 호환). */
  tab?: TreeTab
  /** 탭 변경 콜백 — 미전달 시 내부 state */
  onTabChange?: (tab: TreeTab) => void
  /** DSL 편집 토글 버튼 클릭 시 */
  onEditDsl?: () => void
  /** 자연어 입력 버튼 */
  onNaturalLanguage?: () => void
  /** AI 전략 생성 버튼 */
  onAiGenerate?: () => void
  /** YAML 다운로드 버튼 */
  onYamlExport?: () => void
  /** YAML 업로드 */
  onYamlImport?: (file: File) => void
  /** 검증 버튼 */
  onValidate?: () => void
  /** 저장 버튼 */
  onSave?: () => void
  /** 평가 버튼 */
  onEvaluate?: () => void
  /** 전략 후보 프리셋 — 시작화면 데이터(DB) 공급 */
  presets?: StrategyStartItem[]
  /** 프리셋 적용 */
  onPresetApply?: (preset: StrategyStartItem) => void
  /** 저장된 strategyId — 평가 활성화 여부 */
  strategyId?: string
  /** 유효성 검사 로딩 */
  isValidating?: boolean
  /** 저장 중 */
  isSaving?: boolean
}

export function StrategyBuilderCanvas({
  tree,
  onTreeChange,
  tab: tabProp,
  onTabChange,
  onEditDsl,
  onNaturalLanguage,
  onAiGenerate,
  onYamlExport,
  onYamlImport,
  onValidate,
  onSave,
  onEvaluate,
  presets = [],
  onPresetApply,
  strategyId,
  isValidating,
  isSaving,
}: StrategyBuilderCanvasProps) {
  // controlled (props) vs uncontrolled (internal state) fallback
  const [internalTab, setInternalTab] = useState<TreeTab>('entry')
  const tab = tabProp ?? internalTab
  const setTab = onTabChange ?? setInternalTab

  // DSL 미리보기 (실시간 갱신)
  const { entry: entryDsl, exit: exitDsl } = useMemo(() => serializeBlockTree(tree), [tree])
  const currentDsl = tab === 'entry' ? entryDsl : exitDsl
  const currentNode = tab === 'entry' ? tree.entry : tree.exit

  const handleNodeChange = (updated: BlockNodeType) => {
    onTreeChange(tab === 'entry' ? { ...tree, entry: updated } : { ...tree, exit: updated })
  }

  const handleNodeRemove = () => {
    onTreeChange(tab === 'entry' ? { ...tree, entry: undefined } : { ...tree, exit: undefined })
  }

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (file && onYamlImport) {
      onYamlImport(file)
      e.target.value = ''
    }
  }

  const isTreeEmpty = !currentNode

  // 인라인 검증 (빌더 고도화 B) — 검증 버튼 전에도 빈 슬롯/미완성을 즉시 표시
  const treeValidation = useMemo(
    () => validateTreeRoot(currentNode, tab === 'entry' ? '매수' : '매도'),
    [currentNode, tab],
  )
  const hasIncompleteSlots = !treeValidation.empty && !treeValidation.complete
  // 검증·저장 버튼은 항상 매수(entry) DSL 기준이므로 entry 트리의 완성도로 게이트한다.
  const entryValidation = useMemo(() => validateTreeRoot(tree.entry, '매수'), [tree.entry])

  return (
    <div className="flex flex-1 flex-col overflow-hidden bg-gray-950">
      {/* 탭 바 */}
      <div className="flex items-center gap-2 border-b border-gray-800 px-4 py-2">
        <button
          className={[
            'rounded-lg px-3 py-1 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
            tab === 'entry'
              ? 'bg-blue-600 text-white'
              : 'text-gray-400 hover:bg-gray-800 hover:text-gray-200',
          ].join(' ')}
          onClick={() => setTab('entry')}
        >
          매수 조건 (Entry)
        </button>
        <button
          className={[
            'rounded-lg px-3 py-1 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
            tab === 'exit'
              ? 'bg-blue-600 text-white'
              : 'text-gray-400 hover:bg-gray-800 hover:text-gray-200',
          ].join(' ')}
          onClick={() => setTab('exit')}
        >
          매도 조건 (Exit)
        </button>
        <span
          className={[
            'ml-auto text-xs',
            isTreeEmpty
              ? 'text-gray-500'
              : hasIncompleteSlots
                ? 'text-amber-300'
                : 'text-emerald-400',
          ].join(' ')}
          role="status"
        >
          {isTreeEmpty
            ? '팔레트에서 블록을 선택하세요'
            : hasIncompleteSlots
              ? `빈 슬롯 ${treeValidation.emptySlotCount}개 — 채워야 검증·저장 가능`
              : '조건 완성 — 검증·저장 가능'}
        </span>
      </div>

      {presets.length > 0 && (
        <div className="flex items-center gap-2 overflow-x-auto border-b border-gray-800 px-4 py-2">
          <span className="shrink-0 text-xs font-medium text-gray-500">프리셋 후보</span>
          {presets.map((preset) => (
            <button
              key={preset.id}
              type="button"
              className="shrink-0 rounded-md border border-gray-700 bg-gray-900 px-2.5 py-1 text-xs text-gray-300 transition-colors hover:border-teal-500 hover:bg-teal-950/40 hover:text-teal-100 focus:outline-none focus:ring-2 focus:ring-teal-400"
              onClick={() => onPresetApply?.(preset)}
              title={`${preset.dslEntry} · 추천 기준봉 ${preset.tf}`}
            >
              {preset.nameKo || preset.name}
            </button>
          ))}
        </div>
      )}

      {/* 트리 영역 */}
      <div
        className="flex-1 overflow-auto p-4"
        role="tree"
        aria-label={`${tab === 'entry' ? '매수' : '매도'} 조건 트리`}
      >
        {currentNode ? (
          <BlockNode
            node={currentNode}
            onChange={handleNodeChange}
            onRemove={handleNodeRemove}
            depth={0}
          />
        ) : (
          <div className="flex h-40 flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed border-gray-800 text-gray-600">
            <span className="text-4xl">+</span>
            <p className="text-sm">왼쪽 팔레트에서 블록을 선택하거나</p>
            <p className="text-xs">자연어로 입력하기 버튼을 사용하세요</p>
          </div>
        )}
      </div>

      {/* 인라인 검증 경고 (빌더 고도화 B) — 검증 버튼 전에도 빈 슬롯 표시 */}
      {hasIncompleteSlots && (
        <div
          className="border-t border-amber-900/50 bg-amber-950/20 px-4 py-2 text-xs leading-5 text-amber-200"
          role="alert"
        >
          {treeValidation.issues.map((issue) => (
            <p key={issue.message}>⚠ {issue.message}</p>
          ))}
        </div>
      )}

      {/* DSL 미리보기 */}
      <div className="border-t border-gray-800 p-3">
        <div className="mb-1 flex items-center justify-between">
          <span className="text-xs font-medium text-gray-500">
            DSL 미리보기 <span className="text-gray-600">({tab === 'entry' ? '매수 Entry' : '매도 Exit'})</span>
          </span>
          {onEditDsl && (
            <button
              className="text-xs text-blue-400 hover:text-blue-300 focus:outline-none"
              onClick={onEditDsl}
            >
              직접 편집
            </button>
          )}
        </div>
        <textarea
          readOnly
          value={currentDsl}
          rows={2}
          placeholder="조건이 없습니다"
          className={[
            'w-full resize-none break-all rounded-lg border bg-gray-900 px-3 py-2 font-mono text-xs leading-5 text-gray-300 focus:outline-none',
            hasIncompleteSlots ? 'border-amber-800/70' : 'border-gray-800',
          ].join(' ')}
          aria-label="DSL 미리보기 (읽기 전용)"
        />
        {hasIncompleteSlots && (
          <p className="mt-1 text-[11px] text-amber-300/80">
            <code className="text-amber-300">_</code> 표시는 아직 비어 있는 슬롯입니다.
          </p>
        )}
      </div>

      {/* 액션 버튼 행 */}
      <div className="flex flex-wrap items-center gap-2 border-t border-gray-800 px-4 py-3">
        {/* AI 전략 생성 — AI 기능 일관 violet 계열 */}
        {onAiGenerate && (
          <button
            type="button"
            className="rounded-lg bg-violet-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-violet-700 focus:outline-none focus:ring-2 focus:ring-violet-400"
            onClick={onAiGenerate}
          >
            AI 전략 생성
          </button>
        )}

        {/* 자연어 입력 */}
        <button
          className="rounded-lg border border-gray-700 px-3 py-1.5 text-sm text-gray-300 transition-colors hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400"
          onClick={onNaturalLanguage}
        >
          자연어로 입력하기
        </button>

        {/* DSL 직접 편집 */}
        <button
          className="rounded-lg border border-gray-700 px-3 py-1.5 text-sm text-gray-300 transition-colors hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400"
          onClick={onEditDsl}
        >
          DSL 직접 편집
        </button>

        {/* YAML 내보내기 */}
        <button
          className="rounded-lg border border-gray-700 px-3 py-1.5 text-sm text-gray-300 transition-colors hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400"
          onClick={onYamlExport}
          disabled={!entryDsl}
          title={!entryDsl ? '매수 조건을 먼저 구성하세요' : undefined}
        >
          DSL 다운로드
        </button>

        {/* YAML 업로드 */}
        <label className="cursor-pointer rounded-lg border border-gray-700 px-3 py-1.5 text-sm text-gray-300 transition-colors hover:bg-gray-800 focus-within:ring-2 focus-within:ring-blue-400">
          YAML 불러오기
          <input type="file" accept=".yaml,.yml" className="sr-only" onChange={handleFileChange} />
        </label>

        <div className="ml-auto flex gap-2">
          {/* 검증 */}
          <button
            className="rounded-lg bg-gray-700 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-gray-600 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-blue-400"
            onClick={onValidate}
            disabled={!entryDsl || isValidating || entryValidation.emptySlotCount > 0}
            title={entryValidation.emptySlotCount > 0 ? '매수 조건의 빈 슬롯을 모두 채우세요' : undefined}
          >
            {isValidating ? '검증 중...' : '검증'}
          </button>

          {/* 저장 */}
          <button
            className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-blue-400"
            onClick={onSave}
            disabled={!entryDsl || isSaving || entryValidation.emptySlotCount > 0}
            title={entryValidation.emptySlotCount > 0 ? '매수 조건의 빈 슬롯을 모두 채우세요' : undefined}
          >
            {isSaving ? '저장 중...' : '저장'}
          </button>

          {/* 평가 */}
          <button
            className="rounded-lg bg-emerald-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-emerald-400"
            onClick={onEvaluate}
            disabled={!strategyId}
            title={!strategyId ? '먼저 전략을 저장하세요' : undefined}
          >
            평가
          </button>
        </div>
      </div>
    </div>
  )
}
