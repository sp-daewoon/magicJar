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
 *   3. "저장" → StrategySaveModal → POST /api/strategies → strategyId 획득
 *   4. "평가" → POST /api/strategy/{id}/evaluate → EvaluationResultCard 표시
 *   5. "관심종목 추가" → POST /api/watchlist
 *   6. "이 전략으로 종목 찾기" → ScreenerResultModal → T-SC Phase 3 (ADR 0071 §8 H-1)
 *
 * ADR 0040 §1 라우팅 /strategy/builder 진입점
 * DoD #26 반응형 — 768px 이하 안내 메시지 (mobile 후속 PR)
 * 룰 5: LLM provider import 0건
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { usePaletteResize } from '../../hooks/usePaletteResize'
import { useModalBehavior } from '../../hooks/useModalBehavior'
import { useMediaQuery } from '../../hooks/useMediaQuery'
import { BlockPalette } from '../../components/strategy/BlockPalette'
import { StrategyBuilderCanvas } from '../../components/strategy/StrategyBuilderCanvas'
import { ComplexityCard } from '../../components/strategy/ComplexityCard'
import { EvaluationResultCard, EvaluationEmptyCard } from '../../components/strategy/EvaluationResultCard'
import { StrategySaveModal } from '../../components/strategy/StrategySaveModal'
import { NaturalLanguageInput } from '../../components/strategy/NaturalLanguageInput'
import { StrategyAiGenerateModal } from '../../components/strategy/StrategyAiGenerateModal'
import { DslTextEditor } from '../../components/strategy/DslTextEditor'
import type { StrategyBuilderAiGenerateItem } from '../../api/strategyBuilderAiGenerate'
import { cloneNode, type BlockNode, type BlockTree } from '../../lib/dsl/blockTree'
import { serializeBlockTree } from '../../lib/dsl/serializer'
import { kisYamlExport, kisYamlImport } from '../../lib/dsl/yaml'
import { useStrategyValidate, useStrategyEvaluate } from '../../api/hooks/useStrategy'
import { useSigStrategy } from '../../api/hooks/useSigStrategy'
import { useStrategyStart, useToggleFavorite } from '../../api/hooks/useStrategyStart'
import type { StrategyComplexityDto, EvaluateResponse, StrategyStartItem } from '../../api/strategy'
import { symbolApi, type CandleInterval } from '../../api/symbol'
import { parseDslText, parseDslTextToTree } from '../../lib/dsl/parser'
import {
  consumeStrategyResearchDraft,
  type StrategyResearchBuilderDraft,
} from '../../lib/strategyResearch/builderDraft'
import {
  consumeTrendlineStrategyDraft,
  type TrendlineStrategyDraft,
} from '../../lib/trendlineStrategy/trendlineDraft'
import { useTrendlines } from '../../api/hooks/useTrendlines'
import {
  TrendlineLabelContext,
  type TrendlineLabelInfo,
} from '../../components/strategy/trendlineLabelContext'
import { ScreenerResultModal } from '../../features/screener/components/ScreenerResultModal'
import { StrategyStartScreen } from '../../components/strategy/StrategyStartScreen'
import { useOnboardingStore } from '../../stores/onboarding'
import { InstrumentSearchInput } from '../../components/InstrumentSearchInput'

// 평가 폼 상태
interface EvaluateForm {
  code: string
  interval: string
}

const EVAL_INTERVAL_OPTIONS = [
  { value: '1m', label: '1m' },
  { value: '3m', label: '3m' },
  { value: '5m', label: '5m' },
  { value: '15m', label: '15m' },
  { value: '30m', label: '30m' },
  { value: '90m', label: '90m' },
  { value: '1h', label: '1h' },
  { value: '4h', label: '4h' },
  { value: '12h', label: '12h (정규장 전체)' },
  { value: '1d', label: '1d' },
  { value: '1wk', label: '1wk' },
  { value: '1mo', label: '1mo' },
  { value: '1y', label: '1y' },
]

// localStorage 즐겨찾기는 DB(strategy_favorite)로 승격됨 (spec 2026-06-18 §5.2·§7-3).
// 마운트 시 1회 흡수 마이그레이션 후 키 제거.
const STRATEGY_FAVORITES_STORAGE_KEY = 'magicjar.strategyBuilder.customFavorites.v1'
const STRATEGY_HIDDEN_DEFAULT_FAVORITES_STORAGE_KEY = 'magicjar.strategyBuilder.hiddenDefaultFavorites.v1'
const STRATEGY_FAVORITES_MIGRATED_KEY = 'magicjar.strategyBuilder.favoritesMigrated.v1'

/**
 * localStorage 잔존 즐겨찾기 → DB 흡수 (베스트에포트, 실패 무해).
 * 식별 가능한 strategyId 항목만 흡수 — 옛 항목은 strategy로 존재하지 않을 수 있으므로
 * `strategyId`/`id`(숫자) 필드로 식별 가능한 것만 대상, 나머지는 무시(로그만).
 * @returns 흡수할 strategyId 목록(중복 제거)
 */
