/**
 * StrategyPositionPanel — 전략별 보유 현황 표시 (ADR 0110 · 분할매수 R3-a)
 *
 * 그 전략이 산 물량(수량·평단·분할매수 단계 N/M)과 손익 두 종류를 표시한다.
 *   - 평가손익(미실현, 폴링): currentPrice·unrealizedPnl·unrealizedPnlPct (PR #1035, Redis WS 시세캐시).
 *     useStrategyPosition 8초 폴링으로 주기 갱신된다. 시세 캐시 miss(null)면 "시세 대기"/"—" 표기(mock 금지).
 *     priceSource로 출처 부기: 'LIVE'→"(실시간)", 'CLOSE'→"(종가기준)"(마감/장전 캔들 종가).
 *   - 실현손익: realizedPnl (확정 손익, 불변).
 * 데이터는 strategy_position (GET /api/strategies/{id}/position). 보유 0건이면 "보유 없음"(mock 합성 0건).
 *
 * "분할매수"는 filledLots(이 포지션의 분할매수 단계)이며 오늘 거래 횟수가 아니다.
 * scaleIn.totalSlices가 있으면 N/설정값(filledLots/totalSlices)으로 표시한다.
 *
 * 룰 5: LLM provider import 0건
 */
import { useStrategyPosition } from '../../api/hooks/useStrategyPosition'
import type { ScaleInDto } from '../../api/strategy'
import { StrategyScaleInPlanPanel } from './StrategyScaleInPlanPanel'

interface StrategyPositionPanelProps {
  strategyId: string
  /** 분할 매수 설정 (진행 회차 N/M 표기 + 분할 매수 계획 패널 게이트) */
  scaleIn?: ScaleInDto | null
  className?: string
}

function formatKrw(value: number): string {
  return `${Math.round(value).toLocaleString('ko-KR')}원`
}

function formatSignedKrw(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${Math.round(value).toLocaleString('ko-KR')}원`
}

function formatSignedPct(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(1)}%`
}

/** 손익 부호별 색조 (양수 초록 / 음수 빨강 / 0 회색) */
function pnlToneOf(value: number): string {
  return value > 0 ? 'text-emerald-300' : value < 0 ? 'text-red-300' : 'text-gray-400'
}

export function StrategyPositionPanel({ strategyId, scaleIn, className = '' }: StrategyPositionPanelProps) {
  const { data, isLoading, isError } = useStrategyPosition(strategyId)

  const held = (data ?? []).filter((p) => p.quantity > 0)
  // 분할 매수 계획 미진입 미리보기 시 목표 수량 산정 기준가 — 첫 보유 currentPrice(LIVE/CLOSE).
  // 보유 중이면 서버가 박제 base·평단으로 정확 계산하므로 null이어도 무방.
  const planReferencePrice = held[0]?.currentPrice ?? null
  // 분할 매수 설정이 있을 때만 계획 패널 마운트 (서버 400 회피)
  const hasScaleIn = !!scaleIn && scaleIn.totalSlices > 1

  return (
    <div className={['space-y-2', className].filter(Boolean).join(' ')}>
    <section className="rounded-lg border border-gray-800 bg-gray-950/40 p-3" aria-label="전략별 보유 현황">
      <div className="mb-2 text-xs font-semibold text-gray-300">전략 보유 현황</div>

      {isLoading && <p className="text-xs text-gray-500">불러오는 중...</p>}

      {isError && (
        <p className="text-xs text-amber-300" role="alert">
          보유 현황을 불러오지 못했습니다.
        </p>
      )}

      {!isLoading && !isError && held.length === 0 && (
        <p className="text-xs text-gray-500">보유 없음</p>
      )}

      {!isLoading && !isError && held.length > 0 && (
        <ul className="space-y-2">
          {held.map((p) => {
            const totalSlices = scaleIn?.totalSlices
            const hasLive = p.unrealizedPnl !== null && p.currentPrice !== null
            const isClose = p.priceSource === 'CLOSE'
            const priceSourceLabel = isClose ? '(종가기준)' : '(실시간)'
            return (
              <li key={`${p.strategyId}-${p.code}`} className="rounded border border-gray-800/60 bg-gray-900/30 p-2">
                <div className="grid grid-cols-2 gap-x-3 gap-y-1 text-xs sm:grid-cols-4">
                  <div>
                    <div className="text-[11px] text-gray-500">종목</div>
                    <div className="font-medium text-gray-200">{p.codeName ?? p.code}</div>
                  </div>
                  <div>
                    <div className="text-[11px] text-gray-500">수량 · 평단</div>
                    <div className="font-medium tabular-nums text-gray-200">
                      {p.quantity.toLocaleString('ko-KR')}주 · {formatKrw(p.avgPrice)}
                    </div>
                  </div>
                  <div>
                    <div className="text-[11px] text-gray-500">분할매수</div>
                    <div className="font-medium tabular-nums text-indigo-300">
                      {totalSlices ? `${p.filledLots}/${totalSlices}` : `${p.filledLots}`}
                    </div>
                  </div>
                  <div>
                    <div className="text-[11px] text-gray-500">실현손익</div>
                    <div className={`font-medium tabular-nums ${pnlToneOf(p.realizedPnl)}`}>
                      {formatSignedKrw(p.realizedPnl)}
                    </div>
                  </div>
                </div>

                <div className="mt-1.5 border-t border-gray-800/60 pt-1.5 text-xs">
                  <div className="text-[11px] text-gray-500">
                    평가손익 {hasLive ? <span className="text-gray-600">{priceSourceLabel}</span> : null}
                  </div>
                  {hasLive ? (
                    <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 tabular-nums">
                      <span className={`font-semibold ${pnlToneOf(p.unrealizedPnl as number)}`}>
                        {formatSignedKrw(p.unrealizedPnl as number)}
                      </span>
                      {p.unrealizedPnlPct !== null && (
                        <span className={`${pnlToneOf(p.unrealizedPnlPct)}`}>
                          ({formatSignedPct(p.unrealizedPnlPct)})
                        </span>
                      )}
                      <span className="text-gray-500">
                        · {isClose ? '종가' : '현재가'} {formatKrw(p.currentPrice as number)}
                      </span>
                    </div>
                  ) : (
                    <div className="text-gray-500" title="시세 캐시 miss — 다음 폴링에서 갱신">
                      시세 대기 · —
                    </div>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </section>

      {/* 분할 매수 계획 (ADR 0128 R3 — 예상 매수 계획·손절가·floor 알람선) */}
      {hasScaleIn && (
        <StrategyScaleInPlanPanel strategyId={strategyId} currentPrice={planReferencePrice} />
      )}
    </div>
  )
}
