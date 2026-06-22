package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderExecutionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderExecutionJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaRepository
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitCodeAgg
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitPort
import kr.spdaewoon.magicjar.domain.account.port.KisRealizedProfitPort
import kr.spdaewoon.magicjar.domain.account.port.RealizedPnlCrossCheck
import kr.spdaewoon.magicjar.domain.account.port.RealizedPnlCrossCheckRow
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * KIS 실현손익 ↔ 우리 FIFO 계산 크로스체크 서비스 (2026-06-22 market-data · 개선 A로 TTTC8715R 1차 승격).
 *
 * "우리 성과 숫자가 진짜 KIS 와 맞는가" 를 증명하는 프로덕션 토대. 우리 DB(order_execution/order_request)
 * FIFO 실현손익을 KIS 실제 실현손익과 종목별·계좌합계로 대조하고 net/gross drift·원인·일치판정을 반환한다.
 *
 * ### 권위 endpoint (개선 A — TTTC8494R 한계 정정)
 * 1차: [KisPeriodTradeProfitPort] **TTTC8715R 기간별매매손익현황조회(HTS [0856] 종목별)** — 매도 발생 종목 전부
 *      (당일 전량청산 hldg_qty=0 포함) → "당일 총 실현손익" 권위. 직전 #1097 TTTC8494R(보유 행 기준) 의 drift root 해소.
 * 보조: [KisRealizedProfitPort] TTTC8494R — real_evaluation·체결기준잔고 참고용(실패 무시).
 *
 * ### KIS-first (헌법 §3)
 * KIS 호출은 어댑터 단일 경로. tr_id·필드·파라미터는 어댑터가 open-trading-api 공식 예제
 * (`inquire_period_trade_profit`)에 정합. 본 서비스는 raw 결정을 하지 않는다.
 * rate limit 은 어댑터가 `KisCallPriority.BACKFILL` 로 보호 — 과호출 금지(reconcile/검증 경로).
 *
 * ### REAL 전용
 * KIS 손익 조회는 공식 예제 기준 모의 TR 부재 → REAL 만 의미. [crossCheck] 는 REAL 강제.
 *
 * ### KIS 실비용 보정 net (개선 E — 2026-06-22, matched=true 종결)
 * 우리 order_execution.fee/tax=0(미캡처) → 우리 net=gross 라 KIS net 과 drift(실측 -5,878).
 * KIS rlzt_pfls 는 **매도 fee+tax(+매수 fee) 차감 net** 임이 실측 확정(종목별 driftNet = -(그 종목 KIS fee+tax) 정확 일치).
 * 추측 0 의 정확 비용 소스 = **TTTC8715R 종목별 fee·tl_tax + output2 buy_fee_smtl**(이미 같은 호출로 보유, 추가 KIS 호출 0).
 *  - 종목별: ourNetKisCost = 우리 gross - KIS(fee+tl_tax) → KIS rlzt_pfls 와 정합(매도비용 기준).
 *  - 계좌합계: ourNetKisCost = 우리 gross - (매도fee+매도tax+매수fee) → output2 tot_rlzt_pfls 와 정확 일치(잔차 0).
 *  - matched 판정 1차 축 = KIS_COST(periodDriftKisCost ≤ tolerance). 보조 = NET/GROSS(정의 모호 흡수).
 *
 * ### net/gross 보조 판정 (우리 fee/tax 캡처 시·KIS rlzt_pfls 정의 모호 흡수)
 * - FIFO 평균단가: 매수 시 비용(fee+tax)을 단가에 가산해 net 평단가 산정 → 매도 시 (체결가-평단가)*수량 - 매도비용 = net.
 * - gross(비용 미차감)도 병행 산출. 우리 fee/tax 미캡처면 net=gross → KIS_COST 축이 정확 보정.
 */
