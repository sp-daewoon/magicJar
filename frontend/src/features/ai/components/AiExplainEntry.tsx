/**
 * AiExplainEntry — 5 진입점 thin wrapper (T-AI R2 §2-2)
 *
 * AiCard + useAiExplain hook 조합. 진입점별 type + context 빌더만 다르므로
 * 페이지에서 prop으로 type + contextBuilder를 주입해 단일 컴포넌트 재사용.
 *
 * 5 진입점에서 사용:
 *   - SymbolDetailPage: <AiExplainEntry type="SYMBOL" context={{code, name}} />
 *   - SymbolDetailPage 차트: <AiExplainEntry type="CHART" context={{code, interval, candles}} />
 *   - StrategyDetail: <AiExplainEntry type="STRATEGY" context={{strategyId, dsl}} />
 *   - SignalCard: <AiExplainEntry type="SIGNAL" context={{signalId, code, side, ...}} />
 *   - MarketTrendPage: <AiExplainEntry type="MARKET" context={{kospiTrend, sectorTopList, maPeriods, rsPeriod}} />
 */
import { memo, useCallback } from 'react'
import type { AiCommentType } from '../../../api/ai'
import { useAiExplain } from '../../../api/hooks/useAiExplain'
import { AiCard } from './AiCard'

interface Props {
  type: AiCommentType
  userId: number | null | undefined
  /** prompt 변수 치환 context — type 별 backend variables_schema 정합. 호출 측에서 referential stable(useMemo) 전달 권장. */
  context: Record<string, unknown>
  /** 비활성 조건 (예: 데이터 부족 시). */
  disabled?: boolean
  disabledMessage?: string
}

// React.memo — SymbolDetailPage 등 실시간 tick 부모의 무관 재렌더 차단 (audit P2-16).
// context 가 매 렌더 재생성되면 memo 무효 — 호출 측 useMemo 정합 필수.
export const AiExplainEntry = memo(function AiExplainEntry({ type, userId, context, disabled, disabledMessage }: Props) {
  const { state, trigger, reset } = useAiExplain({ type, userId })

  const handleTrigger = useCallback(() => {
    void trigger(context)
  }, [trigger, context])

  return (
    <AiCard
      type={type}
      state={state}
      onTrigger={handleTrigger}
      onReset={reset}
      disabled={disabled || userId == null}
      disabledMessage={
        disabledMessage ?? (userId == null ? 'KIS 계좌 설정 후 사용 가능합니다.' : undefined)
      }
    />
  )
})
