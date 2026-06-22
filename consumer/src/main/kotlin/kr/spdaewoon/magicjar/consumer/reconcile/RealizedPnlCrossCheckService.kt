package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderExecutionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderExecutionJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaRepository
import kr.spdaewoon.magicjar.domain.account.port.KisRealizedProfitItem
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
 * KIS 실현손익(TTTC8494R) ↔ 우리 FIFO 계산 크로스체크 서비스 (2026-06-22 market-data 신설).
 *
 * "우리 성과 숫자가 진짜 KIS 와 맞는가" 를 증명하는 프로덕션 토대. 우리 DB(order_execution/order_request)
 * FIFO 실현손익을 KIS 실제 실현손익과 종목별·계좌합계로 대조하고 drift·원인·일치판정을 반환한다.
 *
 * ### KIS-first (헌법 §3)
 * KIS 호출은 [KisRealizedProfitPort](KisRealizedProfitAdapter) 단일 경로. tr_id·필드·파라미터는 어댑터가
 * open-trading-api 공식 예제(`inquire_balance_rlz_pl`)에 정합. 본 서비스는 raw 결정을 하지 않는다.
 * rate limit 은 어댑터가 `KisCallPriority.BACKFILL` 로 보호 — 과호출 금지(reconcile/검증 경로).
 *
 * ### REAL 전용
 * KIS 실현손익 조회는 공식 예제 기준 모의 TR 부재 → REAL 만 의미. [crossCheck] 는 REAL 강제.
 *
 * ### 우리쪽 계산 정합 (api PerformanceService 동일 의미)
 * - FIFO 평균단가: 매수 시 비용(fee+tax)을 단가에 가산해 net 평단가 산정 → 매도 시 (체결가-평단가)*수량 - 매도비용 = net.
 * - **gross(비용 미차감)** 도 병행 산출: 매수 평단가는 순수 체결가만, 매도 시 (체결가-순평단가)*수량 = gross.
 *   KIS `COST_ICLD_YN=N`(비용 미포함) gross 와 직접 비교하기 위함.
 */
