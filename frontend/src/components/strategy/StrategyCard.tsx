/**
 * StrategyCard — 전략 단건 카드 (T-SIG Phase 3 — ADR 0066)
 *
 * 표시 항목: 이름·codes·intervals·status badge·시그널 카운트
 * 액션: 자동매매 중지/시작, 삭제 (soft delete)
 * 실제 데이터만 표시 (feedback_no_mock_data 정합)
 */
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  type SigStrategyDto,
  type StrategyStatus,
} from '../../api/strategy'
import {
  useArchiveSigStrategy,
  useDeleteSigStrategy,
  useToggleListFavorite,
  useToggleSigStrategyStatus,
  useUnarchiveSigStrategy,
} from '../../api/hooks/useSigStrategy'
import { AiExplainEntry } from '../../features/ai/components/AiExplainEntry'
import { PaperBadge } from '../../features/daytradePaper/components/PaperBadge'
import { useOnboardingStore } from '../../stores/onboarding'
import { useToast } from '../ui/ToastProvider'
import { BlockedStatusPopover } from './BlockedStatusPopover'
import { RiskPlanPreviewPanel } from './RiskPlanPreviewPanel'
import { StrategyPositionPanel } from './StrategyPositionPanel'
import { StrategyPerformanceModal } from './StrategyPerformanceModal'
import {
  formatStrategyCodeLabel,
  isPaperStrategy,
  resolveStrategyMutationErrorMessage,
  strategyActivationBlockedReason,
} from './strategyDisplay'
import { parsePositiveNumber } from './riskPlanDisplay'
import { describeScaleIn } from './scaleInExitDisplay'
import { floorBadge, scaleInBadgeLabel } from './strategyCardBadges'

// ──────────────────────────────────────────────
// 상태 배지 스타일
// ──────────────────────────────────────────────

const STATUS_BADGE: Record<StrategyStatus, { label: string; cls: string }> = {
  ACTIVE:   { label: '자동매매중', cls: 'bg-emerald-900/80 text-emerald-300 border border-emerald-700' },
  BUY_BLOCKED: { label: '매수차단', cls: 'bg-sky-950/80 text-sky-300 border border-sky-700' },
  PAUSED:   { label: '중지됨',     cls: 'bg-amber-900/80 text-amber-300 border border-amber-700' },
  DISABLED: { label: '보호중지',   cls: 'bg-gray-700/80 text-gray-400 border border-gray-600' },
  ARCHIVED: { label: '보관됨',     cls: 'bg-gray-800/80 text-gray-400 border border-gray-700' },
  DELETED:  { label: '삭제됨',     cls: 'bg-red-900/80 text-red-400 border border-red-800' },
}

const DISABLED_REASON_LABEL: Record<string, string> = {
  TIMEOUT: '평가 시간 초과',
  CPU_BUDGET_EXCEEDED: 'CPU 예산 초과',
  MANUAL: '보호 중지',
  RISK_VIOLATION: '리스크 차단 누적',
}

// ──────────────────────────────────────────────
// 컴포넌트
// ──────────────────────────────────────────────

interface StrategyCardProps {
  strategy: SigStrategyDto
  /** 다중 선택 모드 — 체크박스 노출 */
  selectMode?: boolean
  /** 현재 카드 선택 여부 */
  selected?: boolean
  /** 선택 토글 콜백 */
  onToggleSelect?: () => void
  /** 즐겨찾기 여부 (시작 화면 favoriteIds 권위) */
  isFavorite?: boolean
}

