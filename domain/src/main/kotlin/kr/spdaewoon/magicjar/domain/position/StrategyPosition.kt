package kr.spdaewoon.magicjar.domain.position

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.strategy.TradePlan
import java.math.BigDecimal

/**
 * 전략별 포지션 귀속 도메인 — ownership 토대 (ADR 0110, 분할매수 spec §3-1).
 *
 * 기존 [Position](user+code 합산, KIS 잔고 권위)은 유지하고, 그 아래에 전략별 lot ledger를 둔다.
 * 동일 종목을 2전략이 동시 운용해도 "이 전략이 산 물량"만 추적·청산하기 위한 1급 도메인.
 *
 * ## 불변식 (R1은 KDoc 명세만 — 갱신/청산 로직 구현은 R2 체결 listener 책임)
 *
 * - **정합**: `sum(StrategyPosition.quantity WHERE user+code) == Position.quantity`.
 *   drift 시 KIS 잔고(Position)가 상위 권위(ADR 0106 §6) — `STRATEGY_POSITION_DRIFT` SystemEvent(WARN) 후 보수적으로 Position 우선.
 * - **평단 갱신(BUY 체결)**: `avgPrice = (avgPrice*quantity + fillPrice*fillQty) / (quantity+fillQty)`,
 *   `quantity += fillQty`, `filledLots += 1`.
 * - **부분/전량 청산(SELL 체결)**: `realizedPnl += (fillPrice - avgPrice)*sellQty`, `quantity -= sellQty`.
 * - **전량 청산 reset**: `quantity == 0`(STOP_LOSS·최종 TAKE_PROFIT 전량 청산)이면 `avgPrice`·`filledLots`·
 *   `executedExitStages` 모두 reset(행은 유지 — 재진입 시 새 사이클). 부분 청산만으로는 reset 안 함(단계 단조 진행 보존).
 * - **부분 익절 중복 발화 방지**: PartialExit 각 단계는 1회만 발화. `evaluatePartialTakeProfit`는
 *   `stageIndex !in executedExitStages` 일 때만 SELL을 발행하고, **체결 확정 시** `executedExitStages += stageIndex`
 *   (발행 시점이 아니라 체결 시점 마킹 — 미체결 reject 시 재시도 가능). [filledLots](진입 단조 카운터)와 대칭 구조.
 *
 * ## 귀속 키
 *
 * OrderIntent·order_execution은 이미 `strategyId`를 보유 → 체결 listener가 strategyId로 StrategyPosition upsert.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 도메인 (ADR 0023 §2-4 정합)
 */
data class StrategyPosition(
    val id: Long? = null,
    val userId: String,
    val strategyId: Long,
    val code: String,
    /** 이 전략이 현재 보유한 수량 (분할 누적 - 청산). 0이면 청산(행 유지). */
    val quantity: Int,
    /** 이 전략의 평단 (분할 매수로 가중평균 갱신). quantity=0이면 reset 대상. */
    val avgPrice: BigDecimal,
    /** scale-in 진행도 — 체결된 분할 회차 수. totalSlices 도달 시 추가 진입 무시(평단 오염 방지). */
    val filledLots: Int = 0,
    /** 이 전략의 누적 실현 손익 (부분/전량 청산 누적). */
    val realizedPnl: BigDecimal = BigDecimal.ZERO,
    /** 이미 발화·체결된 PartialExit 단계 인덱스 집합 — 부분 익절 중복 발화 방지. filledLots와 대칭. */
    val executedExitStages: Set<Int> = emptySet(),
    /**
     * **분할매수 base 고정값**(원) — 1차 진입 시점의 매수가능액([RiskBudgetCalculator.BuyBudget.buyable])을 저장해
     * 2·3차 회차 예산을 `고정 base × 회차 비중%`으로 산정한다 (2026-06-19 사용자 결재 ①, ADR 0128).
     *
     * ## 왜 고정하나
     * 회차마다 buyable 을 재계산하면, 1차 매수가 전체노출을 늘려 남은 한도가 줄고 → 2·3차 회차 예산이 의도보다
     * 작아진다(분할 비중 왜곡). 1차 시점 base 를 1회 박제해 회차 비중만 곱하면 [40,35,25] 의도가 정확히 보존된다.
     *
     * ## 외부 주입 추상화 (미래 목표금액 매집 호환)
     * 이 필드는 "1차 시점 buyable"의 박제값이지만, 그 의미는 **"분할 전체에 쓸 종목 예산"**으로 추상화돼 있다.
     * 미래 "지정 가격 + 목표 금액" 매집 트랙에선 base = 사용자 지정 목표 금액으로 채워 넣으면 회차 산식 변경 0으로 호환된다.
     *
     * null = 아직 1차 미진입(또는 분할 미사용 전략). quantity=0 전량 청산 시 reset(다음 사이클 새 base).
     */
    val scaleInBaseBudget: BigDecimal? = null,
    /**
     * **부분 매수 잔여 수량**(주) — 회차 예산이 살 수 있는 수량보다 자금이 부족해 일부만 체결됐을 때, 못 산 잔여
     * 수량을 기억한다 (2026-06-19 사용자 결재 ②, ADR 0128). 자금이 생기면 다음 boundary 에서 마저 채운다.
     * 0 = 잔여 없음(정상). quantity=0 전량 청산 시 reset.
     */
    val scaleInRemainderQty: Int = 0,
    /**
     * **거래 계획(Planned Entry) 박제값** — ADR 0130 L2. owner=AUTOPILOT 전략이 진입(BUY intent 발행 성공) 시점에
     * 이 lot 의 thesis·TP·SL·trailing·hold·exitConditions·contingency·status·exitReason 를 1회 박제한다
     * (`strategy_position.trade_plan` JSONB · V95). R2 [PlanExecutor]가 이 계획을 lot-scoped 로 보장 실행한다.
     *
     * null = 미기록 — **USER/SYSTEM 전략(owner 격리)·게이트(`magicjar.autopilot.planned-entry.enabled`) off** 시
     * 항상 null 이며 현행 청산 라우팅 그대로(zero-regression). 박제 자체는 진입 시
     * ([SignalGeneratedKafkaListener.captureTradePlanIfAutopilot]) 책임이다.
     *
     * ## 전량 청산 reset 규약 (불변식 — 위 quantity=0 reset 과 합류)
     * `quantity == 0`(전량 청산) 시 `avgPrice`·`filledLots`·`executedExitStages`·`scaleInBaseBudget`·
     * `scaleInRemainderQty` 와 함께 **`tradePlan` 도 reset(null)** 한다(행은 유지 — 다음 진입 사이클은 새 plan 박제).
     * 부분 청산만으로는 reset 안 함(계획은 lot 사이클 동안 유효). reset 적용은 R2 체결 listener 책임.
     */
    val tradePlan: TradePlan? = null,
    val openedAt: Instant,
    val updatedAt: Instant,
) {
    /** 전량 청산 여부 (Position.isClosed 패턴 정합). */
    val isClosed: Boolean get() = quantity == 0
}
