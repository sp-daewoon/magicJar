package kr.spdaewoon.magicjar.strategy.integrity

/**
 * strategy_position **부족(underflow) 방향** 드리프트의 귀속 근거 기반 자동 보정 산정 — ADR 0115 §P1-1 amend (2026-06-10).
 *
 * ## 배경 — 2026-06-10 실측 incident (ADR 0115 §1 사슬의 반대 방향)
 * [PositionDriftCorrection]는 **초과(strategySum > authority)** 만 내림 보정하고, 부족(strategySum < authority)은
 * [DriftCorrectionResult.WarnUnderflow] 로 WARN-only 였다(없는 물량 인식 회피 — 외부 HTS 매수 등 정상 사유 가능).
 *
 * 그러나 2026-06-10 전략8·005930 incident는 부족 방향이면서 **귀속 근거가 명확**한 경우였다:
 * WS 체결통보 유실 → REST backfill 흡수(`BACKFILL:...` execution)된 order#177(strategyId=8) FILLED 3주가
 * `StrategyPositionExecutionReplayService` 의 "row 기존재 → replay skip"(의도적 under-count, over-count 회피)로
 * strategy_position 에 반영되지 못해 qty=0 vs KIS position 3주 drift 고착 → 시그널 54건 BLOCKED.
 *
 * 이 부족은 **외부 주문이 아니라 우리 전략이 체결시킨 주문의 ledger 미반영**이다. order_request.strategy_id(=8)가
 * 명확한 귀속 근거다. 따라서 "없는 물량 인식" risk 가 없고 정정 가능하다 — 본 함수가 그 경우만 좁게 정정한다.
 *
 * ## 보수성 가드 (자본 경로 — over-count 0 LOCK)
 * 부족 방향 보정은 "없는 물량을 인식"할 risk 가 본질적으로 있으므로, 다음 3중 게이트를 **모두** 통과할 때만 정정한다:
 *
 * 1. **귀속 근거 명확**: 각 leg 의 목표 수량은 그 전략에 귀속된 **FILLED execution ledger 의 순매수량**
 *    (`sum(BUY filled qty) - sum(SELL filled qty)`, source=AUTO·strategyId≠0)이다. 외부(EXTERNAL)·MANUAL(strategyId=0)
 *    체결은 ledger 집계에서 제외되므로 귀속 근거에 포함되지 않는다([LedgerAttribution] 입력 책임).
 * 2. **멱등(증분 아닌 절대 목표)**: 보정은 leg 수량을 ledger 순매수량으로 **set** 한다(증분 가산 아님). 이미 정합이면
 *    no-op([DriftCorrectionResult.NoChange]). 같은 입력 재실행은 항상 동일 결과 — over-count 구조적 0.
 * 3. **전체 정합 검산**: 보정 후 `sum(목표 leg quantity) == authority`(KIS position 권위) 가 **정확히** 성립할 때만
 *    [DriftCorrectionResult.Adjust]. ledger 합이 authority 와 어긋나면(외부 주문 혼재·부분 귀속·KIS 미반영 등 모호)
 *    함부로 정정하지 않고 [DriftCorrectionResult.WarnUnderflow] 로 신호만 — 운영 알림·수동 판단에 맡긴다.
 *
 * ## 방향 제약 (LOCK)
 * 본 함수는 **부족 방향 한정**(strategySum < authority)이다. 초과·일치는 [PositionDriftCorrection] 책임이므로
 * 그 경우 [DriftCorrectionResult.NoChange] 를 반환해 본 경로가 초과 정정에 개입하지 않는다(단일 책임).
 *
 * ## 순수성 (LOCK — [PositionDriftCorrection]·[kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitCalculator] 선례 1:1)
 * Spring·JPA·Clock·IO·KIS 의존 0. 입력([LedgerDriftInput]) → 출력([DriftCorrectionResult]) 결정론.
 * ledger 집계(JPA @Query)·position 조회·DB write 는 R2 어댑터([ConsumerExecutionStrategyPositionAdapter])가 배선한다.
 *
 * @see PositionDriftCorrection 초과 방향(내림) 보정 — 본 함수와 방향 상보
 * @see DriftCorrectionResult 공유 출력 계약(R2가 소비)
 */