@Service
class RealizedPnlCrossCheckService(
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

        // ── KIS 실현손익 호출 ────────────────────────────────────────────────
        val kisSnapshot =
            try {
                kisRealizedProfitPort.fetchRealizedProfit(userId, TradingMode.REAL)
            } catch (e: Exception) {
                log.warn("KIS 실현손익 크로스체크 호출 실패 — userId={} err={}", maskUserId(userId), e.message)
                return failed(userId, validPeriod, from, to, ourNetTotal, ourGrossTotal, ourCostTotal, ourRows, e.message)
            }

        val kisByCode: Map<String, KisRealizedProfitItem> =
            kisSnapshot.items.associateBy { it.code }
        val kisAccount = kisSnapshot.summary?.realizedProfitLoss
        val kisRealEvalPnl = kisSnapshot.summary?.realEvaluationProfitLoss

        // ── 종목별 대조 (우리 ∪ KIS) ─────────────────────────────────────────
        val allCodes = (ourRows.keys + kisByCode.keys).toSortedSet()
        val perCode =
            allCodes.map { code ->
                val our = ourRows[code]
                val kis = kisByCode[code]
                val ourGross = our?.gross ?: BigDecimal.ZERO
                val ourNet = our?.net ?: BigDecimal.ZERO
                val kisPnl = kis?.realizedProfitLoss
                val drift = kisPnl?.let { it.subtract(ourGross) }
                RealizedPnlCrossCheckRow(
                    code = code,
                    name = kis?.name ?: our?.name,
                    kisRealizedPnl = kisPnl,
                    ourGrossRealizedPnl = ourGross.setScale(2, RoundingMode.HALF_UP),
                    ourNetRealizedPnl = ourNet.setScale(2, RoundingMode.HALF_UP),
                    driftGross = drift?.setScale(2, RoundingMode.HALF_UP),
                    presentInKis = kis != null,
                    matched = drift != null && drift.abs() <= TOLERANCE_WON,
                )
            }

        // ── 계좌합계 drift (gross 기준이 정합 비교) ────────────────────────────
        val accountDriftGross = kisAccount?.subtract(ourGrossTotal)?.setScale(2, RoundingMode.HALF_UP)
        val accountDriftNet = kisAccount?.subtract(ourNetTotal)?.setScale(2, RoundingMode.HALF_UP)
        val matched = accountDriftGross != null && accountDriftGross.abs() <= TOLERANCE_WON

        return RealizedPnlCrossCheck(
            userId = userId,
            period = validPeriod,
            periodStart = from,
            periodEnd = to,
            kisAccountRealizedPnl = kisAccount,
            kisRealEvaluationPnl = kisRealEvalPnl,
            ourNetRealizedPnl = ourNetTotal,
            ourGrossRealizedPnl = ourGrossTotal,
            ourTotalCost = ourCostTotal,
            accountDriftGross = accountDriftGross,
            accountDriftNet = accountDriftNet,
            toleranceWon = TOLERANCE_WON,
            matched = matched,
            driftCauses = classifyCauses(matched, accountDriftGross, ourCostTotal, perCode, kisAccount),
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
        accountDriftGross: BigDecimal?,
        ourCost: BigDecimal,
        perCode: List<RealizedPnlCrossCheckRow>,
        kisAccount: BigDecimal?,
    ): List<String> {
        if (matched) return emptyList()
        val causes = mutableListOf<String>()
        if (kisAccount == null) {
            causes += "KIS_SUMMARY_ABSENT: output2 실현손익 합계 부재 (보유 잔고 0 또는 응답 누락)"
            return causes
        }
        // 우리 net 으로 비교하면 비용만큼 차이 — KIS 가 gross 임을 환기
        if (ourCost.abs() > BigDecimal.ZERO) {
            causes += "COST_SEMANTICS: KIS COST_ICLD_YN=N(gross) vs 우리 net 비교 시 매도비용 ${ourCost.toPlainString()}원 차이 — gross 비교 사용"
        }
        // 전량 청산 종목이 KIS output1 에서 빠져 종목별 비교가 일부 불가
        val ourOnly = perCode.filter { !it.presentInKis && it.ourGrossRealizedPnl.signum() != 0 }
        if (ourOnly.isNotEmpty()) {
            causes += "KIS_ROW_ABSENT: 당일 전량 청산 등으로 KIS output1 잔고 행 부재 종목 ${ourOnly.map { it.code }} — 체결기준잔고(0800) 잔고행 기준 semantics"
        }
        if (accountDriftGross != null && accountDriftGross.abs() > TOLERANCE_WON) {
            causes +=
                "ACCOUNT_DRIFT_GROSS: 계좌합계 gross drift ${accountDriftGross.toPlainString()}원 (>${TOLERANCE_WON.toPlainString()}원) — 미체결 반영 지연·당일/누계·페이지네이션 점검 필요"
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
            kisAccountRealizedPnl = null,
            kisRealEvaluationPnl = null,
            ourNetRealizedPnl = ourNet,
            ourGrossRealizedPnl = ourGross,
            ourTotalCost = ourCost,
            accountDriftGross = null,
            accountDriftNet = null,
            toleranceWon = TOLERANCE_WON,
            matched = false,
            driftCauses = listOf("KIS_CALL_FAILED: ${errorMessage ?: "unknown"}"),
            perCode =
                ourRows.toSortedMap().map { (code, r) ->
                    RealizedPnlCrossCheckRow(
                        code = code,
                        name = r.name,
                        kisRealizedPnl = null,
                        ourGrossRealizedPnl = r.gross.setScale(2, RoundingMode.HALF_UP),
                        ourNetRealizedPnl = r.net.setScale(2, RoundingMode.HALF_UP),
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
