/**
 * StrategyScaleInPlanPanel — 분할 매수 계획 표시 (ADR 0128 R3 · 사용자 ⑧⑨ 권위)
 *
 * 사용자(최고 관리자)가 "예상 매수 계획을 전략에서 꼭 볼 수 있게" 강조한 화면.
 * GET /api/strategies/{id}/scalein-plan?currentPrice= 의 ScaleInPlanDto를 렌더한다.
 *
 *   1. 회차별 계획 표 — 목표 수량 + 진입 조건(triggerDescription). 0% 회차는 서버에서 제외(⑨).
 *      미진입(목표 수량 null)이면 비중·조건만 미리보기 + "기준가 대기" 표기(mock 합성 0건).
 *   2. 예상 손절가 — expectedStopLossPrice(평단 × (1 − 손절%)). 분할 진행으로 평단이 바뀌면
 *      8초 폴링(useScaleInPlan)으로 갱신된다. 손절% 미설정 시 안내.
 *   3. floor 알람선 — floorPrice/floorPct를 "알람만(자동 청산 아님)"으로 명확히 구분 표기(⑦).
 *      사용자: floor는 알람만, 청산은 수동.
 *
 * 호출 게이트: 분할 매수 설정(scaleIn)이 있을 때만 마운트(서버 400 회피 — 호출 측 책임).
 * 룰 5: LLM provider import 0건
 */
import { useScaleInPlan } from '../../api/hooks/useScaleInPlan'
import type { ScaleInPlanDto } from '../../api/strategyPosition'

interface StrategyScaleInPlanPanelProps {
  strategyId: string
  /**
   * 미진입(미보유) 미리보기 시 목표 수량 산정 기준가(라이브 현재가).
   * 보유 중이면 서버가 박제 base·평단으로 정확 계산하므로 생략 가능.
   */
  currentPrice?: number | null
  className?: string
}

function formatKrw(value: number): string {
  return `${Math.round(value).toLocaleString('ko-KR')}원`
}

function formatPct(value: number): string {
  // 소수 둘째 자리까지(불필요한 0 제거)
  return `${Number(value.toFixed(2))}%`
}

export function StrategyScaleInPlanPanel({ strategyId, currentPrice, className = '' }: StrategyScaleInPlanPanelProps) {
  // scaleIn 설정이 있을 때만 마운트되므로 enabled=true 고정 (호출 게이트는 부모 책임)
  const { data, isLoading, isError } = useScaleInPlan(strategyId, true, currentPrice)

  return (
    <section
      className={['rounded-lg border border-gray-800 bg-gray-950/40 p-3', className].filter(Boolean).join(' ')}
      aria-label="분할 매수 계획"
    >
      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs font-semibold text-gray-300">예상 매수 계획</span>
        {data && (
          <span className="text-[11px] text-gray-500">
            {data.held ? `진행 ${data.filledLots}/${data.totalSlices}회차` : '미진입 (미리보기)'}
          </span>
        )}
      </div>

      {isLoading && <p className="text-xs text-gray-500">불러오는 중...</p>}

      {isError && (
        <p className="text-xs text-amber-300" role="alert">
          분할 매수 계획을 불러오지 못했습니다.
        </p>
      )}

      {!isLoading && !isError && data && <PlanBody plan={data} />}
    </section>
  )
}

function PlanBody({ plan }: { plan: ScaleInPlanDto }) {
  const hasQuantities = plan.slices.some((s) => s.targetQuantity !== null)

  return (
    <div className="space-y-2.5">
      {/* 회차별 계획 표 */}
      {plan.slices.length === 0 ? (
        <p className="text-xs text-gray-500">표시할 회차가 없습니다.</p>
      ) : (
        <ul className="space-y-1" aria-label="회차별 분할 매수 계획">
          {plan.slices.map((slice) => {
            const isFilled = plan.held && slice.displayOrder <= plan.filledLots
            return (
              <li
                key={slice.sliceIndex}
                className={[
                  'rounded border px-2 py-1.5 text-xs',
                  isFilled ? 'border-emerald-900/50 bg-emerald-950/20' : 'border-gray-800/60 bg-gray-900/30',
                ].join(' ')}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium text-gray-200">
                    {slice.displayOrder}차
                    <span className="ml-1.5 text-[11px] text-indigo-300">{formatPct(slice.weightPct)}</span>
                    {isFilled && <span className="ml-1.5 text-[11px] text-emerald-400">진입 완료</span>}
                  </span>
                  <span className="tabular-nums font-medium text-gray-200">
                    {slice.targetQuantity !== null ? (
                      `${slice.targetQuantity.toLocaleString('ko-KR')}주`
                    ) : (
                      <span className="text-gray-500" title="기준가(현재가·평단)가 없어 목표 수량 미산출">
                        기준가 대기
                      </span>
                    )}
                  </span>
                </div>
                <div className="mt-0.5 text-[11px] text-gray-500">{slice.triggerDescription}</div>
              </li>
            )
          })}
        </ul>
      )}

      {!hasQuantities && plan.slices.length > 0 && (
        <p className="text-[11px] text-gray-500">
          1차 진입(또는 기준가 확보) 후 목표 수량이 계산됩니다.
        </p>
      )}

      {/* 예상 손절가 + floor 알람선 */}
      <div className="grid grid-cols-1 gap-1.5 border-t border-gray-800/60 pt-2 sm:grid-cols-2">
        {/* 예상 손절가 (자동 청산) */}
        <div className="rounded border border-red-900/40 bg-red-950/15 px-2 py-1.5">
          <div className="text-[11px] text-gray-400">
            예상 손절가
            {plan.fixedStopLossPct !== null && (
              <span className="ml-1 text-red-300">(−{formatPct(plan.fixedStopLossPct)})</span>
            )}
          </div>
          {plan.expectedStopLossPrice !== null ? (
            <div className="tabular-nums font-semibold text-red-200">{formatKrw(plan.expectedStopLossPrice)}</div>
          ) : plan.fixedStopLossPct === null ? (
            <div className="text-[11px] text-gray-500">손절 미설정</div>
          ) : (
            <div className="text-[11px] text-gray-500" title="평단·기준가 미확보">기준가 대기</div>
          )}
        </div>

        {/* floor 알람선 (자동 청산 아님 — ⑦) */}
        <div className="rounded border border-amber-900/40 bg-amber-950/15 px-2 py-1.5">
          <div className="text-[11px] text-gray-400">
            알람선 floor
            <span className="ml-1 text-amber-300">(−{formatPct(plan.floorPct)})</span>
          </div>
          {plan.floorPrice !== null ? (
            <div className="tabular-nums font-semibold text-amber-200">{formatKrw(plan.floorPrice)}</div>
          ) : (
            <div className="text-[11px] text-gray-500" title="평단·기준가 미확보">기준가 대기</div>
          )}
          <div className="mt-0.5 text-[10px] leading-tight text-amber-400/80">알람만 · 자동 청산 아님 (청산은 수동)</div>
        </div>
      </div>
    </div>
  )
}