function collectMigratableFavoriteIds(): number[] {
  if (typeof window === 'undefined') return []
  const ids = new Set<number>()
  try {
    const raw = window.localStorage.getItem(STRATEGY_FAVORITES_STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    for (const item of parsed) {
      if (!item || typeof item !== 'object') continue
      const candidate = item as Record<string, unknown>
      // 식별 가능한 strategyId: 숫자거나 숫자 문자열
      const rawId = candidate.strategyId ?? candidate.id
      const numId = typeof rawId === 'number' ? rawId : Number(rawId)
      if (Number.isFinite(numId) && numId > 0) ids.add(numId)
      else console.info('[strategyBuilder] localStorage 즐겨찾기 흡수 불가(식별 불가) — 무시', candidate.id)
    }
  } catch {
    return []
  }
  return Array.from(ids)
}

function clearLocalFavoriteStorage() {
  if (typeof window === 'undefined') return
  window.localStorage.removeItem(STRATEGY_FAVORITES_STORAGE_KEY)
  window.localStorage.removeItem(STRATEGY_HIDDEN_DEFAULT_FAVORITES_STORAGE_KEY)
  window.localStorage.setItem(STRATEGY_FAVORITES_MIGRATED_KEY, '1')
}

function insertBlockIntoFirstEmptySlot(root: BlockNode, block: BlockNode): BlockNode | null {
  const freshBlock = () => cloneNode(block)

  switch (root.kind) {
    case 'comparison': {
      if (!root.left) return { ...root, left: freshBlock() }
      const updatedLeft = insertBlockIntoFirstEmptySlot(root.left, block)
      if (updatedLeft) return { ...root, left: updatedLeft }
      if (!root.right) return { ...root, right: freshBlock() }
      const updatedRight = insertBlockIntoFirstEmptySlot(root.right, block)
      return updatedRight ? { ...root, right: updatedRight } : null
    }
    case 'logical': {
      if (!root.left) return { ...root, left: freshBlock() }
      const updatedLeft = insertBlockIntoFirstEmptySlot(root.left, block)
      if (updatedLeft) return { ...root, left: updatedLeft }
      if (!root.right) return { ...root, right: freshBlock() }
      const updatedRight = insertBlockIntoFirstEmptySlot(root.right, block)
      return updatedRight ? { ...root, right: updatedRight } : null
    }
    case 'arithmetic': {
      if (!root.left) return { ...root, left: freshBlock() }
      const updatedLeft = insertBlockIntoFirstEmptySlot(root.left, block)
      if (updatedLeft) return { ...root, left: updatedLeft }
      if (!root.right) return { ...root, right: freshBlock() }
      const updatedRight = insertBlockIntoFirstEmptySlot(root.right, block)
      return updatedRight ? { ...root, right: updatedRight } : null
    }
    case 'not': {
      if (!root.operand) return { ...root, operand: freshBlock() }
      const updatedOperand = insertBlockIntoFirstEmptySlot(root.operand, block)
      return updatedOperand ? { ...root, operand: updatedOperand } : null
    }
    default:
      return null
  }
}

export function StrategyBuilderPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const routeStrategyId = searchParams.get('strategyId') ?? undefined
  const loadedStrategyIdRef = useRef<string | null>(null)
  const [tree, setTree] = useState<BlockTree>({})
  const [strategyId, setStrategyId] = useState<string | undefined>()
  const [complexity, setComplexity] = useState<StrategyComplexityDto | null>(null)
  const [evalResult, setEvalResult] = useState<EvaluateResponse | null>(null)
  const [evalForm, setEvalForm] = useState<EvaluateForm>({ code: '005930', interval: '1d' })
  const [evalPrepMessage, setEvalPrepMessage] = useState<string | null>(null)
  const [isPreparingEvaluation, setIsPreparingEvaluation] = useState(false)
  const [toast, setToast] = useState<string | null>(null)
  const [toastType, setToastType] = useState<'success' | 'error'>('success')
  // 시작 화면 → 빌더 진입 여부 (빈 트리 + 미로드 시 시작 화면, "빈 캔버스로"·카드 선택 시 빌더)
  const [started, setStarted] = useState(false)
  const [researchDraftMeta, setResearchDraftMeta] = useState<StrategyResearchBuilderDraft | null>(null)
  const importedResearchDraftIdRef = useRef<string | null>(null)
  // 추세매매 Phase 1 — 차트 추세선에서 넘어온 프리셋 메타
  const [trendlineDraftMeta, setTrendlineDraftMeta] = useState<TrendlineStrategyDraft | null>(null)
  const importedTrendlineDraftIdRef = useRef<string | null>(null)
  const favoritesMigratedRef = useRef(false)

  // 모달 상태
  const [showSaveModal, setShowSaveModal] = useState(false)
  const [showNaturalLang, setShowNaturalLang] = useState(false)
  const [showAiGenerate, setShowAiGenerate] = useState(false)
  // AI 생성 후보를 빌더로 불러올 때 name·riskPlan을 저장 모달 초기값으로 전달 (researchDraftMeta 패턴)
  const [aiDraftMeta, setAiDraftMeta] = useState<StrategyBuilderAiGenerateItem | null>(null)
  // 시작 화면 카드(템플릿·내 전략)에서 불러온 항목 — 저장 모달 기본값(name·tf·리스크플랜) 전달 (ADR 0112 자동 저장 0)
  const [startItemMeta, setStartItemMeta] = useState<StrategyStartItem | null>(null)
  const [showDslEditor, setShowDslEditor] = useState(false)
  const [dslEditorTab, setDslEditorTab] = useState<'entry' | 'exit'>('entry')
  const [validateError, setValidateError] = useState<string | null>(null)
  // T-SC Phase 3 — 종목 찾기 모달 (ADR 0071 §8 H-1)
  const [showScreener, setShowScreener] = useState(false)
  // Canvas 탭 state — BlockPalette 클릭 흐름 정합 (hotfix 2026-05-14)
  const [canvasTab, setCanvasTab] = useState<'entry' | 'exit'>('entry')
  // 모바일(lg 미만) 화면 전환 — 팔레트/조건/결과 (빌더 고도화 D)
  const [mobilePane, setMobilePane] = useState<'palette' | 'canvas' | 'result'>('canvas')
  // lg(1024px) 이상이면 데스크톱 3분할, 미만이면 모바일 탭 레이아웃.
  // matchMedia 부재(jsdom·SSR)에서는 데스크톱 default (기존 테스트 호환).
  const isDesktop = useMediaQuery('(min-width: 1024px)', true)

  // DSL 직접 편집 모달 — ESC 닫기 + body scroll lock (공용 훅)
  // SaveModal·NaturalLanguageInput·ScreenerResultModal 은 각 컴포넌트 내부에서 훅 적용.
  useModalBehavior({ open: showDslEditor, onClose: () => setShowDslEditor(false) })

  // userId: onboarding store (feedback_no_mock_data 정합)
  const registeredUserId = useOnboardingStore((s) => s.registeredUserId)
  const { data: loadedStrategy, isError: isLoadStrategyError, error: loadStrategyError } = useSigStrategy(routeStrategyId)

  const { mutate: validateMutate, isPending: isValidating } = useStrategyValidate()
  const { mutateAsync: evaluateAsync, isPending: isEvaluating } = useStrategyEvaluate()
  const { width: paletteWidth, handleMouseDown: handlePaletteResizeDown, isDragging: isPaletteResizing } = usePaletteResize()

  // 시작 화면 데이터(DB) — 즐겨찾기는 templates+myStrategies 중 favorite=true 합집합 (localStorage 폐기)
  const { data: startData } = useStrategyStart(registeredUserId)
  const { mutate: toggleFavoriteMutate, isPending: isTogglingFavorite } = useToggleFavorite(registeredUserId)
  const favoritePresets = useMemo<StrategyStartItem[]>(() => {
    if (!startData) return []
    const seen = new Set<number>()
    return [...startData.myStrategies, ...startData.templates].filter((item) => {
      if (!item.favorite || seen.has(item.id)) return false
      seen.add(item.id)
      return true
    })
  }, [startData])

  const showToast = useCallback((msg: string, type: 'success' | 'error' = 'success') => {
    setToast(msg)
    setToastType(type)
    setTimeout(() => setToast(null), 3000)
  }, [])

  useEffect(() => {
    if (!loadedStrategy || loadedStrategyIdRef.current === loadedStrategy.id) return
    if (!loadedStrategy.buyCondition) {
      showToast('불러온 전략에 매수 조건이 없습니다', 'error')
      return
    }
    // 복제 모드(목록 카드 "복제" → ?duplicate=1): DSL·설정만 불러오고 strategyId는 비워
    // 저장 시 기존 전략 수정이 아니라 신규 생성 흐름으로 진입한다.
    const isDuplicate = searchParams.get('duplicate') === '1'
    try {
      setTree(parseDslTextToTree(loadedStrategy.buyCondition, loadedStrategy.sellCondition))
      setStrategyId(isDuplicate ? undefined : loadedStrategy.id)
      setAiDraftMeta(null)
      setStartItemMeta(null)
      setTrendlineDraftMeta(null)
      setComplexity(loadedStrategy.complexity ?? null)
      setEvalResult(null)
      setValidateError(null)
      setEvalForm((prev) => ({
        code: loadedStrategy.codes[0] ?? prev.code,
        interval: loadedStrategy.intervals[0] ?? loadedStrategy.tf ?? prev.interval,
      }))
      loadedStrategyIdRef.current = loadedStrategy.id
      showToast(
        isDuplicate
          ? `${loadedStrategy.nameKo || loadedStrategy.name} 복제 — 이름을 바꿔 새 전략으로 저장하세요`
          : `${loadedStrategy.nameKo || loadedStrategy.name} 불러옴`,
      )
    } catch (err) {
      showToast(`전략 DSL 불러오기 실패: ${err instanceof Error ? err.message : '알 수 없는 오류'}`, 'error')
    }
  }, [loadedStrategy, showToast, searchParams])

  useEffect(() => {
    const draftId = searchParams.get('researchDraftId')
    if (!draftId || importedResearchDraftIdRef.current === draftId) return
    importedResearchDraftIdRef.current = draftId

    const nextParams = new URLSearchParams(searchParams)
    nextParams.delete('researchDraftId')
    setSearchParams(nextParams, { replace: true })

    const draft = consumeStrategyResearchDraft(draftId)
    if (!draft) {
      showToast('전략 리서치 draft를 찾을 수 없습니다', 'error')
      return
    }
    if ((tree.entry || tree.exit) && !window.confirm('현재 작성 중인 조건을 리서치 후보로 교체할까요?')) {
      return
    }
    try {
      setTree(parseDslTextToTree(draft.entryDsl, draft.exitDsl))
      setResearchDraftMeta(draft)
      setAiDraftMeta(null)
      setStartItemMeta(null)
      setTrendlineDraftMeta(null)
      setStrategyId(undefined)
      setComplexity(null)
      setEvalResult(null)
      setValidateError(null)
      setCanvasTab('entry')
      setEvalForm({ code: draft.code, interval: draft.interval })
      loadedStrategyIdRef.current = null
      showToast(`${draft.candidateTitle} draft 불러옴`)
    } catch (err) {
      showToast(`리서치 draft 변환 실패: ${err instanceof Error ? err.message : '알 수 없는 오류'}`, 'error')
    }
  }, [searchParams, setSearchParams, showToast, tree.entry, tree.exit])

  // 추세매매 Phase 1 — ?trendlineDraftId= 딥링크 소비 → 시작 화면 건너뛰고 프리셋 로드
  useEffect(() => {
    const draftId = searchParams.get('trendlineDraftId')
    if (!draftId || importedTrendlineDraftIdRef.current === draftId) return
    importedTrendlineDraftIdRef.current = draftId

    const nextParams = new URLSearchParams(searchParams)
    nextParams.delete('trendlineDraftId')
    setSearchParams(nextParams, { replace: true })

    const draft = consumeTrendlineStrategyDraft(draftId)
    if (!draft) {
      showToast('추세선 프리셋을 찾을 수 없습니다 — 차트에서 다시 시도해 주세요', 'error')
      return
    }
    if ((tree.entry || tree.exit) && !window.confirm('현재 작성 중인 조건을 추세선 프리셋으로 교체할까요?')) {
      return
    }
    try {
      setTree(parseDslTextToTree(draft.entryDsl, draft.exitDsl))
      setTrendlineDraftMeta(draft)
      setResearchDraftMeta(null)
      setAiDraftMeta(null)
      setStartItemMeta(null)
      setStrategyId(undefined)
      setComplexity(null)
      setEvalResult(null)
      setValidateError(null)
      setCanvasTab('entry')
      setEvalForm({ code: draft.code, interval: draft.interval })
      loadedStrategyIdRef.current = null
      setStarted(true)
      showToast(`${draft.nameKo} 프리셋 불러옴`)
    } catch (err) {
      showToast(`추세선 프리셋 변환 실패: ${err instanceof Error ? err.message : '알 수 없는 오류'}`, 'error')
    }
  }, [searchParams, setSearchParams, showToast, tree.entry, tree.exit])

  useEffect(() => {
    if (isLoadStrategyError && loadStrategyError) {
      showToast(`전략 조회 실패: ${loadStrategyError.message}`, 'error')
    }
  }, [isLoadStrategyError, loadStrategyError, showToast])

  // localStorage 즐겨찾기 1회 흡수 마이그레이션 (spec §7-3) — 식별 가능한 strategyId만, 베스트에포트.
  useEffect(() => {
    if (favoritesMigratedRef.current) return
    if (registeredUserId === null || registeredUserId === undefined) return
    if (typeof window === 'undefined') return
    if (window.localStorage.getItem(STRATEGY_FAVORITES_MIGRATED_KEY)) {
      favoritesMigratedRef.current = true
      return
    }
    favoritesMigratedRef.current = true
    const ids = collectMigratableFavoriteIds()
    for (const id of ids) {
      toggleFavoriteMutate({ id, on: true }) // 멱등 — 실패해도 무해
    }
    clearLocalFavoriteStorage()
    if (ids.length > 0) {
      console.info(`[strategyBuilder] localStorage 즐겨찾기 ${ids.length}건 DB 흡수 완료`)
    }
  }, [registeredUserId, toggleFavoriteMutate])

  // 팔레트에서 블록 선택 → 캔버스 현재 탭 루트에 추가
  // hotfix 2026-05-14: canvasTab 인식 — 매수/매도 탭에 따라 분기 (이전 결함: 항상 entry 에만 추가)
  const handlePaletteSelect = (block: BlockNode) => {
    const applyBlock = (current?: BlockNode): BlockNode | undefined => {
      if (!current) return cloneNode(block)
      const inserted = insertBlockIntoFirstEmptySlot(current, block)
      if (inserted) return inserted
      const ok = window.confirm('현재 조건에 빈 슬롯이 없습니다. 선택한 블록으로 현재 조건을 교체할까요?')
      return ok ? cloneNode(block) : current
    }

    setTree((prev) => (
      canvasTab === 'entry'
        ? { ...prev, entry: applyBlock(prev.entry) }
        : { ...prev, exit: applyBlock(prev.exit) }
    ))
  }

  /**
   * 시작 화면 카드(템플릿·내 전략·즐겨찾기) → 빌더 로드 (spec §6.3).
   * DSL 파싱 실패 시 throw하지 않고 토스트 경고만 — 한 템플릿이 깨져도 나머지 사용 가능 (spec §9, AI dslValid=false 패턴).
   * 리스크플랜(tp/sl)은 자동 저장 안 함, 저장 모달 기본값으로만 전달 (ADR 0112).
   */
  const loadFromStartItem = (item: StrategyStartItem) => {
    if (tree.entry || tree.exit) {
      const ok = window.confirm('현재 조건을 선택한 전략으로 교체할까요?')
      if (!ok) return
    }
    const label = item.nameKo || item.name
    try {
      const parsed = parseDslTextToTree(item.dslEntry, item.dslExit ?? undefined)
      if (!parsed.entry) {
        showToast(`${label}: 매수 조건을 해석하지 못했습니다`, 'error')
        return
      }
      setTree(parsed)
      setCanvasTab('entry')
      setStrategyId(undefined)
      setAiDraftMeta(null)
      setTrendlineDraftMeta(null)
      setComplexity(null)
      setEvalResult(null)
      setValidateError(null)
      setStartItemMeta(item)
      loadedStrategyIdRef.current = null
      if (item.tf) setEvalForm((prev) => ({ ...prev, interval: item.tf }))
      setStarted(true)
      showToast(`${label} 불러옴`)
    } catch (err) {
      // 파서 버전 불일치 등 — 카드 경고(토스트)로 처리, 빌더 진입은 막지 않음
      showToast(`${label} 조건 해석 실패: ${err instanceof Error ? err.message : '알 수 없는 오류'}`, 'error')
    }
  }

  const handleFavoriteApply = (item: StrategyStartItem) => {
    loadFromStartItem(item)
  }

  const handleFavoriteRemove = (id: number) => {
    toggleFavoriteMutate(
      { id, on: false },
      {
        onSuccess: () => showToast('즐겨찾기에서 삭제했습니다'),
        onError: (err) => showToast(`즐겨찾기 삭제 실패: ${err.message}`, 'error'),
      },
    )
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
    setSearchParams({ strategyId: id })
    showToast('전략이 저장되었습니다')
  }

  // 평가
  const ensureEvaluationCandles = async (code: string, interval: string) => {
    const candleInterval = interval as CandleInterval
    setEvalPrepMessage('캔들 데이터 확인 중...')
    const current = await symbolApi.getCandles(code, candleInterval, undefined, 1)
    if (current.candles.length > 0) return

    setEvalPrepMessage('캔들 데이터가 없어 KIS에서 갱신 중...')
    await symbolApi.refreshCandles(code, [interval])

    setEvalPrepMessage('갱신된 캔들 확인 중...')
    const refreshed = await symbolApi.getCandles(code, candleInterval, undefined, 1)
    if (refreshed.candles.length === 0) {
      throw new Error('캔들 갱신 후에도 평가 가능한 차트 데이터가 없습니다. 잠시 후 다시 실행하거나 종목 상세에서 재수집을 실행해 주세요.')
    }
  }

  const handleEvaluate = async () => {
    if (!strategyId) return
    const code = evalForm.code.trim()
    if (!/^\d{6}$/.test(code)) {
      showToast('평가 종목은 6자리 종목코드로 선택해 주세요', 'error')
      return
    }
    setIsPreparingEvaluation(true)
    setEvalResult(null)
    try {
      await ensureEvaluationCandles(code, evalForm.interval)
      setEvalPrepMessage('전략 평가 중...')
      const result = await evaluateAsync({ strategyId, req: { code, interval: evalForm.interval } })
      setEvalResult(result)
      showToast(`평가 완료 — 신호: ${result.signal}`)
    } catch (err) {
      const msg = err instanceof Error ? err.message : '알 수 없는 오류'
      setEvalResult(null)
      showToast(`평가 실패: ${msg}`, 'error')
    } finally {
      setEvalPrepMessage(null)
      setIsPreparingEvaluation(false)
    }
  }

  // AI 생성 후보 → 빌더 폼에 주입 (자동 저장 0 — ADR 0112 매매 결정권 0)
  // NaturalLanguageInput이 DSL을 트리로 넣는 기존 경로(parseDslTextToTree) 재사용.
  const handleAiDraftLoad = (item: StrategyBuilderAiGenerateItem) => {
    if (!item.dslValid) return
    if ((tree.entry || tree.exit) && !window.confirm('현재 작성 중인 조건을 AI 생성 후보로 교체할까요?')) {
      return
    }
    try {
      setTree(parseDslTextToTree(item.entryDsl, item.exitDsl ?? undefined))
      setAiDraftMeta(item)
      setResearchDraftMeta(null)
      setStartItemMeta(null)
      setTrendlineDraftMeta(null)
      setStrategyId(undefined)
      setComplexity(null)
      setEvalResult(null)
      setValidateError(null)
      setCanvasTab('entry')
      loadedStrategyIdRef.current = null
      setShowAiGenerate(false)
      showToast(`${item.name} 후보를 빌더로 불러왔습니다`)
    } catch (err) {
      showToast(`AI 후보 변환 실패: ${err instanceof Error ? err.message : '알 수 없는 오류'}`, 'error')
    }
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
    setDslEditorTab(canvasTab)
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

  // 추세매매 Phase 2 (ADR 0140) — 현재 평가 종목의 저장된 추세선 → trendline(id) 노드 라벨 맵.
  //   유효 6자리 코드일 때만 조회(빈 코드면 useTrendlines 비활성). 빌더 트리의 TrendlineNodeView 가
  //   컨텍스트에서 "추세선 #id (종목 · 라벨)" 을 붙인다. 맵에 없으면 id 만 표시(graceful).
  const trendlineLookupCode = /^\d{6}$/.test(evalForm.code) ? evalForm.code : ''
  const { data: symbolTrendlines = [] } = useTrendlines(trendlineLookupCode)
  const trendlineLabels = useMemo<Map<number, TrendlineLabelInfo>>(() => {
    const map = new Map<number, TrendlineLabelInfo>()
    for (const line of symbolTrendlines) {
      const label = line.label?.trim() || (line.type === 'SUPPORT' ? '지지' : '저항')
      map.set(line.id, { label, code: line.code })
    }
    return map
  }, [symbolTrendlines])

  // 시작 화면 분기 (spec 2026-06-18 §4.1) — 빈 트리 + 미로드 + URL deeplink 없음 + 미시작일 때만.
  const hasDeepLink =
    !!routeStrategyId || !!searchParams.get('researchDraftId') || !!searchParams.get('trendlineDraftId')
  const isFresh =
    !started &&
    !hasDeepLink &&
    !tree.entry &&
    !tree.exit &&
    !strategyId &&
    !researchDraftMeta &&
    !aiDraftMeta &&
    !startItemMeta &&
    !trendlineDraftMeta
  const showStartScreen = isFresh

  if (showStartScreen) {
    return (
      <div className="flex flex-col overflow-hidden bg-gray-950" style={{ height: 'calc(100vh - 60px)' }}>
        <StrategyStartScreen
          userId={registeredUserId}
          onPick={loadFromStartItem}
          onBlank={() => setStarted(true)}
          onDescribe={() => {
            setStarted(true)
            setShowAiGenerate(true)
          }}
        />
        {/* 시작 화면에서 "말로 설명" 시 AI 생성 모달 */}
        {showAiGenerate && (
          <StrategyAiGenerateModal
            userId={registeredUserId}
            defaultInterval={evalForm.interval}
            onLoadDraft={handleAiDraftLoad}
            onClose={() => setShowAiGenerate(false)}
          />
        )}
        {/* 토스트 알림 (시작 화면에서도 노출) */}
        {toast && (
          <div
            className={[
              'fixed bottom-4 right-4 z-50 rounded-xl px-4 py-3 text-sm font-medium shadow-lg transition-all',
              toastType === 'success' ? 'bg-emerald-900 text-emerald-200' : 'bg-red-900 text-red-200',
            ].join(' ')}
            role="status"
          >
            {toast}
          </div>
        )}
      </div>
    )
  }

  // 결과 패널 내용 — 데스크톱 aside · 모바일 결과 탭에서 공유 (빌더 고도화 D, 중복 방지)
  const resultsPanelContent = (
    <>
      <h2 className="text-sm font-semibold text-gray-300">전략 실행</h2>

      {trendlineDraftMeta && (
        <div className="rounded-lg border border-sky-800/60 bg-sky-950/30 p-3">
          <div className="text-xs font-semibold text-sky-200">차트 추세선에서 생성된 프리셋</div>
          <p className="mt-1 text-xs leading-relaxed text-sky-100/90">
            자동매매 결정을 대신하지 않습니다 — 백테스트로 검증한 뒤 직접 저장·활성화하세요.
          </p>
          <div className="mt-2 text-xs text-gray-300">{trendlineDraftMeta.nameKo}</div>
          <div className="mt-1 text-[11px] text-gray-500">
            {trendlineDraftMeta.code} · {trendlineDraftMeta.interval} ·{' '}
            {trendlineDraftMeta.lineType === 'RESISTANCE' ? '저항(돌파)' : '지지(반등)'} ·{' '}
            {trendlineDraftMeta.sloped
              ? `기울선 참조 trendline(${trendlineDraftMeta.trendlineId})`
              : `수평선 @${trendlineDraftMeta.linePrice.toLocaleString('ko-KR')}`}
          </div>
          {trendlineDraftMeta.sloped && (
            <p className="mt-2 rounded bg-sky-950/50 px-2 py-1 text-[11px] text-sky-200/90">
              기울선은 시점마다 기준 가격이 바뀝니다 — 저장한 선을 참조(trendline({trendlineDraftMeta.trendlineId}))하므로,
              선을 다시 그으면 다음 평가부터 자동 반영됩니다. 선을 삭제하면 이 전략은 매매 기준이 사라져 삭제가 차단됩니다.
            </p>
          )}
          <p className="mt-2 rounded bg-amber-950/40 px-2 py-1 text-[11px] text-amber-300">
            휩쏘 주의 — 선 바로 아래 이탈 청산은 반복 매매 위험이 있습니다. 손절 {trendlineDraftMeta.stopLossPct}%는
            선 가격 기준 제안값이니 저장 전 조정하고, minHold·버퍼는 저장 후 전략 설정에서 보강하세요.
          </p>
          <Link
            to="/strategy/backtest"
            className="mt-2 block w-full rounded-md border border-sky-700 py-1.5 text-center text-xs text-sky-200 hover:bg-sky-900/40"
          >
            백테스트로 검증하기 →
          </Link>
        </div>
      )}

      {researchDraftMeta && !strategyId && (
        <div className="rounded-lg border border-blue-900/60 bg-blue-950/20 p-3">
          <div className="text-xs font-semibold text-blue-200">리서치 draft</div>
          <div className="mt-1 text-sm text-gray-200">{researchDraftMeta.candidateTitle}</div>
          <div className="mt-1 text-xs text-gray-500">
            {researchDraftMeta.code} · {researchDraftMeta.interval} · 익절 {researchDraftMeta.takeProfitPct}% · 손절 {researchDraftMeta.stopLossPct}%
          </div>
          {(researchDraftMeta.universeCodes?.length ?? 0) > 1 && (
            <div className="mt-1.5 inline-flex items-center gap-1 rounded bg-emerald-950/40 px-2 py-0.5 text-[11px] text-emerald-300">
              universe {researchDraftMeta.universeCodes!.length}종 프리필됨 · 평가 기준 {researchDraftMeta.code}
            </div>
          )}
          {researchDraftMeta.conversionNotes.length > 0 && (
            <div className="mt-2 space-y-1 text-xs text-amber-300">
              {researchDraftMeta.conversionNotes.map((note) => (
                <p key={note}>{note}</p>
              ))}
            </div>
          )}
        </div>
      )}

      {aiDraftMeta && !strategyId && (
        <div className="rounded-lg border border-violet-900/60 bg-violet-950/20 p-3">
          <div className="text-xs font-semibold text-violet-200">AI 생성 후보</div>
          <div className="mt-1 text-sm text-gray-200">{aiDraftMeta.name}</div>
          <div className="mt-1 text-xs text-gray-500">
            {[
              aiDraftMeta.riskPlan.takeProfitPct != null ? `익절 ${aiDraftMeta.riskPlan.takeProfitPct}%` : null,
              aiDraftMeta.riskPlan.fixedStopLossPct != null ? `손절 ${aiDraftMeta.riskPlan.fixedStopLossPct}%` : null,
              aiDraftMeta.riskPlan.minHoldBars != null ? `최소보유 ${aiDraftMeta.riskPlan.minHoldBars}봉` : null,
            ].filter(Boolean).join(' · ') || '리스크 플랜 미지정'}
          </div>
          <p className="mt-2 text-xs text-amber-300">
            저장하지 않았습니다. 검토·검증·평가 후 직접 저장하세요.
          </p>
        </div>
      )}

      <div className="rounded-lg border border-gray-800 bg-gray-900 p-3">
        <div className="mb-2 text-xs text-gray-500">종목·기준봉</div>
        <div className="space-y-2">
          <InstrumentSearchInput
            value={evalForm.code}
            onRawChange={(value) => setEvalForm((f) => ({ ...f, code: value.trim() }))}
            onSelect={(result) => setEvalForm((f) => ({ ...f, code: result.code }))}
            placeholder="종목명 또는 코드 검색"
          />
          <select
            aria-label="평가 기준봉"
            value={evalForm.interval}
            onChange={(e) => setEvalForm((f) => ({ ...f, interval: e.target.value }))}
            className="w-full rounded-md border border-gray-700 bg-gray-950 px-2 py-1.5 text-sm text-white focus:outline-none focus:ring-2 focus:ring-blue-400"
          >
            {EVAL_INTERVAL_OPTIONS.map((i) => (
              <option key={i.value} value={i.value}>{i.label}</option>
            ))}
          </select>
          <button
            className="w-full rounded-md bg-emerald-600 py-1.5 text-sm font-medium text-white hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-50"
            onClick={handleEvaluate}
            disabled={!strategyId || isEvaluating || isPreparingEvaluation || !evalForm.code}
            title={!strategyId ? '먼저 전략을 저장하세요' : undefined}
          >
            {isEvaluating || isPreparingEvaluation ? '평가 중...' : '평가 실행'}
          </button>
          {evalPrepMessage && (
            <p className="text-xs text-emerald-300" role="status">
              {evalPrepMessage}
            </p>
          )}
        </div>
      </div>

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
      ) : strategyId && !isEvaluating && !isPreparingEvaluation ? (
        <EvaluationEmptyCard />
      ) : null}

      {/* T-SC Phase 3 — 이 전략으로 종목 찾기 (ADR 0071 §8 H-1) */}
      {strategyId && (
        <button
          type="button"
          onClick={() => setShowScreener(true)}
          className="w-full rounded-xl bg-indigo-700 py-2.5 text-sm font-semibold text-white hover:bg-indigo-600 active:bg-indigo-800 disabled:cursor-not-allowed disabled:opacity-50"
          aria-label="이 전략으로 종목 찾기"
        >
          이 전략으로 종목 찾기
        </button>
      )}

      {/* 저장된 전략 정보 + 다음 단계 도선 (검증 퍼널 — 빌더 → 백테스트) */}
      {strategyId && (
        <div className="rounded-lg bg-gray-900 p-3 text-xs text-gray-500">
          <p>
            전략 ID: <code className="text-gray-400">{strategyId}</code>
          </p>
          <p className="mb-2 mt-2 text-[11px] text-gray-600">
            다음 단계 — 과거 데이터로 전략을 검증하세요.
          </p>
          <Link
            to={`/strategy/backtest?strategyId=${encodeURIComponent(strategyId)}`}
            className="block w-full rounded-md bg-blue-600 py-1.5 text-center text-sm font-medium text-white hover:bg-blue-700"
          >
            이 전략 백테스트 →
          </Link>
          <Link
            to="/strategy/list"
            className="mt-1.5 block w-full rounded-md border border-gray-700 py-1.5 text-center text-xs text-gray-400 hover:bg-gray-800"
          >
            전략 목록에서 관리
          </Link>
        </div>
      )}

      {/* 빈 상태 */}
      {!complexity && !evalResult && !strategyId && (
        <div className="text-center text-xs text-gray-600">
          <p>검증·평가 결과가 여기에 표시됩니다</p>
        </div>
      )}
    </>
  )

  return (
    <TrendlineLabelContext.Provider value={trendlineLabels}>
    <div className="flex flex-col overflow-hidden bg-gray-950" style={{ height: 'calc(100vh - 60px)' }}>
      {/* 모바일 레이아웃 (lg 미만) — 팔레트·캔버스·결과 탭 전환 세로 스택 (빌더 고도화 D) */}
      {!isDesktop && (
      <div className="flex min-h-0 flex-1 flex-col lg:hidden">
        <div className="flex items-center gap-1 border-b border-gray-800 px-2 py-2">
          {(
            [
              { key: 'palette', label: '블록' },
              { key: 'canvas', label: '조건' },
              { key: 'result', label: '결과' },
            ] as const
          ).map((t) => (
            <button
              key={t.key}
              type="button"
              onClick={() => setMobilePane(t.key)}
              className={[
                'flex-1 rounded-lg px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
                mobilePane === t.key ? 'bg-blue-600 text-white' : 'text-gray-400 hover:bg-gray-800',
              ].join(' ')}
              aria-pressed={mobilePane === t.key}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="min-h-0 flex-1 overflow-hidden">
          {mobilePane === 'palette' && (
            <div className="flex h-full w-full overflow-x-auto">
              <BlockPalette
                onSelect={(block) => {
                  handlePaletteSelect(block)
                  setMobilePane('canvas')
                }}
                favorites={favoritePresets}
                onFavoriteApply={(item) => {
                  handleFavoriteApply(item)
                  setMobilePane('canvas')
                }}
                onFavoriteRemove={handleFavoriteRemove}
                favoriteRemovePending={isTogglingFavorite}
                width={300}
              />
            </div>
          )}
          {mobilePane === 'canvas' && (
            <StrategyBuilderCanvas
              tree={tree}
              onTreeChange={setTree}
              tab={canvasTab}
              onTabChange={setCanvasTab}
              onEditDsl={handleEditDsl}
              onNaturalLanguage={() => setShowNaturalLang(true)}
              onAiGenerate={() => setShowAiGenerate(true)}
              onYamlExport={handleYamlExport}
              onYamlImport={handleYamlImport}
              onValidate={handleValidate}
              onSave={() => setShowSaveModal(true)}
              onEvaluate={handleEvaluate}
              strategyId={strategyId}
              isValidating={isValidating}
              isSaving={false}
            />
          )}
          {mobilePane === 'result' && (
            <div className="flex h-full flex-col gap-4 overflow-y-auto p-4">{resultsPanelContent}</div>
          )}
        </div>
      </div>
      )}

      {/* 데스크톱 레이아웃 */}
      {isDesktop && (
      <div className="hidden min-h-0 flex-1 lg:flex">
        {/* 좌: 팔레트 + 드래그 리사이즈 핸들 */}
        <BlockPalette
          onSelect={handlePaletteSelect}
          favorites={favoritePresets}
          onFavoriteApply={handleFavoriteApply}
          onFavoriteRemove={handleFavoriteRemove}
          favoriteRemovePending={isTogglingFavorite}
          width={paletteWidth}
        />
        {/* ResizeHandle — col-resize 핸들 */}
        <div
          role="separator"
          aria-label="팔레트 폭 조절"
          aria-orientation="vertical"
          className={[
            'relative z-10 flex w-1.5 flex-shrink-0 cursor-col-resize select-none items-center justify-center',
            'hover:bg-blue-500/40 active:bg-blue-500/60',
            isPaletteResizing ? 'bg-blue-500/60' : 'bg-transparent',
            'transition-colors duration-100',
          ].join(' ')}
          onMouseDown={handlePaletteResizeDown}
          title="드래그하여 패널 폭 조절"
        >
          {/* 핸들 점선 비주얼 */}
          <div
            className={[
              'h-12 w-0.5 rounded-full',
              isPaletteResizing ? 'bg-blue-400' : 'bg-gray-700 group-hover:bg-blue-500',
            ].join(' ')}
          />
        </div>

        {/* 중: 캔버스 */}
        <StrategyBuilderCanvas
          tree={tree}
          onTreeChange={setTree}
          tab={canvasTab}
          onTabChange={setCanvasTab}
          onEditDsl={handleEditDsl}
          onNaturalLanguage={() => setShowNaturalLang(true)}
          onAiGenerate={() => setShowAiGenerate(true)}
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
          {resultsPanelContent}
        </aside>
      </div>
      )}

      {/* 저장 모달 */}
      {showSaveModal && (
        <StrategySaveModal
          strategyId={strategyId}
          userId={registeredUserId}
          entry={entryDsl}
          exit={exitDsl}
          initialName={loadedStrategy?.name ?? researchDraftMeta?.name ?? trendlineDraftMeta?.name ?? aiDraftMeta?.name ?? startItemMeta?.name}
          initialNameKo={loadedStrategy?.nameKo ?? researchDraftMeta?.nameKo ?? trendlineDraftMeta?.nameKo ?? aiDraftMeta?.name ?? startItemMeta?.nameKo ?? undefined}
          initialUniverse={loadedStrategy?.universe}
          initialTf={evalForm.interval}
          initialCode={evalForm.code}
          initialCodes={loadedStrategy?.codes ?? researchDraftMeta?.universeCodes ?? (trendlineDraftMeta ? [trendlineDraftMeta.code] : undefined)}
          initialCodeNames={loadedStrategy?.codeNames}
          initialStopLossPct={
            loadedStrategy?.stopLossPct ?? researchDraftMeta?.stopLossPct ?? trendlineDraftMeta?.stopLossPct ?? aiDraftMeta?.riskPlan.fixedStopLossPct ?? startItemMeta?.fixedStopLossPct ?? undefined
          }
          initialTakeProfitPct={
            loadedStrategy?.takeProfitPct ?? researchDraftMeta?.takeProfitPct ?? aiDraftMeta?.riskPlan.takeProfitPct ?? startItemMeta?.takeProfitPct ?? undefined
          }
          initialMinHoldBars={loadedStrategy?.minHoldBars ?? aiDraftMeta?.riskPlan.minHoldBars ?? null}
          initialViActionPolicy={loadedStrategy?.viActionPolicy ?? null}
          initialScaleIn={loadedStrategy?.scaleIn}
          initialPartialExits={loadedStrategy?.partialExits}
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

      {/* AI 전략 생성 모달 */}
      {showAiGenerate && (
        <StrategyAiGenerateModal
          userId={registeredUserId}
          defaultInterval={evalForm.interval}
          onLoadDraft={handleAiDraftLoad}
          onClose={() => setShowAiGenerate(false)}
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

      {/* T-SC Phase 3 — 종목 찾기 모달 (ADR 0071 §8) */}
      {showScreener && strategyId && (
        <ScreenerResultModal
          strategyId={parseInt(strategyId, 10)}
          userId={registeredUserId}
          onClose={() => setShowScreener(false)}
        />
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
    </TrendlineLabelContext.Provider>
  )
}
