/**
 * StrategyBuilderPage — Strategy Builder 통합 페이지
 *
 * 레이아웃 (§7 시각화 컨셉 정합):
 *   좌: BlockPalette (w-56)
 *   중: StrategyBuilderCanvas (flex-1)
 *   우: ComplexityCard + EvaluationResultCard 결과 영역 (w-72)
 *
 * 플로우:
 *   1. 팔레트에서 블록 선택 → 현재 탭(entry/exit) 트리에 추가
 *   2. "검증" → POST /api/strategy/validate → ComplexityCard 표시
 *   3. "저장" → StrategySaveModal → POST /api/strategy → strategyId 획득
 *   4. "평가" → POST /api/strategy/{id}/evaluate → EvaluationResultCard 표시
 *   5. "관심종목 추가" → POST /api/watchlist
 *
 * ADR 0040 §1 라우팅 /strategy/builder 진입점
 * DoD #26 반응형 — 768px 이하 안내 메시지 (mobile 후속 PR)
 * 룰 5: LLM provider import 0건
 */
import { useState } from 'react'
import { BlockPalette } from '../../components/strategy/BlockPalette'
import { StrategyBuilderCanvas } from '../../components/strategy/StrategyBuilderCanvas'
import { ComplexityCard } from '../../components/strategy/ComplexityCard'
import { EvaluationResultCard, EvaluationEmptyCard } from '../../components/strategy/EvaluationResultCard'
import { StrategySaveModal } from '../../components/strategy/StrategySaveModal'
import { NaturalLanguageInput } from '../../components/strategy/NaturalLanguageInput'
import { DslTextEditor } from '../../components/strategy/DslTextEditor'
import type { BlockNode, BlockTree } from '../../lib/dsl/blockTree'
import { serializeBlockTree } from '../../lib/dsl/serializer'
import { kisYamlExport, kisYamlImport } from '../../lib/dsl/yaml'
import { useStrategyValidate, useStrategyEvaluate } from '../../api/hooks/useStrategy'
import type { StrategyComplexityDto, EvaluateResponse } from '../../api/strategy'
import { parseDslText } from '../../lib/dsl/parser'

// 평가 폼 상태
interface EvaluateForm {
  code: string
  interval: string
}