export function StrategyCard({
  strategy,
  selectMode = false,
  selected = false,
  onToggleSelect,
  isFavorite = false,
}: StrategyCardProps) {
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [showAllCodes, setShowAllCodes] = useState(false)
  const [showPerformance, setShowPerformance] = useState(false)
  const [showArchiveConfirm, setShowArchiveConfirm] = useState(false)
  const badge = STATUS_BADGE[strategy.status] ?? STATUS_BADGE.DISABLED
  const codes = Array.isArray(strategy.codes) ? strategy.codes : []
  const intervals = Array.isArray(strategy.intervals) ? strategy.intervals : []
  const isPaper = isPaperStrategy(strategy)
  const { showToast } = useToast()
  const navigate = useNavigate()
  // W4 R2 AI 진입점 — userId 1일 토큰 한도 키
  const userId = useOnboardingStore((s) => s.registeredUserId)

  const toggleMutation = useToggleSigStrategyStatus()
  const deleteMutation = useDeleteSigStrategy()
  const favoriteMutation = useToggleListFavorite(userId)
  const archiveMutation = useArchiveSigStrategy(userId)
  const unarchiveMutation = useUnarchiveSigStrategy(userId)
  const isArchived = strategy.status === 'ARCHIVED'
  const floorBadgeInfo = floorBadge(strategy.scaleIn)
  const scaleInBadge = scaleInBadgeLabel(strategy.scaleIn)

  function handleToggleFavorite() {
    favoriteMutation.mutate(
      { id: Number(strategy.id), on: !isFavorite },
      {
        onError: (error) => {
          showToast(resolveStrategyMutationErrorMessage(error), 'error')
        },
      },
    )
  }

  function handleDuplicate() {
    // 기존 전략을 빌더로 불러와 복제 — 빌더가 duplicate 파라미터로 신규 저장 흐름 진입
    navigate(`/strategy/builder?strategyId=${strategy.id}&duplicate=1`)
  }
  const isMissingOperationalTargets = codes.length === 0 || intervals.length === 0
  const activationBlockedReason = strategyActivationBlockedReason(strategy)
  // RiskPlan(손절·익절) 사유는 백테스트 핸드오프 대상이 아님 — 빌더에서 손절/익절 설정 필요
  const isRiskPlanBlockedReason = Boolean(
    activationBlockedReason &&
      (activationBlockedReason.includes('손절') || activationBlockedReason.includes('익절')),
  )
  // "성공한 백테스트가 있어야 …" 사유만 백테스트 화면으로 핸드오프
  const isBacktestBlockedReason = Boolean(activationBlockedReason) && !isRiskPlanBlockedReason

  function handleToggle() {
    const nextStatus: StrategyStatus =
      strategy.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE'
    if (nextStatus === 'ACTIVE' && isMissingOperationalTargets) {
      showToast('자동매매 대상 종목과 평가 인터벌을 먼저 지정해 주세요.', 'error')
      return
    }
    if (nextStatus === 'ACTIVE' && activationBlockedReason) {
      // 백테스트 사유는 toast 대신 백테스트 화면으로 전환 + 해당 전략 prefill
      if (isBacktestBlockedReason) {
        navigate(`/strategy/backtest?strategyId=${strategy.id}`)
        return
      }
      showToast(activationBlockedReason, 'error')
      return
    }
    toggleMutation.mutate(
      { id: strategy.id, status: nextStatus },
      {
        onSuccess: () => {
          showToast(statusSuccessMessage(nextStatus, isPaper), 'success')
        },
        onError: (error) => {
          showToast(resolveStrategyMutationErrorMessage(error), 'error')
        },
      },
    )
  }

  function handlePauseAll() {
    toggleMutation.mutate(
      { id: strategy.id, status: 'PAUSED' },
      {
        onSuccess: () => {
          showToast(statusSuccessMessage('PAUSED', isPaper), 'success')
        },
        onError: (error) => {
          showToast(resolveStrategyMutationErrorMessage(error), 'error')
        },
      },
    )
  }

  function handleDelete() {
    if (!confirmDelete) {
      setConfirmDelete(true)
      return
    }
    deleteMutation.mutate(strategy.id)
    setConfirmDelete(false)
  }

  function handleArchiveConfirm() {
    archiveMutation.mutate(strategy.id, {
      onSuccess: () => {
        setShowArchiveConfirm(false)
        showToast('보관함으로 이동했습니다.', 'success')
      },
      onError: (error) => {
        // 409면 백엔드가 보유 포지션/미체결 주문 등 한국어 사유를 본문에 담아 준다 — 그대로 노출
        setShowArchiveConfirm(false)
        showToast(resolveStrategyMutationErrorMessage(error), 'error')
      },
    })
  }

  function handleUnarchive() {
    unarchiveMutation.mutate(strategy.id, {
      onSuccess: () => {
        // 꺼내기 = PAUSED(중지) 복귀. 자동매매 재시작은 사용자가 별도로 눌러야 함을 명시
        showToast('보관을 해제했습니다. 중지 상태로 이동했습니다.', 'success')
      },
      onError: (error) => {
        showToast(resolveStrategyMutationErrorMessage(error), 'error')
      },
    })
  }

  const isToggleable =
    strategy.status === 'ACTIVE' ||
    strategy.status === 'BUY_BLOCKED' ||
    strategy.status === 'PAUSED' ||
    strategy.status === 'DISABLED'
  const isDeletable = strategy.status !== 'DELETED'
  const isBusy =
    toggleMutation.isPending ||
    deleteMutation.isPending ||
    archiveMutation.isPending ||
    unarchiveMutation.isPending
  const disabledReasonLabel = strategy.disabledReason && strategy.disabledReason !== 'NULL'
    ? DISABLED_REASON_LABEL[strategy.disabledReason] ?? strategy.disabledReason
    : null
  const isRiskViolationProtected = strategy.disabledReason === 'RISK_VIOLATION'
  const disabledAtLabel = formatKst(strategy.lastDisabledAt)
  const activeNeedsAttention = !isPaper && strategy.status === 'ACTIVE' && activationBlockedReason
  const stopLossPct = parsePositiveNumber(strategy.stopLossPct)
  const takeProfitPct = parsePositiveNumber(strategy.takeProfitPct)
  const scaleInLabel = describeScaleIn(strategy.scaleIn)
  const partialExitCount = strategy.partialExits?.length ?? 0
  const displayedCodes = showAllCodes ? codes : codes.slice(0, 4)
  const hiddenCodeCount = Math.max(codes.length - 4, 0)
  const hiddenCodeLabels = codes
    .slice(4)
    .map((code) => formatStrategyCodeLabel(code, strategy.codeNames))
    .join(', ')
  // 보유 현황은 운영(자동매매) 상태에서만 의미 — 종목 매핑이 있을 때만 조회
  const showPositionPanel =
    (strategy.status === 'ACTIVE' || strategy.status === 'BUY_BLOCKED' || strategy.status === 'PAUSED') &&
    codes.length > 0
  const primaryCode = codes[0]
  const riskPlanChartPath =
    primaryCode && stopLossPct !== null && takeProfitPct !== null
      ? `/symbol/${primaryCode}?${new URLSearchParams({
          strategyId: strategy.id,
          stopLossPct: String(stopLossPct),
          takeProfitPct: String(takeProfitPct),
        }).toString()}`
      : null

  return (
    <article
      className={[
        'flex flex-col gap-3 rounded-xl border bg-gray-900 p-5 transition-colors',
        selected
          ? 'border-blue-500 ring-1 ring-blue-500/60'
          : isPaper
            ? 'border-amber-800/70 hover:border-amber-700/80'
            : 'border-gray-800 hover:border-gray-700',
      ].join(' ')}
    >
      {/* 헤더 */}
      <div className="flex items-start justify-between gap-2">
        {selectMode && (
          <label className="mt-1 flex shrink-0 cursor-pointer items-center">
            <span className="sr-only">{strategy.name} 선택</span>
            <input
              type="checkbox"
              checked={selected}
              onChange={() => onToggleSelect?.()}
              aria-label={`${strategy.name} 선택`}
              className="h-4 w-4 rounded border-gray-600 bg-gray-800 text-blue-600 focus:ring-2 focus:ring-blue-400"
            />
          </label>
        )}
        <div className="min-w-0">
          <h3 className="truncate text-base font-semibold text-white" title={strategy.name}>
            {strategy.name}
          </h3>
          {strategy.nameKo && strategy.nameKo !== strategy.name && (
            <p className="mt-0.5 truncate text-xs text-gray-400" title={strategy.nameKo}>
              {strategy.nameKo}
            </p>
          )}
          {isPaper && (
            <div className="mt-1">
              <Link
                to="/strategy/paper"
                className="inline-flex rounded-full focus:outline-none focus:ring-2 focus:ring-amber-400 focus:ring-offset-1 focus:ring-offset-gray-900"
                aria-label="모의 트레이닝으로 이동"
                title="모의 트레이닝으로 이동"
              >
                <PaperBadge />
              </Link>
            </div>
          )}
          <div className="mt-1 flex flex-wrap gap-1">
            {codes.length === 0 && (
              <span className="rounded bg-gray-800 px-1.5 py-0.5 text-xs text-gray-500">
                종목 미지정
              </span>
            )}
            {displayedCodes.map((code) => (
              <span
                key={code}
                title={formatStrategyCodeLabel(code, strategy.codeNames)}
                className="max-w-[12rem] truncate rounded bg-gray-800 px-1.5 py-0.5 text-xs font-medium text-gray-300"
              >
                {formatStrategyCodeLabel(code, strategy.codeNames)}
              </span>
            ))}
            {hiddenCodeCount > 0 && (
              <button
                type="button"
                onClick={() => setShowAllCodes((prev) => !prev)}
                title={showAllCodes ? '종목 접기' : hiddenCodeLabels}
                aria-expanded={showAllCodes}
                aria-label={
                  showAllCodes
                    ? '종목 목록 접기'
                    : `나머지 종목 ${hiddenCodeCount}개 펼치기`
                }
                className="rounded bg-gray-800 px-1.5 py-0.5 text-xs font-medium text-gray-400 transition-colors hover:bg-gray-700 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900"
              >
                {showAllCodes ? '접기' : `+${hiddenCodeCount}`}
              </button>
            )}
          </div>
        </div>

        {/* 상태 배지 + 즐겨찾기 */}
        <div className="flex shrink-0 items-center gap-1.5">
          <button
            type="button"
            onClick={handleToggleFavorite}
            disabled={favoriteMutation.isPending}
            aria-pressed={isFavorite}
            aria-label={isFavorite ? '즐겨찾기 해제' : '즐겨찾기 추가'}
            title={isFavorite ? '즐겨찾기 해제' : '즐겨찾기 추가'}
            className={[
              'rounded p-0.5 text-base leading-none transition-colors focus:outline-none focus:ring-2 focus:ring-amber-400 focus:ring-offset-1 focus:ring-offset-gray-900 disabled:opacity-50',
              isFavorite ? 'text-amber-300 hover:text-amber-200' : 'text-gray-600 hover:text-amber-300',
            ].join(' ')}
          >
            {isFavorite ? '★' : '☆'}
          </button>
          <span className={`rounded px-2 py-0.5 text-xs font-bold ${badge.cls}`}>
            {badge.label}
          </span>
        </div>
      </div>

      {/* 한눈 상태 배지 — 분할매수 진행 · floor 알람/자동청산 (정보 밀도 개선) */}
      {(scaleInBadge || floorBadgeInfo) && (
        <div className="flex flex-wrap items-center gap-1.5">
          {scaleInBadge && (
            <span className="rounded bg-indigo-950/70 px-2 py-0.5 text-xs font-medium text-indigo-300">
              {scaleInBadge}
            </span>
          )}
          {floorBadgeInfo && (
            <span
              className={`rounded px-2 py-0.5 text-xs font-medium ${floorBadgeInfo.cls}`}
              title={floorBadgeInfo.description}
            >
              {floorBadgeInfo.label}
            </span>
          )}
        </div>
      )}

      {/* 인터벌 */}
      {intervals.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {intervals.map((iv) => (
            <span
              key={iv}
              className="rounded bg-blue-950/50 px-2 py-0.5 text-xs text-blue-400"
            >
              {iv}
            </span>
          ))}
        </div>
      )}

      <div className="grid grid-cols-2 gap-2 text-xs text-gray-500">
        <span>전략 ID {strategy.id}</span>
        <span className="text-right">
          {strategyStatusDescription(strategy.status, isPaper)}
        </span>
      </div>

      {/* 보관 시각 — 백엔드가 archivedAt을 내려줄 때만 표시(mock 금지) */}
      {isArchived && strategy.archivedAt && formatKst(strategy.archivedAt) && (
        <p className="text-xs text-gray-500">
          보관 시각: {formatKst(strategy.archivedAt)}
        </p>
      )}

      {strategy.status === 'DISABLED' && (
        <div className="border-l-2 border-amber-500 pl-3 text-xs leading-5 text-amber-100">
          <p className="font-medium">
            {disabledReasonLabel
              ? `중지 사유: ${disabledReasonLabel}`
              : '중지 사유를 확인 중입니다'}
          </p>
          <p className="text-amber-200/70">
            {disabledAtLabel
              ? `${disabledAtLabel}에 자동매매 보호 장치가 전략을 멈췄습니다.`
              : '자동매매 보호 장치가 전략을 멈췄습니다.'}
          </p>
          {isRiskViolationProtected && (
            <div className="mt-2">
              {/* 차단 누적 사유는 popover로 접어 카드 높이를 줄임 (2026-06-17) */}
              <BlockedStatusPopover
                tone="amber"
                label="리스크 차단 누적"
                title="리스크 차단 누적으로 자동 정지"
                summaryLines={[
                  { text: '최근 차단 횟수가 자동정지 기준에 도달했습니다.' },
                  ...(disabledAtLabel
                    ? [{ text: `정지 시각: ${disabledAtLabel}`, muted: true }]
                    : []),
                  {
                    text: '해제: 차단 원인 해소 후 자동매매를 다시 시작하세요.',
                    muted: true,
                  },
                ]}
                historyTo="/risk-events?verdict=BLOCKED&range=7d"
              />
            </div>
          )}
        </div>
      )}

      {strategy.status === 'BUY_BLOCKED' && (
        // "신규 매수 차단" 사유는 라벨 클릭 popover로 접어 카드 높이를 줄임 (2026-06-17)
        <div>
          <BlockedStatusPopover
            tone="sky"
            label={isPaper ? '모의 신규 매수 차단' : '신규 매수 차단'}
            title="신규 매수 차단"
            summaryLines={[
              {
                text: disabledAtLabel
                  ? `${disabledAtLabel}에 리스크 차단 누적이 기준에 도달해 신규 BUY만 막았습니다.`
                  : '리스크 차단 누적이 기준에 도달해 신규 BUY만 막았습니다.',
              },
              {
                text: '기존 포지션의 매도·익절·손절 평가는 계속 유지됩니다.',
                muted: true,
              },
            ]}
            historyTo="/risk-events?verdict=BLOCKED&range=7d"
          />
        </div>
      )}

      {activeNeedsAttention && (
        <div className="border-l-2 border-red-400 bg-red-950/30 px-3 py-2 text-xs leading-5 text-red-100">
          <p className="font-semibold">자동매매 점검 필요</p>
          <p>{activationBlockedReason}</p>
        </div>
      )}

      {/* DSL 미리보기 — 매수 / 매도 조건 */}
      {strategy.buyCondition && (
        <p className="truncate rounded bg-gray-800/60 px-3 py-1.5 font-mono text-xs text-gray-300" title={strategy.buyCondition}>
          <span className="text-emerald-400">매수:</span> {strategy.buyCondition}
        </p>
      )}
      {strategy.sellCondition && (
        <p className="truncate rounded bg-gray-800/60 px-3 py-1.5 font-mono text-xs text-gray-300" title={strategy.sellCondition}>
          <span className="text-orange-300">매도:</span> {strategy.sellCondition}
        </p>
      )}
      {(strategy.stopLossPct !== null && strategy.stopLossPct !== undefined) ||
      (strategy.takeProfitPct !== null && strategy.takeProfitPct !== undefined) ? (
        <RiskPlanPreviewPanel
          className="border-y border-gray-800 py-3"
          title="자동매매 손절·익절"
          stopLossPct={strategy.stopLossPct}
          takeProfitPct={strategy.takeProfitPct}
          actionSlot={
            riskPlanChartPath ? (
              <Link
                to={riskPlanChartPath}
                className="rounded-md bg-gray-800 px-2 py-1 text-[11px] font-medium text-emerald-300 transition-colors hover:bg-emerald-950/60 focus:outline-none focus:ring-2 focus:ring-emerald-400"
              >
                차트에서 보기
              </Link>
            ) : null
          }
        />
      ) : (
        <div className="border-y border-red-900/50 py-3 text-xs text-red-200">
          손절·익절 미설정
        </div>
      )}

      {/* 분할 매수 / 분할 익절 요약 (ADR 0111) */}
      {(scaleInLabel || partialExitCount > 0) && (
        <div className="flex flex-wrap gap-1.5">
          {scaleInLabel && (
            <span className="rounded bg-indigo-950/60 px-2 py-0.5 text-xs text-indigo-300">
              분할매수 {scaleInLabel}
            </span>
          )}
          {partialExitCount > 0 && (
            <span className="rounded bg-emerald-950/60 px-2 py-0.5 text-xs text-emerald-300">
              분할익절 {partialExitCount}단계
            </span>
          )}
        </div>
      )}
      {strategy.viActionPolicy && (
        <div
          className={[
            'rounded border px-3 py-2 text-xs font-semibold',
            strategy.viActionPolicy === 'EXIT_MARKET'
              ? 'border-red-900/60 bg-red-950/20 text-red-200'
              : strategy.viActionPolicy === 'ALERT_ONLY'
              ? 'border-amber-900/60 bg-amber-950/20 text-amber-200'
              : 'border-gray-800 bg-gray-900/60 text-gray-400',
          ].join(' ')}
        >
          VI {strategy.viActionPolicy === 'EXIT_MARKET' ? '시장가 청산' : strategy.viActionPolicy === 'ALERT_ONLY' ? '알림만' : '보유 유지'}
        </div>
      )}

      {/* 전략별 보유 현황 (ADR 0110) */}
      {showPositionPanel && (
        <StrategyPositionPanel strategyId={strategy.id} scaleIn={strategy.scaleIn} />
      )}

      {/* 시그널 카운트 */}
      {strategy.recentSignalCount !== undefined && (
        <p className="text-xs text-gray-500">
          최근 7일 시그널{' '}
          <span className="font-semibold text-gray-300">{strategy.recentSignalCount}건</span>
        </p>
      )}

      {/* 액션 버튼 */}
      <div className="flex flex-wrap items-center gap-2 pt-1">
        <Link
          to={`/strategy/builder?strategyId=${strategy.id}`}
          className="rounded-lg bg-gray-800 px-3 py-1.5 text-xs font-medium text-blue-300 transition-colors hover:bg-blue-950/60 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900"
        >
          빌더에서 수정
        </Link>

        {/* 복제 — 기존 전략을 빌더로 불러와 신규 전략으로 저장 */}
        {strategy.status !== 'DELETED' && (
          <button
            type="button"
            onClick={handleDuplicate}
            className="rounded-lg bg-gray-800 px-3 py-1.5 text-xs font-medium text-gray-200 transition-colors hover:bg-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900"
          >
            복제
          </button>
        )}

        {/* 성과·메모 (T-PERF R3 — ADR 0126) */}
        <button
          type="button"
          onClick={() => setShowPerformance(true)}
          className="rounded-lg bg-gray-800 px-3 py-1.5 text-xs font-medium text-gray-200 transition-colors hover:bg-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900"
        >
          📊 성과·메모
        </button>

        {/* 보관 — 일상 목록에서 치워 보관함으로 이동 (확인 모달 경유) */}
        {!isArchived && strategy.status !== 'DELETED' && (
          <button
            type="button"
            onClick={() => setShowArchiveConfirm(true)}
            disabled={isBusy}
            title="자동매매 평가에서 제외하고 보관함으로 이동"
            className="rounded-lg bg-gray-800 px-3 py-1.5 text-xs font-medium text-gray-300 transition-colors hover:bg-gray-700 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-gray-400 focus:ring-offset-1 focus:ring-offset-gray-900"
          >
            🗄 보관
          </button>
        )}

        {/* 꺼내기 — 보관 해제 → 중지(PAUSED) 상태로 복귀 */}
        {isArchived && (
          <button
            type="button"
            onClick={handleUnarchive}
            disabled={unarchiveMutation.isPending}
            title="보관을 해제하고 중지 상태로 되돌립니다"
            className="rounded-lg bg-blue-700 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900"
          >
            {unarchiveMutation.isPending ? '꺼내는 중...' : '꺼내기'}
          </button>
        )}

        {/* 상태 토글 */}
        {isToggleable && (
          <button
            onClick={handleToggle}
            disabled={isBusy || (strategy.status !== 'ACTIVE' && isMissingOperationalTargets)}
            title={
              strategy.status !== 'ACTIVE' && isMissingOperationalTargets
                ? '빌더에서 수정 후 자동매매 대상 종목과 평가 인터벌을 지정하세요'
                : strategy.status !== 'ACTIVE' && activationBlockedReason
                ? activationBlockedReason
                : undefined
            }
            className={[
              'rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-offset-gray-900',
              strategy.status === 'ACTIVE'
                ? 'bg-amber-700 text-white hover:bg-amber-600 focus:ring-amber-300'
                : activationBlockedReason
                ? 'bg-amber-700 text-white hover:bg-amber-600 focus:ring-amber-300'
                : 'bg-emerald-600 text-white hover:bg-emerald-500 focus:ring-emerald-300',
            ].join(' ')}
          >
            {toggleMutation.isPending
              ? '처리 중...'
              : strategy.status === 'ACTIVE'
              ? isPaper ? '모의 감시 중지' : '자동매매 중지'
              : strategy.status === 'BUY_BLOCKED'
              ? '매수 재개'
              : isMissingOperationalTargets
              ? '종목 지정 필요'
              : activationBlockedReason
              ? isRiskPlanBlockedReason
                ? 'RiskPlan 필요'
                : '백테스트 필요'
              : isPaper ? '모의 감시 시작' : '자동매매 시작'}
          </button>
        )}

        {strategy.status === 'BUY_BLOCKED' && (
          <button
            onClick={handlePauseAll}
            disabled={isBusy}
            className="rounded-lg bg-amber-700 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-amber-600 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-amber-300 focus:ring-offset-1 focus:ring-offset-gray-900"
          >
            전체 중지
          </button>
        )}

        {/* 삭제 */}
        {isDeletable && (
          <button
            onClick={handleDelete}
            disabled={isBusy}
            onBlur={() => setConfirmDelete(false)}
            className={[
              'rounded-lg px-3 py-1.5 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-red-400 focus:ring-offset-1 focus:ring-offset-gray-900',
              confirmDelete
                ? 'bg-red-700 text-white hover:bg-red-600'
                : 'bg-gray-800 text-red-400 hover:bg-red-900/40',
            ].join(' ')}
          >
            {deleteMutation.isPending
              ? '삭제 중...'
              : confirmDelete
              ? '정말 삭제하시겠습니까?'
              : '삭제'}
          </button>
        )}

        {(toggleMutation.isError || deleteMutation.isError) && (
          <p className="text-xs text-red-400">
            {toggleMutation.isError
              ? resolveStrategyMutationErrorMessage(toggleMutation.error)
              : '요청 실패. 다시 시도해 주세요.'}
          </p>
        )}
      </div>

      {/* W4 R2 AI 진입점: STRATEGY (전략 평가) */}
      <div className="border-t border-gray-800 pt-2">
        <AiExplainEntry
          type="STRATEGY"
          userId={userId}
          context={{
            strategyId: strategy.id,
            name: strategy.name,
            codes,
            intervals,
            status: strategy.status,
            buyCondition: strategy.buyCondition ?? null,
            sellCondition: strategy.sellCondition ?? null,
            stopLossPct: strategy.stopLossPct ?? null,
            takeProfitPct: strategy.takeProfitPct ?? null,
            viActionPolicy: strategy.viActionPolicy ?? 'ALERT_ONLY',
            recentSignalCount: strategy.recentSignalCount ?? 0,
          }}
        />
      </div>

      {/* 성과·메모 모달 (T-PERF R3 — ADR 0126) */}
      {showPerformance && (
        <StrategyPerformanceModal
          strategyId={Number(strategy.id)}
          strategyName={strategy.name}
          userId={userId}
          onClose={() => setShowPerformance(false)}
        />
      )}

      {/* 보관 확인 모달 */}
      {showArchiveConfirm && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby={`archive-confirm-title-${strategy.id}`}
          onClick={() => {
            if (!archiveMutation.isPending) setShowArchiveConfirm(false)
          }}
        >
          <div
            className="w-full max-w-sm rounded-xl border border-gray-700 bg-gray-900 p-5 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <h4
              id={`archive-confirm-title-${strategy.id}`}
              className="text-base font-semibold text-white"
            >
              전략 보관
            </h4>
            <p className="mt-2 text-sm text-gray-300">
              <span className="font-medium text-white">{strategy.name}</span> 전략을 보관합니다.
            </p>
            <p className="mt-1 text-xs leading-5 text-gray-400">
              자동매매 평가에서 제외되고 보관함으로 이동합니다. 보관함 탭에서 언제든 다시 꺼낼 수 있습니다.
            </p>
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setShowArchiveConfirm(false)}
                disabled={archiveMutation.isPending}
                className="rounded-lg bg-gray-800 px-3 py-1.5 text-xs font-medium text-gray-300 transition-colors hover:bg-gray-700 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-gray-400"
              >
                취소
              </button>
              <button
                type="button"
                onClick={handleArchiveConfirm}
                disabled={archiveMutation.isPending}
                className="rounded-lg bg-amber-700 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-amber-600 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-amber-300"
              >
                {archiveMutation.isPending ? '보관 중...' : '보관하기'}
              </button>
            </div>
          </div>
        </div>
      )}
    </article>
  )
}