@Service
class RealizedPnlCrossCheckService(
    private val kisPeriodTradeProfitPort: KisPeriodTradeProfitPort,
    private val kisRealizedProfitPort: KisRealizedProfitPort,
    private val orderExecutionRepository: ConsumerOrderExecutionJpaRepository,
    private val orderRequestRepository: ConsumerOrderRequestJpaRepository,
) {
    private val log = LoggerFactory.getLogger(RealizedPnlCrossCheckService::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /**
     * KIS ↔ 우리 실현손익 크로스체크 1회 실행.
     *
     * @param userId user_account.user_id
     * @param period DAY | WEEK | MONTH | YEAR (range 산정 — KIS 는 당일 기준 단일 스냅샷이라 DAY 정밀)
     */
    fun crossCheck(
        userId: String,
        period: String,
    ): RealizedPnlCrossCheck {
        val validPeriod = period.uppercase()
        val (from, to) = resolveRange(validPeriod)

        // ── 우리쪽 FIFO replay (net + gross) ──────────────────────────────────
        val ourRows = computeOurRealizedPnl(userId, from, to)
        val ourNetTotal = ourRows.values.fold(BigDecimal.ZERO) { acc, r -> acc + r.net }.setScale(2, RoundingMode.HALF_UP)
        val ourGrossTotal = ourRows.values.fold(BigDecimal.ZERO) { acc, r -> acc + r.gross }.setScale(2, RoundingMode.HALF_UP)
        val ourCostTotal = ourRows.values.fold(BigDecimal.ZERO) { acc, r -> acc + r.cost }.setScale(2, RoundingMode.HALF_UP)

        // ── 1차 권위: TTTC8715R 기간별매매손익(전량청산 포함) ────────────────────
        val periodSnapshot =
            try {
                kisPeriodTradeProfitPort.fetchPeriodTradeProfit(userId, TradingMode.REAL, from, to)
            } catch (e: Exception) {
                log.warn("KIS 기간별매매손익(TTTC8715R) 크로스체크 호출 실패 — userId={} err={}", maskUserId(userId), e.message)
                return failed(userId, validPeriod, from, to, ourNetTotal, ourGrossTotal, ourCostTotal, ourRows, e.message)
            }
        val kisByCode: Map<String, KisPeriodTradeProfitCodeAgg> = periodSnapshot.byCode()
        val kisPeriodTotal = periodSnapshot.summary?.totalRealizedPnl
        val kisPeriodSellFee = periodSnapshot.summary?.sellFeeTotal
        val kisPeriodSellTax = periodSnapshot.summary?.sellTaxTotal
        // 개선 E: 매수수수료합계(buy_fee_smtl). KIS rlzt_pfls 가 매수fee 까지 반영 → 우리 gross 에서 추가 차감해 잔차 0.
        val kisPeriodBuyFee = periodSnapshot.summary?.buyFeeTotal

        // ── 보조: TTTC8494R 실평가손익 참고(보유 행 기준 — 실패해도 무시) ──────────
        val (kis8494Account, kisRealEvalPnl) =
            try {
                val snap = kisRealizedProfitPort.fetchRealizedProfit(userId, TradingMode.REAL)
                snap.summary?.realizedProfitLoss to snap.summary?.realEvaluationProfitLoss
            } catch (e: Exception) {
                log.info("TTTC8494R 보조 조회 생략(참고용) — userId={} err={}", maskUserId(userId), e.message)
                null to null
            }

        // ── 종목별 대조 (우리 ∪ KIS) ─────────────────────────────────────────
        // 개선 E: 우리 order_execution.fee/tax 미캡처(=0) → 우리 net=gross 라 KIS net 과 drift.
        //   종목별 우리 gross 에서 KIS 가 실제 부과한 fee+tl_tax 를 차감해 KIS rlzt_pfls 와 정합하는 net 도출(추측 0).
        val allCodes = (ourRows.keys + kisByCode.keys).toSortedSet()
        val perCode =
            allCodes.map { code ->
                val our = ourRows[code]
                val kis = kisByCode[code]
                val ourGross = our?.gross ?: BigDecimal.ZERO
                val ourNet = our?.net ?: BigDecimal.ZERO
                val kisPnl = kis?.realizedPnl
                // KIS 실비용 보정 net: 우리 gross - KIS(fee + tl_tax). KIS 행이 있을 때만 도출.
                val kisCost =
                    if (kis != null) {
                        (kis.fee ?: BigDecimal.ZERO).add(kis.tax ?: BigDecimal.ZERO)
                    } else {
                        null
                    }
                val ourNetKisCost = kisCost?.let { ourGross.subtract(it) }
                val driftNet = kisPnl?.subtract(ourNet)
                val driftGross = kisPnl?.subtract(ourGross)
                val driftKisCost = if (kisPnl != null && ourNetKisCost != null) kisPnl.subtract(ourNetKisCost) else null
                // 종목 일치: KIS 실비용 보정 net / net / gross 중 어느 한쪽이 tolerance 이내이면 일치.
                val rowMatched =
                    (driftKisCost != null && driftKisCost.abs() <= TOLERANCE_WON) ||
                        (driftNet != null && driftNet.abs() <= TOLERANCE_WON) ||
                        (driftGross != null && driftGross.abs() <= TOLERANCE_WON)
                RealizedPnlCrossCheckRow(
                    code = code,
                    name = kis?.name ?: our?.name,
                    kisRealizedPnl = kisPnl,
                    kisFee = kis?.fee,
                    kisTax = kis?.tax,
                    ourGrossRealizedPnl = ourGross.setScale(2, RoundingMode.HALF_UP),
                    ourNetRealizedPnl = ourNet.setScale(2, RoundingMode.HALF_UP),
                    ourNetKisCost = ourNetKisCost?.setScale(2, RoundingMode.HALF_UP),
                    driftNet = driftNet?.setScale(2, RoundingMode.HALF_UP),
                    driftGross = driftGross?.setScale(2, RoundingMode.HALF_UP),
                    driftKisCost = driftKisCost?.setScale(2, RoundingMode.HALF_UP),
                    presentInKis = kis != null,
                    matched = rowMatched,
                )
            }

        // ── 계좌합계: KIS 실비용 보정 net(1차) + net + gross 산출 ────────────────
        // 우리 gross 합계 - KIS 실비용(매도fee+매도tax) - 매수fee = KIS 총실현손익 정합 net.
        //   KIS rlzt_pfls(tot_rlzt_pfls)는 매수수수료까지 반영하므로 매수fee 도 차감해야 잔차 0.
        //   1순위: output2 합계(sell_fee+sell_tax+buy_fee). 부재 시 종목별 fee/tl_tax 합으로 폴백(매수fee 잔차 남을 수 있음).
        val kisSellCostByCode =
            perCode
                .filter { it.presentInKis }
                .fold(BigDecimal.ZERO) { acc, r ->
                    acc.add((r.kisFee ?: BigDecimal.ZERO).add(r.kisTax ?: BigDecimal.ZERO))
                }.setScale(2, RoundingMode.HALF_UP)
        // 매도비용 합계: output2(sell_fee_smtl+sell_tltx_smtl) 우선, 부재 시 종목별 합.
        val kisSellCostTotal =
            if (kisPeriodSellFee != null || kisPeriodSellTax != null) {
                (kisPeriodSellFee ?: BigDecimal.ZERO).add(kisPeriodSellTax ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
            } else {
                kisSellCostByCode
            }
        // 전체 KIS 비용 = 매도비용 + 매수fee(있으면). KIS rlzt_pfls 가 매수fee 반영 → 차감해야 정확 일치.
        val kisCostTotal = kisSellCostTotal.add(kisPeriodBuyFee ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
        val ourNetKisCostTotal =
            if (kisCallOk(periodSnapshot)) ourGrossTotal.subtract(kisCostTotal).setScale(2, RoundingMode.HALF_UP) else null

        val periodDriftNet = kisPeriodTotal?.subtract(ourNetTotal)?.setScale(2, RoundingMode.HALF_UP)
        val periodDriftGross = kisPeriodTotal?.subtract(ourGrossTotal)?.setScale(2, RoundingMode.HALF_UP)
        val periodDriftKisCost =
            if (kisPeriodTotal != null && ourNetKisCostTotal != null) {
                kisPeriodTotal.subtract(ourNetKisCostTotal).setScale(2, RoundingMode.HALF_UP)
            } else {
                null
            }
        val account8494DriftGross = kis8494Account?.subtract(ourGrossTotal)?.setScale(2, RoundingMode.HALF_UP)
        val account8494DriftNet = kis8494Account?.subtract(ourNetTotal)?.setScale(2, RoundingMode.HALF_UP)

        // matched 판정: KIS 실비용 보정 net 이 1차 축. net/gross 는 보조(우리 fee/tax 캡처 시·정의 모호 흡수).
        val kisCostOk = periodDriftKisCost != null && periodDriftKisCost.abs() <= TOLERANCE_WON
        val netOk = periodDriftNet != null && periodDriftNet.abs() <= TOLERANCE_WON
        val grossOk = periodDriftGross != null && periodDriftGross.abs() <= TOLERANCE_WON
        val matched = kisCostOk || netOk || grossOk
        val matchedDefinition =
            when {
                kisCostOk -> "KIS_COST"
                netOk && grossOk -> "BOTH"
                netOk -> "NET"
                grossOk -> "GROSS"
                else -> null
            }

        return RealizedPnlCrossCheck(
            userId = userId,
            period = validPeriod,
            periodStart = from,
            periodEnd = to,
            kisPeriodTotalRealizedPnl = kisPeriodTotal,
            kisPeriodSellFee = kisPeriodSellFee,
            kisPeriodSellTax = kisPeriodSellTax,
            kisAccountRealizedPnl = kis8494Account,
            kisRealEvaluationPnl = kisRealEvalPnl,
            ourNetRealizedPnl = ourNetTotal,
            ourNetKisCost = ourNetKisCostTotal,
            ourGrossRealizedPnl = ourGrossTotal,
            ourTotalCost = ourCostTotal,
            kisCostApplied = if (kisCallOk(periodSnapshot)) kisCostTotal else null,
            periodDriftNet = periodDriftNet,
            periodDriftGross = periodDriftGross,
            periodDriftKisCost = periodDriftKisCost,
            accountDriftGross = account8494DriftGross,
            accountDriftNet = account8494DriftNet,
            toleranceWon = TOLERANCE_WON,
            matched = matched,
            matchedDefinition = matchedDefinition,
            driftCauses =
                classifyCauses(
                    matched = matched,
                    periodDriftNet = periodDriftNet,
                    periodDriftGross = periodDriftGross,
                    periodDriftKisCost = periodDriftKisCost,
                    ourCost = ourCostTotal,
                    kisCostApplied = kisCostTotal,
                    kisBuyFee = kisPeriodBuyFee,
                    perCode = perCode,
                    kisPeriodTotal = kisPeriodTotal,
                    kisPeriodSellFee = kisPeriodSellFee,
                    kisPeriodSellTax = kisPeriodSellTax,
                ),
            perCode = perCode,
            kisCallOk = true,
        )
    }

    /** TTTC8715R 호출이 실제로 성공해 종목/합계가 의미를 갖는지 — KIS-cost net 산출 게이트. */
    private fun kisCallOk(snapshot: kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitSnapshot): Boolean =
        snapshot.summary != null || snapshot.rows.isNotEmpty()

    // ── 우리쪽 FIFO replay ─────────────────────────────────────────────────────

    private data class OurCodeResult(
        val name: String?,
        val gross: BigDecimal,
        val net: BigDecimal,
        val cost: BigDecimal,
    )

    private class PositionState(
        var qty: BigDecimal = BigDecimal.ZERO,
        var netAvg: BigDecimal = BigDecimal.ZERO,
        var grossAvg: BigDecimal = BigDecimal.ZERO,
    )

    /**
     * api PerformanceService.replayTradeBreakdown 와 동일 의미의 FIFO replay.
     * periodEnd 종료시점(to+1 자정 KST)까지 모든 체결을 replay 하되, periodStart~periodEnd 매도만 집계한다.
     */
    private fun computeOurRealizedPnl(
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): Map<String, OurCodeResult> {
        val cutoff = to.plusDays(1).atStartOfDay(kst).toInstant()
        val executions = orderExecutionRepository.findByExecutedAtBeforeOrderByExecutedAtAsc(cutoff)
        if (executions.isEmpty()) return emptyMap()

        val requestById: Map<Long, ConsumerOrderRequestJpaEntity> =
            orderRequestRepository
                .findAllById(executions.map { it.orderId }.distinct())
                .filter { it.id != null }
                .associateBy { it.id!! }

        // REAL + 해당 user 한정
        val filtered =
            executions.filter { exec ->
                val req = requestById[exec.orderId] ?: return@filter false
                req.mode == TradingMode.REAL && req.userId == userId
            }
        if (filtered.isEmpty()) return emptyMap()

        val positions = mutableMapOf<String, PositionState>()
        val accumGross = mutableMapOf<String, BigDecimal>()
        val accumNet = mutableMapOf<String, BigDecimal>()
        val accumCost = mutableMapOf<String, BigDecimal>()
        val names = mutableMapOf<String, String?>()

        for (exec in filtered) {
            val req = requestById[exec.orderId] ?: continue
            val code = req.code
            val state = positions.getOrPut(code) { PositionState() }
            names.putIfAbsent(code, null) // 종목명은 KIS 측에서 우선 사용

            when (req.side) {
                OrderSide.BUY -> applyBuy(state, exec)
                OrderSide.SELL -> {
                    val r = applySell(state, exec) ?: continue
                    val tradeDate = exec.executedAt.atZone(kst).toLocalDate()
                    if (tradeDate < from || tradeDate > to) continue
                    accumGross[code] = (accumGross[code] ?: BigDecimal.ZERO).add(r.gross)
                    accumNet[code] = (accumNet[code] ?: BigDecimal.ZERO).add(r.net)
                    accumCost[code] = (accumCost[code] ?: BigDecimal.ZERO).add(r.cost)
                }
            }
        }

        return accumGross.keys.associateWith { code ->
            OurCodeResult(
                name = names[code],
                gross = (accumGross[code] ?: BigDecimal.ZERO),
                net = (accumNet[code] ?: BigDecimal.ZERO),
                cost = (accumCost[code] ?: BigDecimal.ZERO),
            )
        }
    }

    private fun applyBuy(
        state: PositionState,
        exec: ConsumerOrderExecutionJpaEntity,
    ) {
        if (exec.executedQty <= BigDecimal.ZERO) return
        val nextQty = state.qty.add(exec.executedQty)
        // net 평단가: 비용을 단가에 가산
        val curNetCost = state.qty.multiply(state.netAvg)
        val addedNetCost =
            exec.executedQty
                .multiply(exec.executedPrice)
                .add(exec.fee)
                .add(exec.tax)
        // gross 평단가: 순수 체결가만
        val curGrossCost = state.qty.multiply(state.grossAvg)
        val addedGrossCost = exec.executedQty.multiply(exec.executedPrice)

        state.qty = nextQty
        if (nextQty.signum() > 0) {
            state.netAvg = curNetCost.add(addedNetCost).divide(nextQty, 8, RoundingMode.HALF_UP)
            state.grossAvg = curGrossCost.add(addedGrossCost).divide(nextQty, 8, RoundingMode.HALF_UP)
        } else {
            state.netAvg = BigDecimal.ZERO
            state.grossAvg = BigDecimal.ZERO
        }
    }

    private data class SellResult(
        val gross: BigDecimal,
        val net: BigDecimal,
        val cost: BigDecimal,
    )

    private fun applySell(
        state: PositionState,
        exec: ConsumerOrderExecutionJpaEntity,
    ): SellResult? {
        if (exec.executedQty <= BigDecimal.ZERO || state.qty <= BigDecimal.ZERO) return null
        val closedQty = minOf(exec.executedQty, state.qty)
        val expenseRatio = closedQty.divide(exec.executedQty, 8, RoundingMode.HALF_UP)
        val fee = exec.fee.multiply(expenseRatio)
        val tax = exec.tax.multiply(expenseRatio)
        val cost = fee.add(tax)

        // gross: 비용 미반영 (KIS COST_ICLD_YN=N 대조)
        val gross = exec.executedPrice.subtract(state.grossAvg).multiply(closedQty)
        // net: 매수 비용은 평단가에, 매도 비용은 차감
        val net =
            exec.executedPrice
                .subtract(state.netAvg)
                .multiply(closedQty)
                .subtract(cost)

        state.qty = state.qty.subtract(closedQty)
        if (state.qty <= BigDecimal.ZERO) {
            state.qty = BigDecimal.ZERO
            state.netAvg = BigDecimal.ZERO
            state.grossAvg = BigDecimal.ZERO
        }
        return SellResult(gross = gross, net = net, cost = cost)
    }

    // ── drift 원인 분류 ────────────────────────────────────────────────────────

    private fun classifyCauses(
        matched: Boolean,
        periodDriftNet: BigDecimal?,
        periodDriftGross: BigDecimal?,
        periodDriftKisCost: BigDecimal?,
        ourCost: BigDecimal,
        kisCostApplied: BigDecimal,
        kisBuyFee: BigDecimal?,
        perCode: List<RealizedPnlCrossCheckRow>,
        kisPeriodTotal: BigDecimal?,
        kisPeriodSellFee: BigDecimal?,
        kisPeriodSellTax: BigDecimal?,
    ): List<String> {
        val causes = mutableListOf<String>()
        if (kisPeriodTotal == null) {
            if (!matched) causes += "KIS_SUMMARY_ABSENT: TTTC8715R output2 총실현손익 부재 (해당 기간 매도 0 또는 응답 누락)"
            return causes
        }

        // 개선 E: KIS 실비용 보정 net 으로 일치한 경우 — 비용 구성을 숫자로 분해(matched 여도 투명 기록).
        if (periodDriftKisCost != null) {
            val residual = periodDriftKisCost
            val sellCost = (kisPeriodSellFee ?: BigDecimal.ZERO).add(kisPeriodSellTax ?: BigDecimal.ZERO)
            if (residual.signum() == 0) {
                causes +=
                    "MATCHED_KIS_COST: 우리 gross - KIS실비용(매도fee+tax=${sellCost.toPlainString()} + 매수fee=" +
                    "${(kisBuyFee ?: BigDecimal.ZERO).toPlainString()} = 총 ${kisCostApplied.toPlainString()}원) = KIS 총실현손익 정확 일치(잔차 0)"
            } else if (kisBuyFee == null) {
                // 매수fee(buy_fee_smtl) 부재 → 매수 수수료만큼 잔차. 숫자로 명시 분해.
                causes +=
                    "KIS_COST_RESIDUAL: 우리 gross - KIS매도비용(${sellCost.toPlainString()}원) 후 잔차=${residual.toPlainString()}원 " +
                    "— KIS rlzt_pfls 는 매수 수수료까지 반영(buy_fee_smtl 미수신). 이 잔차=매수fee 추정"
            } else {
                causes +=
                    "KIS_COST_RESIDUAL: 매도+매수비용(${kisCostApplied.toPlainString()}원) 차감 후 잔차=${residual.toPlainString()}원 " +
                    "— 반올림/세부 비용 차이 추정. 수동 점검 필요"
            }
        }

        if (matched) return causes

        // 우리 fee/tax 미캡처 → net=gross. KIS 가 net(비용 반영)이면 우리 net 도 KIS fee/tax 만큼 보정 필요.
        if (ourCost.signum() == 0 && (kisPeriodSellFee?.signum() == 1 || kisPeriodSellTax?.signum() == 1)) {
            val kisCost = (kisPeriodSellFee ?: BigDecimal.ZERO).add(kisPeriodSellTax ?: BigDecimal.ZERO)
            causes +=
                "OUR_COST_ZERO: 우리 order_execution.fee/tax=0(미캡처) → 우리 net=gross. " +
                "KIS 매도비용(fee+tax)=${kisCost.toPlainString()}원 → 우리 net 을 이만큼 차감 보정해야 KIS net 정합"
        }
        // net/gross 어느 정의가 더 가까운지 실측 분해
        if (periodDriftNet != null && periodDriftGross != null) {
            val closer = if (periodDriftNet.abs() <= periodDriftGross.abs()) "NET" else "GROSS"
            causes +=
                "DRIFT_BREAKDOWN: periodDriftNet=${periodDriftNet.toPlainString()}원, " +
                "periodDriftGross=${periodDriftGross.toPlainString()}원 — 더 가까운 정의=$closer (KIS rlzt_pfls 정의 실측)"
        }
        // TTTC8715R 은 매도 발생 종목 전부를 잡아야 정상 — 우리에 있는데 KIS 에 없으면 누락 신호
        val ourOnly = perCode.filter { !it.presentInKis && it.ourGrossRealizedPnl.signum() != 0 }
        if (ourOnly.isNotEmpty()) {
            causes += "KIS_ROW_ABSENT: TTTC8715R output1 에서 매도 종목 ${ourOnly.map { it.code }} 누락 — 조회기간·미체결 동기화 점검"
        }
        if (causes.isEmpty()) causes += "UNCLASSIFIED: drift 원인 미분류 — 수동 점검 필요"
        return causes
    }

    private fun resolveRange(period: String): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now(kst)
        return when (period) {
            "WEEK" -> today.with(java.time.DayOfWeek.MONDAY) to today
            "MONTH" -> today.withDayOfMonth(1) to today
            "YEAR" -> today.withDayOfYear(1) to today
            else -> today to today // DAY (KIS 당일 스냅샷 정밀 비교)
        }
    }

    private fun failed(
        userId: String,
        period: String,
        from: LocalDate,
        to: LocalDate,
        ourNet: BigDecimal,
        ourGross: BigDecimal,
        ourCost: BigDecimal,
        ourRows: Map<String, OurCodeResult>,
        errorMessage: String?,
    ): RealizedPnlCrossCheck =
        RealizedPnlCrossCheck(
            userId = userId,
            period = period,
            periodStart = from,
            periodEnd = to,
            kisPeriodTotalRealizedPnl = null,
            kisPeriodSellFee = null,
            kisPeriodSellTax = null,
            kisAccountRealizedPnl = null,
            kisRealEvaluationPnl = null,
            ourNetRealizedPnl = ourNet,
            ourNetKisCost = null,
            ourGrossRealizedPnl = ourGross,
            ourTotalCost = ourCost,
            kisCostApplied = null,
            periodDriftNet = null,
            periodDriftGross = null,
            periodDriftKisCost = null,
            accountDriftGross = null,
            accountDriftNet = null,
            toleranceWon = TOLERANCE_WON,
            matched = false,
            matchedDefinition = null,
            driftCauses = listOf("KIS_CALL_FAILED: ${errorMessage ?: "unknown"}"),
            perCode =
                ourRows.toSortedMap().map { (code, r) ->
                    RealizedPnlCrossCheckRow(
                        code = code,
                        name = r.name,
                        kisRealizedPnl = null,
                        kisFee = null,
                        kisTax = null,
                        ourGrossRealizedPnl = r.gross.setScale(2, RoundingMode.HALF_UP),
                        ourNetRealizedPnl = r.net.setScale(2, RoundingMode.HALF_UP),
                        ourNetKisCost = null,
                        driftNet = null,
                        driftGross = null,
                        driftKisCost = null,
                        presentInKis = false,
                        matched = false,
                    )
                },
            kisCallOk = false,
            errorMessage = errorMessage,
        )

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    companion object {
        /** tolerance 표준: 정확 일치 기대(원 단위). gross 비교에서 이 값 이하 drift 면 matched. */
        val TOLERANCE_WON: BigDecimal = BigDecimal("1")
    }
}
