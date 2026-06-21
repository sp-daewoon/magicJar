/**
 * ScaleInPlanPreviewInline — 저장 전 분할 매수 계획 미리보기 (ADR 0128 R3 · 빌더 고도화 A)
 *
 * StrategySaveModal 안에서 scale-in enabled 일 때 노출하는 경량 미리보기.
 * 저장된 strategyId가 없어 서버 scalein-plan API를 못 부르므로 client-side 산식
 * (buildScaleInPlanPreview)으로 회차별 비중·예상 진입가·예상 손절가·floor 알람선을 보여준다.
 *
 * ★ "예상/미리보기" 명시 — 자본 실행이 아니다. 수량은 표기하지 않는다(base 예산 미상).
 *   기준가 미입력 시 "기준가 입력 시 미리보기" 안내(mock 합성 0건).
 *
 * 저장 후 화면용 StrategyScaleInPlanPanel(서버 API + 폴링)과 별개의 전용 컴포넌트.
 *
 * 룰 5: LLM provider import 0건
 */
import type { ScaleInDto } from '../../api/strategy'
import { buildScaleInPlanPreview } from './scaleInPlanPreviewDisplay'

interface ScaleInPlanPreviewInlineProps {
  scaleIn: ScaleInDto
  manualWeights: boolean
  previewEntryPrice: number | string | null | undefined
  stopLossPct: number | string | null | undefined
  className?: string
}

function formatKrw(value: number): string {
  return `${Math.round(value).toLocaleString('ko-KR')}원`
}

function formatPct(value: number): string {
  return `${Number(value.toFixed(2))}%`
}

export function ScaleInPlanPreviewInline({
  scaleIn,
  manualWeights,
  previewEntryPrice,
  stopLossPct,
  className = '',
}: ScaleInPlanPreviewInlineProps) {
  const plan = buildScaleInPlanPreview({ scaleIn, manualWeights, previewEntryPrice, stopLossPct })
  const hasBase = plan.basePrice !== null

  return (
    <section
      className={['rounded-lg border border-indigo-900/50 bg-indigo-950/15 p-3', className].filter(Boolean).join(' ')}
      aria-label="저장 전 분할 매수 계획 미리보기"
    >
      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs font-semibold text-indigo-200">예상 매수 계획 (미리보기)</span>
        <span className="text-[11px] text-gray-500">저장 안 됨 · 비중 기준</span>
      </div>

      {plan.slices.length === 0 ? (
        <p className="text-xs text-gray-500">표시할 회차가 없습니다 (비중이 모두 0%).</p>
      ) : (
        <ul className="space-y-1" aria-label="회차별 예상 매수 계획">
          {plan.slices.map((slice) => (
            <li
              key={slice.sliceIndex}
              className="rounded border border-gray-800/60 bg-gray-900/30 px-2 py-1.5 text-xs"
            >
              <div className="flex items-center justify-between gap-2">
                <span className="font-medium text-gray-200">
                  {slice.displayOrder}차
                  <span className="ml-1.5 text-[11px] text-indigo-300">{formatPct(slice.weightPct)}</span>
                </span>
                <span className="tabular-nums font-medium text-gray-200">
                  {slice.expectedEntryPrice !== null ? (
                    `예상 ${formatKrw(slice.expectedEntryPrice)}`
                  ) : (
                    <span className="text-gray-500">예상가 미정</span>
                  )}
                </span>
              </div>
              <div className="mt-0.5 text-[11px] text-gray-500">{slice.triggerDescription}</div>
            </li>
          ))}
        </ul>
      )}

      {!hasBase && (
        <p className="mt-2 text-[11px] text-amber-300/90" role="status">
          위 미리보기 기준가를 입력하면 회차별 예상 진입가·손절가가 표시됩니다.
        </p>
      )}

      {/* 예상 손절가 + floor 알람선 */}
      <div className="mt-2 grid grid-cols-1 gap-1.5 border-t border-gray-800/60 pt-2 sm:grid-cols-2">
        <div className="rounded border border-red-900/40 bg-red-950/15 px-2 py-1.5">
          <div className="text-[11px] text-gray-400">예상 손절가</div>
          {plan.expectedStopLossPrice !== null ? (
            <div className="tabular-nums font-semibold text-red-200">{formatKrw(plan.expectedStopLossPrice)}</div>
          ) : (
            <div className="text-[11px] text-gray-500">
              {stopLossPct ? '기준가 입력 시' : '손절 미설정'}
            </div>
          )}
        </div>

        <div className="rounded border border-amber-900/40 bg-amber-950/15 px-2 py-1.5">
          <div className="text-[11px] text-gray-400">
            알람선 floor
            {plan.floorPct !== null && <span className="ml-1 text-amber-300">(−{formatPct(plan.floorPct)})</span>}
          </div>
          {plan.floorPct === null ? (
            <div className="text-[11px] text-gray-500">floor 비활성</div>
          ) : plan.floorPrice !== null ? (
            <>
              <div className="tabular-nums font-semibold text-amber-200">{formatKrw(plan.floorPrice)}</div>
              <div className="mt-0.5 text-[10px] leading-tight text-amber-400/80">
                {plan.floorAutoLiquidate ? '자동 청산' : '알람만 · 자동 청산 아님'}
              </div>
            </>
          ) : (
            <div className="text-[11px] text-gray-500">기준가 입력 시</div>
          )}
        </div>
      </div>
    </section>
  )
}
