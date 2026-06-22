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
 * ### net/gross 실측 판정 (KIS rlzt_pfls 정의 모호 흡수)
 * - FIFO 평균단가: 매수 시 비용(fee+tax)을 단가에 가산해 net 평단가 산정 → 매도 시 (체결가-평단가)*수량 - 매도비용 = net.
 * - gross(비용 미차감)도 병행 산출. KIS `rlzt_pfls` 가 net/gross 어느 정의인지 화면 명시 부족 →
 *   **둘 다 산출해 drift 가 0(또는 tolerance)인 쪽을 실측으로 확정**한다(matchedDefinition).
 * - 우리 order_execution.fee/tax=0(미캡처)이면 net=gross → KIS sll_fee_smtl·sll_tltx_smtl 로 보정 안내(OUR_COST_ZERO).
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
        val allCodes = (ourRows.keys + kisByCode.keys).toSortedSet()
        val perCode =
            allCodes.map { code ->
                val our = ourRows[code]
                val kis = kisByCode[code]
                val ourGross = our?.gross ?: BigDecimal.ZERO
                val ourNet = our?.net ?: BigDecimal.ZERO
                val kisPnl = kis?.realizedPnl
                val driftNet = kisPnl?.subtract(ourNet)
                val driftGross = kisPnl?.subtract(ourGross)
                // 종목 일치: net 또는 gross 어느 한쪽이 tolerance 이내이면 일치(KIS rlzt_pfls 정의 모호 흡수)
                val rowMatched =
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
                    driftNet = driftNet?.setScale(2, RoundingMode.HALF_UP),
                    driftGross = driftGross?.setScale(2, RoundingMode.HALF_UP),
                    presentInKis = kis != null,
                    matched = rowMatched,
                )
            }

        // ── 계좌합계 drift: net·gross 둘 다 산출, 작은 쪽을 실측 판정 ────────────
        val periodDriftNet = kisPeriodTotal?.subtract(ourNetTotal)?.setScale(2, RoundingMode.HALF_UP)
        val periodDriftGross = kisPeriodTotal?.subtract(ourGrossTotal)?.setScale(2, RoundingMode.HALF_UP)
        val account8494DriftGross = kis8494Account?.subtract(ourGrossTotal)?.setScale(2, RoundingMode.HALF_UP)
        val account8494DriftNet = kis8494Account?.subtract(ourNetTotal)?.setScale(2, RoundingMode.HALF_UP)

        val netOk = periodDriftNet != null && periodDriftNet.abs() <= TOLERANCE_WON
        val grossOk = periodDriftGross != null && periodDriftGross.abs() <= TOLERANCE_WON
        val matched = netOk || grossOk
        val matchedDefinition =
            when {
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
            ourGrossRealizedPnl = ourGrossTotal,
            ourTotalCost = ourCostTotal,
            periodDriftNet = periodDriftNet,
            periodDriftGross = periodDriftGross,
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
                    ourCost = ourCostTotal,
                    perCode = perCode,
                    kisPeriodTotal = kisPeriodTotal,
                    kisPeriodSellFee = kisPeriodSellFee,
                    kisPeriodSellTax = kisPeriodSellTax,
                ),
            perCode = perCode,
            kisCallOk = true,
        )
    }

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
        ourCost: BigDecimal,
        perCode: List<RealizedPnlCrossCheckRow>,
        kisPeriodTotal: BigDecimal?,
        kisPeriodSellFee: BigDecimal?,
        kisPeriodSellTax: BigDecimal?,
    ): List<String> {
        if (matched) return emptyList()
        val causes = mutableListOf<String>()
        if (kisPeriodTotal == null) {
            causes += "KIS_SUMMARY_ABSENT: TTTC8715R output2 총실현손익 부재 (해당 기간 매도 0 또는 응답 누락)"
            return causes
        }
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
            ourGrossRealizedPnl = ourGross,
            ourTotalCost = ourCost,
            periodDriftNet = null,
            periodDriftGross = null,
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
                        driftNet = null,
                        driftGross = null,
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