object LedgerAttributedDriftCorrection {
    /**
     * 부족 방향 귀속 보정 산정 단일 진입점.
     *
     * @return
     *  - 토글 off → [DriftCorrectionResult.Disabled]
     *  - 부족 아님(strategySum >= authority) → [DriftCorrectionResult.NoChange] (초과·일치는 [PositionDriftCorrection] 책임)
     *  - 귀속 ledger 로 정확히 authority 재구성 가능 → [DriftCorrectionResult.Adjust] (leg 별 목표 = ledger 순매수량)
     *  - 그 외(ledger 합 ≠ authority — 모호) → [DriftCorrectionResult.WarnUnderflow]
     */
    fun evaluate(input: LedgerDriftInput): DriftCorrectionResult {
        if (!input.enabled) {
            return DriftCorrectionResult.Disabled
        }

        val authority = input.authorityQuantity.coerceAtLeast(0)
        val strategySum = input.legs.sumOf { it.quantity }

        // 부족 방향만 본 함수 책임. 초과·일치는 PositionDriftCorrection 이 처리하므로 개입 안 함.
        if (strategySum >= authority) {
            return DriftCorrectionResult.NoChange
        }

        // 귀속 ledger 순매수량 맵 (strategyId → net BUY-SELL FILLED, source=AUTO·strategyId≠0).
        val ledgerByStrategy = input.attributions.associate { it.strategyId to it.netFilledQuantity.coerceAtLeast(0) }

        // 보정 후보 — 현재 leg 들에 대해 목표 수량 = ledger 순매수량으로 set.
        // ledger 에 없는 전략 leg 는 현재 수량 유지(귀속 근거 없음 — 함부로 안 건드림).
        val targetByStrategy = mutableMapOf<Long, Int>()
        for (leg in input.legs) {
            targetByStrategy[leg.strategyId] = ledgerByStrategy[leg.strategyId] ?: leg.quantity
        }
        // 현재 leg 가 없지만 ledger 엔 순매수가 있는 전략(전형 incident — row 는 sentinel 병합으로 qty=0 존재 또는 부재).
        // 본 입력의 legs 는 user+code 전체이므로, ledger 에만 있고 legs 에 없는 전략은 신규 leg 생성 대상.
        for (attr in input.attributions) {
            if (attr.strategyId !in targetByStrategy && attr.netFilledQuantity > 0) {
                targetByStrategy[attr.strategyId] = attr.netFilledQuantity
            }
        }

        // 전체 정합 검산 — 보정 후 합이 authority 와 정확히 일치할 때만 정정(모호하면 WARN).
        val correctedSum = targetByStrategy.values.sum()
        if (correctedSum != authority) {
            return DriftCorrectionResult.WarnUnderflow(
                strategySum = strategySum,
                authorityQuantity = authority,
            )
        }

        // 실제 수량이 바뀌는 leg 만 corrections 로(멱등 — 이미 목표면 제외).
        val legQty = input.legs.associate { it.strategyId to it.quantity }
        val corrections =
            targetByStrategy
                .filter { (strategyId, target) -> (legQty[strategyId] ?: 0) != target }
                .map { (strategyId, target) ->
                    LegCorrection(
                        strategyId = strategyId,
                        previousQuantity = legQty[strategyId] ?: 0,
                        newQuantity = target,
                    )
                }

        // 바뀌는 leg 가 없으면(이미 정합 — 방어) NoChange.
        if (corrections.isEmpty()) {
            return DriftCorrectionResult.NoChange
        }

        return DriftCorrectionResult.Adjust(
            strategySum = strategySum,
            authorityQuantity = authority,
            corrections = corrections,
        )
    }
}

/**
 * [LedgerAttributedDriftCorrection.evaluate] 입력 — 모든 외부 상태 명시 주입(순수성 LOCK · [DriftCorrectionInput] 패턴 정합).
 *
 * @param code 종목 코드(audit·식별 메타 — 산정 로직 미사용).
 * @param authorityQuantity 권위 수량 = `position.quantity`(KIS 잔고 권위, ADR 0042). 음수면 0 절상.
 * @param legs 동일 user+code 의 현재 strategy_position leg 목록(다전략 N개·단일 1개·부재 빈목록).
 * @param attributions 전략별 귀속 ledger 순매수량(FILLED execution `sum(BUY)-sum(SELL)`, source=AUTO·strategyId≠0).
 *   R2 어댑터가 order_request⨝order_execution JPA 집계로 채운다. **외부·MANUAL 체결은 제외**(귀속 근거 없음 — 보수성 LOCK).
 * @param enabled 보정 활성 토글(ENV `magicjar.position.drift-auto-correct.enabled` 주입 — 초과 보정과 동일 키 공유).
 */
data class LedgerDriftInput(
    val code: String,
    val authorityQuantity: Int,
    val legs: List<StrategyPositionLeg>,
    val attributions: List<LedgerAttribution>,
    val enabled: Boolean = DriftCorrectionDefaults.AUTO_CORRECT_ENABLED,
)

/**
 * 전략 1개의 귀속 ledger 순매수량 — FILLED execution `sum(BUY qty) - sum(SELL qty)`.
 *
 * **귀속 근거 = order_request.strategy_id(≠0) + source=AUTO**. 외부(EXTERNAL)·MANUAL(strategyId=0) 체결은
 * 집계 단계(R2 JPA)에서 제외하므로 본 값은 "우리 자동매매 전략이 체결시킨 순보유량"이다(없는 물량 인식 risk 0).
 *
 * @param strategyId 귀속 전략.
 * @param netFilledQuantity 순매수 체결량(BUY-SELL). 음수 가능(SELL 과다 — 정상 청산 진행) → 산정 시 0 절상.
 */
data class LedgerAttribution(
    val strategyId: Long,
    val netFilledQuantity: Int,
)