function strategyStatusDescription(status: StrategyStatus, isPaper: boolean): string {
  switch (status) {
    case 'ACTIVE':
      return isPaper ? '모의 감시 중' : '자동매매 수행 중'
    case 'BUY_BLOCKED':
      return isPaper ? '모의 신규 매수 차단' : '신규 매수 차단'
    case 'PAUSED':
      return isPaper ? '모의 감시 중지됨' : '자동매매 중지됨'
    case 'DISABLED':
      return '보호 장치로 중지'
    case 'ARCHIVED':
      return '보관함으로 이동됨'
    case 'DELETED':
      return '삭제됨'
  }
}

function statusSuccessMessage(status: StrategyStatus, isPaper = false): string {
  switch (status) {
    case 'ACTIVE':
      return isPaper ? '모의 감시가 시작되었습니다.' : '자동매매가 시작되었습니다.'
    case 'BUY_BLOCKED':
      return '신규 매수가 차단되었습니다.'
    case 'PAUSED':
      return isPaper ? '모의 감시가 중지되었습니다.' : '자동매매가 중지되었습니다.'
    case 'DELETED':
      return '전략이 삭제되었습니다.'
    case 'DISABLED':
      return '자동매매가 보호 중지되었습니다.'
    case 'ARCHIVED':
      return '전략이 보관되었습니다.'
  }
}

function formatKst(value: string | null | undefined): string | null {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}