export function StrategyBuilderPage() {
  const [tree, setTree] = useState<BlockTree>({})
  const [strategyId, setStrategyId] = useState<string | undefined>()
  const [complexity, setComplexity] = useState<StrategyComplexityDto | null>(null)
  const [evalResult, setEvalResult] = useState<EvaluateResponse | null>(null)
  const [evalForm, setEvalForm] = useState<EvaluateForm>({ code: '005930', interval: '1d' })
  const [toast, setToast] = useState<string | null>(null)
  const [toastType, setToastType] = useState<'success' | 'error'>('success')

  // 모달 상태
  const [showSaveModal, setShowSaveModal] = useState(false)
  const [showNaturalLang, setShowNaturalLang] = useState(false)
  const [showDslEditor, setShowDslEditor] = useState(false)
  const [dslEditorTab, setDslEditorTab] = useState<'entry' | 'exit'>('entry')
  const [validateError, setValidateError] = useState<string | null>(null)

  const { mutate: validateMutate, isPending: isValidating } = useStrategyValidate()
  const { mutate: evaluateMutate, isPending: isEvaluating } = useStrategyEvaluate()

  const showToast = (msg: string, type: 'success' | 'error' = 'success') => {
    setToast(msg)
    setToastType(type)
    setTimeout(() => setToast(null), 3000)
  }

  // 팔레트에서 블록 선택 → 캔버스 현재 탭 루트에 추가
  const handlePaletteSelect = (block: BlockNode) => {
    setTree((prev) => ({
      ...prev,
      entry: prev.entry ?? block,
    }))
  }

  // 검증
  const handleValidate = () => {
    const { entry } = serializeBlockTree(tree)
    if (!entry) return
    setValidateError(null)
    validateMutate(
      { expr: entry },
      {
        onSuccess: (result) => {
          setComplexity(result)
          showToast(`검증 완료 — 복잡도: ${result.complexityClass}`)
        },
        onError: (err) => {
          setComplexity(null)
          setValidateError(err.message)
          showToast(`검증 실패: ${err.message}`, 'error')
        },
      },
    )
  }

  // 저장 완료
  const handleSaved = (id: string) => {
    setStrategyId(id)
    showToast('전략이 저장되었습니다')
  }

  // 평가
  const handleEvaluate = () => {
    if (!strategyId) return
    evaluateMutate(
      { strategyId, req: evalForm },
      {
        onSuccess: (result) => {
          setEvalResult(result)
          showToast(`평가 완료 — 신호: ${result.signal}`)
        },
        onError: (err) => {
          setEvalResult(null)
          showToast(`평가 실패: ${err.message}`, 'error')
        },
      },
    )
  }

  // 자연어 변환 결과 적용
  const handleNaturalResult = (result: Partial<BlockTree>) => {
    setTree((prev) => ({
      entry: result.entry ?? prev.entry,
      exit: result.exit ?? prev.exit,
    }))
  }

  // DSL 편집 열기
  const handleEditDsl = () => {
    setDslEditorTab('entry')
    setShowDslEditor(true)
  }

  // DSL 편집 → 트리 반영
  const handleDslChange = (node: BlockNode) => {
    setTree((prev) =>
      dslEditorTab === 'entry' ? { ...prev, entry: node } : { ...prev, exit: node },
    )
    setShowDslEditor(false)
  }

  // YAML 내보내기
  const handleYamlExport = () => {
    const { entry } = serializeBlockTree(tree)
    if (!entry) return
    const yaml = kisYamlExport(tree, {
      name: 'my_strategy',
      nameKo: '나의 전략',
      universe: 'KOSPI',
      tf: '1d',
    })
    const blob = new Blob([yaml], { type: 'text/yaml' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'strategy.kis.yaml'
    a.click()
    URL.revokeObjectURL(url)
  }

  // YAML 불러오기
  const handleYamlImport = (file: File) => {
    if (file.size > 1024 * 1024) {
      showToast('파일이 너무 큽니다 (최대 1MB)', 'error')
      return
    }
    const reader = new FileReader()
    reader.onload = (e) => {
      try {
        const text = e.target?.result as string
        const imported = kisYamlImport(text)
        const entryNode = imported.entry ? parseDslText(imported.entry) : undefined
        const exitNode = imported.exit ? parseDslText(imported.exit) : undefined
        setTree({ entry: entryNode, exit: exitNode })
        showToast('YAML 불러오기 완료')
      } catch (err) {
        showToast(
          `YAML 파싱 실패: ${err instanceof Error ? err.message : '알 수 없는 오류'}`,
          'error',
        )
      }
    }
    reader.readAsText(file)
  }

  const { entry: entryDsl, exit: exitDsl } = serializeBlockTree(tree)

  return (
    <div className="flex flex-col overflow-hidden bg-gray-950" style={{ height: 'calc(100vh - 60px)' }}>
      {/* 모바일 안내 (DoD #26 — 768px 이하) */}
      <div className="flex flex-col items-center justify-center p-6 text-center lg:hidden">
        <p className="text-sm text-gray-400">
          전략 빌더는 현재 데스크톱(1280px 이상)에서 권장합니다.
        </p>
        <p className="mt-1 text-xs text-gray-600">모바일 최적화는 후속 업데이트에서 제공됩니다.</p>
      </div>

      {/* 데스크톱 레이아웃 */}
      <div className="hidden min-h-0 flex-1 lg:flex">
        {/* 좌: 팔레트 */}
        <BlockPalette onSelect={handlePaletteSelect} />

        {/* 중: 캔버스 */}
        <StrategyBuilderCanvas
          tree={tree}
          onTreeChange={setTree}
          onEditDsl={handleEditDsl}
          onNaturalLanguage={() => setShowNaturalLang(true)}
          onYamlExport={handleYamlExport}
          onYamlImport={handleYamlImport}
          onValidate={handleValidate}
          onSave={() => setShowSaveModal(true)}
          onEvaluate={handleEvaluate}
          strategyId={strategyId}
          isValidating={isValidating}
          isSaving={false}
        />

        {/* 우: 결과 영역 */}
        <aside className="flex w-72 flex-shrink-0 flex-col gap-4 overflow-y-auto border-l border-gray-800 p-4">
          <h2 className="text-sm font-semibold text-gray-300">결과</h2>

          {/* 평가 입력 */}
          {strategyId && (
            <div className="rounded-xl border border-gray-800 bg-gray-900 p-3">
              <div className="mb-2 text-xs text-gray-500">평가 설정</div>
              <div className="space-y-2">
                <input
                  type="text"
                  value={evalForm.code}
                  onChange={(e) => setEvalForm((f) => ({ ...f, code: e.target.value }))}
                  placeholder="종목코드 (예: 005930)"
                  className="w-full rounded border border-gray-700 bg-gray-950 px-2 py-1 text-sm text-white placeholder-gray-600 focus:outline-none"
                />
                <select
                  value={evalForm.interval}
                  onChange={(e) => setEvalForm((f) => ({ ...f, interval: e.target.value }))}
                  className="w-full rounded border border-gray-700 bg-gray-950 px-2 py-1 text-sm text-white focus:outline-none"
                >
                  {['1m', '5m', '30m', '1h', '1d', '1wk'].map((i) => (
                    <option key={i} value={i}>{i}</option>
                  ))}
                </select>
                <button
                  className="w-full rounded bg-emerald-600 py-1.5 text-sm font-medium text-white hover:bg-emerald-700 disabled:opacity-50"
                  onClick={handleEvaluate}
                  disabled={isEvaluating || !evalForm.code}
                >
                  {isEvaluating ? '평가 중...' : '평가 실행'}
                </button>
              </div>
            </div>
          )}

          {/* 복잡도 카드 */}
          {complexity && <ComplexityCard complexity={complexity} />}

          {/* 검증 에러 */}
          {validateError && !complexity && (
            <div className="rounded-xl border border-red-900/50 bg-red-900/20 p-3 text-sm text-red-400">
              {validateError}
            </div>
          )}

          {/* 평가 결과 카드 */}
          {evalResult ? (
            <EvaluationResultCard
              result={evalResult}
              code={evalForm.code}
            />
          ) : strategyId && !isEvaluating ? (
            <EvaluationEmptyCard />
          ) : null}

          {/* 저장된 전략 정보 */}
          {strategyId && (
            <div className="rounded-lg bg-gray-900 p-3 text-xs text-gray-500">
              전략 ID: <code className="text-gray-400">{strategyId}</code>
            </div>
          )}

          {/* 빈 상태 */}
          {!complexity && !evalResult && !strategyId && (
            <div className="text-center text-xs text-gray-600">
              <p>검증·평가 결과가 여기에 표시됩니다</p>
            </div>
          )}
        </aside>
      </div>

      {/* 저장 모달 */}
      {showSaveModal && (
        <StrategySaveModal
          entry={entryDsl}
          exit={exitDsl}
          onSaved={handleSaved}
          onClose={() => setShowSaveModal(false)}
        />
      )}

      {/* 자연어 입력 모달 */}
      {showNaturalLang && (
        <NaturalLanguageInput
          onResult={handleNaturalResult}
          onClose={() => setShowNaturalLang(false)}
        />
      )}

      {/* DSL 직접 편집 */}
      {showDslEditor && (
        <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/60">
          <div className="w-full max-w-lg p-4">
            <DslTextEditor
              dsl={dslEditorTab === 'entry' ? entryDsl : exitDsl}
              onDslChange={handleDslChange}
              onClose={() => setShowDslEditor(false)}
              tabLabel={dslEditorTab}
            />
          </div>
        </div>
      )}

      {/* 토스트 알림 */}
      {toast && (
        <div
          className={[
            'fixed bottom-4 right-4 z-50 rounded-xl px-4 py-3 text-sm font-medium shadow-lg transition-all',
            toastType === 'success'
              ? 'bg-emerald-900 text-emerald-200'
              : 'bg-red-900 text-red-200',
          ].join(' ')}
          role="status"
        >
          {toast}
        </div>
      )}
    </div>
  )
}
