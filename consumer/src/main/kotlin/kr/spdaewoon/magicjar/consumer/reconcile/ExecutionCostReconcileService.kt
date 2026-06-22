package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderExecutionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderExecutionJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaRepository
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitCodeAgg
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitPort
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * 거래비용(fee/tax) 사후 영속 reconcile 서비스 — 손익 net화 (follow-up H, 2026-06-22 market-data).
 *
 * ### 왜 필요한가 (근본 원인)
 * KIS WS 체결통보(H0STCNI0)·REST 일별체결(inquire-daily-ccld)은 **최종 수수료·제세금을 싣지 않는다** →
 * `order_execution.fee/tax` 가 0으로 INSERT 된다. 그 결과 PerformanceService FIFO 가 net=gross 를 내고,
 * `/api/performance/summary`·`/api/autopilot/performance` 대시보드가 **gross 과대표시**(실측 +27,420 vs KIS net 21,542)된다.
 *
 * ### 해결: KIS 실비용을 체결 단위로 안분 영속 (추측 0)
 * KIS 기간별매매손익(TTTC8715R)이 종목별 실제 부과 fee·tl_tax(매도비용)를 반환한다(개선 E 에서 크로스체크가 이미
 * "우리 gross - KIS(fee+tl_tax) = KIS rlzt_pfls" 정합을 실측 확정). 본 서비스는 그 종목별 KIS 비용을
 * **기간 내 매도 체결에 체결금액 비례로 안분**해 `order_execution.fee/tax` 를 채운다. 이후 PerformanceService FIFO 가
 * **자동으로 net** 을 낸다(소비처 무변경 — 비용은 단가/실현손익에 이미 반영하는 기존 로직 재사용).
 *
 * ### KIS-first (헌법 §3)
 * KIS 호출은 [KisPeriodTradeProfitPort](TTTC8715R) 어댑터 단일 경로 — tr_id·필드·파라미터 raw 결정은 어댑터 책임.
 * 본 서비스는 raw 결정 0. rate limit 은 어댑터가 `KisCallPriority.BACKFILL` 로 보호(reconcile 경로, 과호출 금지).
 *
 * ### 멱등·자본 안전
 *  - `fee = 0 AND tax = 0` 인 행만 UPDATE([ConsumerOrderExecutionJpaRepository.fillCostIfAbsent]) → 재실행해도 중복 차감 0.
 *  - 매도 체결만 대상(tax 0.18% 는 매도분 부과 — 2024+ well-defined). 매수 수수료는 평단가 영향 미미 + KIS rlzt_pfls
 *    매도비용 기준 정합이라 1차 범위 제외(개선 E 와 동일 정의).
 *  - 자본 주문 경로 무영향 — 손익 표시 정확화(비용 사후 기록)만. read-then-update, 주문·포지션 변경 0.
 *
 * ### REAL 전용
 * KIS 손익 조회는 공식 예제 기준 모의 TR 부재 → REAL 만 의미. 본 서비스는 REAL 체결만 대상으로 한다.
 */
@Service
class ExecutionCostReconcileService(
    private val kisPeriodTradeProfitPort: KisPeriodTradeProfitPort,
    private val orderExecutionRepository: ConsumerOrderExecutionJpaRepository,
    private val orderRequestRepository: ConsumerOrderRequestJpaRepository,
) {
    private val log = LoggerFactory.getLogger(ExecutionCostReconcileService::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /**
     * 기간 [from, to] 내 REAL 매도 체결의 fee/tax 를 KIS 실비용으로 안분 영속한다.
     *
     * @param userId user_account.user_id
     * @param from·to 조회·보정 기간(KST). KIS TTTC8715R 는 INQR_STRT_DT~INQR_END_DT 로 그대로 매핑.
     * @return reconcile 결과(종목수·UPDATE 행수·적용 비용 합계).
     */
    @Transactional
    fun reconcile(
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): ExecutionCostReconcileResult {
        // ── KIS 종목별 실비용 fetch (TTTC8715R) ───────────────────────────────
        val kisByCode: Map<String, KisPeriodTradeProfitCodeAgg> =
            try {
                kisPeriodTradeProfitPort.fetchPeriodTradeProfit(userId, TradingMode.REAL, from, to).byCode()
            } catch (e: Exception) {
                log.warn("비용 reconcile — KIS 기간별매매손익(TTTC8715R) 호출 실패: userId={} err={}", maskUserId(userId), e.message)
                return ExecutionCostReconcileResult.failed(userId, from, to, e.message)
            }
        if (kisByCode.isEmpty()) {
            log.info("비용 reconcile — KIS 매도 종목 0건(해당 기간 매도 없음): userId={} [{}~{}]", maskUserId(userId), from, to)
            return ExecutionCostReconcileResult.empty(userId, from, to)
        }

        // ── 기간 내 REAL 매도 체결 로드 ────────────────────────────────────────
        val fromInstant = from.atStartOfDay(kst).toInstant()
        val toInstant = to.plusDays(1).atStartOfDay(kst).toInstant()
        val executions =
            orderExecutionRepository.findByExecutedAtGreaterThanEqualAndExecutedAtLessThanOrderByExecutedAtAsc(fromInstant, toInstant)
        if (executions.isEmpty()) {
            log.info("비용 reconcile — 기간 내 체결 0건: userId={} [{}~{}]", maskUserId(userId), from, to)
            return ExecutionCostReconcileResult.empty(userId, from, to)
        }

        val requestById: Map<Long, ConsumerOrderRequestJpaEntity> =
            orderRequestRepository
                .findAllById(executions.map { it.orderId }.distinct())
                .filter { it.id != null }
                .associateBy { it.id!! }

        // REAL + 해당 user + 매도만 종목별 그룹
        val sellsByCode: Map<String, List<ConsumerOrderExecutionJpaEntity>> =
            executions
                .mapNotNull { exec ->
                    val req = requestById[exec.orderId] ?: return@mapNotNull null
                    if (req.mode != TradingMode.REAL || req.userId != userId) return@mapNotNull null
                    if (req.side != OrderSide.SELL) return@mapNotNull null
                    if (exec.executedQty <= BigDecimal.ZERO) return@mapNotNull null
                    req.code to exec
                }.groupBy({ it.first }, { it.second })

        // ── 종목별 안분 + UPDATE ───────────────────────────────────────────────
        var updatedRows = 0
        var skippedRows = 0
        var appliedFee = BigDecimal.ZERO
        var appliedTax = BigDecimal.ZERO
        var codesWithKisCost = 0

        sellsByCode.forEach { (code, sells) ->
            val kis = kisByCode[code] ?: return@forEach
            val kisFee = kis.fee ?: BigDecimal.ZERO
            val kisTax = kis.tax ?: BigDecimal.ZERO
            if (kisFee.signum() == 0 && kisTax.signum() == 0) return@forEach
            codesWithKisCost++

            val allocations = allocate(sells, kisFee, kisTax)
            allocations.forEach { (exec, fee, tax) ->
                val id = exec.id ?: return@forEach
                val updated = orderExecutionRepository.fillCostIfAbsent(id, fee, tax)
                if (updated > 0) {
                    updatedRows++
                    appliedFee = appliedFee.add(fee)
                    appliedTax = appliedTax.add(tax)
                } else {
                    skippedRows++
                }
            }
        }

        log.info(
            "비용 reconcile 완료 — userId={} [{}~{}] KIS종목={} 비용종목={} UPDATE={} skip(이미채움)={} appliedFee={} appliedTax={}",
            maskUserId(userId),
            from,
            to,
            kisByCode.size,
            codesWithKisCost,
            updatedRows,
            skippedRows,
            appliedFee.toPlainString(),
            appliedTax.toPlainString(),
        )

        return ExecutionCostReconcileResult(
            userId = userId,
            from = from,
            to = to,
            kisCallOk = true,
            kisCodeCount = kisByCode.size,
            costCodeCount = codesWithKisCost,
            updatedRows = updatedRows,
            skippedRows = skippedRows,
            appliedFee = appliedFee.setScale(2, RoundingMode.HALF_UP),
            appliedTax = appliedTax.setScale(2, RoundingMode.HALF_UP),
            errorMessage = null,
        )
    }

    /**
     * 종목 KIS 비용(fee·tax 합계)을 매도 체결에 **체결금액(executedQty×executedPrice) 비례**로 안분한다.
     *
     * 마지막 체결에 잔여(반올림 합산 오차)를 몰아 합계가 KIS 비용과 정확히 일치하도록 보정한다(잔차 0).
     * 체결금액 합계가 0이면(이론상 없음) 균등 안분으로 폴백.
     */
    internal fun allocate(
        sells: List<ConsumerOrderExecutionJpaEntity>,
        kisFee: BigDecimal,
        kisTax: BigDecimal,
    ): List<CostAllocation> {
        val amounts = sells.map { it.executedQty.multiply(it.executedPrice) }
        val totalAmount = amounts.fold(BigDecimal.ZERO, BigDecimal::add)
        val n = sells.size

        var accFee = BigDecimal.ZERO
        var accTax = BigDecimal.ZERO
        val result = ArrayList<CostAllocation>(n)
        for (i in sells.indices) {
            val last = i == n - 1
            val fee: BigDecimal
            val tax: BigDecimal
            if (last) {
                // 잔여 몰아주기 — 합계 = KIS 비용 정확 일치(반올림 잔차 0).
                fee = kisFee.subtract(accFee)
                tax = kisTax.subtract(accTax)
            } else {
                val ratio =
                    if (totalAmount.signum() > 0) {
                        amounts[i].divide(totalAmount, 12, RoundingMode.HALF_UP)
                    } else {
                        BigDecimal.ONE.divide(BigDecimal(n), 12, RoundingMode.HALF_UP)
                    }
                fee = kisFee.multiply(ratio).setScale(4, RoundingMode.HALF_UP)
                tax = kisTax.multiply(ratio).setScale(4, RoundingMode.HALF_UP)
                accFee = accFee.add(fee)
                accTax = accTax.add(tax)
            }
            result += CostAllocation(sells[i], fee.setScale(4, RoundingMode.HALF_UP), tax.setScale(4, RoundingMode.HALF_UP))
        }
        return result
    }

    internal data class CostAllocation(
        val execution: ConsumerOrderExecutionJpaEntity,
        val fee: BigDecimal,
        val tax: BigDecimal,
    )

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}

/**
 * 거래비용 reconcile 결과.
 *
 * @property kisCodeCount KIS TTTC8715R 가 반환한 매도 종목 수.
 * @property costCodeCount 그중 fee·tax 비용이 있어 안분 대상이 된 종목 수.
 * @property updatedRows 실제 fee/tax 를 채운 체결 행 수(멱등 — fee=0&tax=0 행만).
 * @property skippedRows 이미 비용이 채워져 skip 된 행 수.
 * @property appliedFee·appliedTax 이번 실행에서 새로 영속한 비용 합계.
 */
data class ExecutionCostReconcileResult(
    val userId: String,
    val from: LocalDate,
    val to: LocalDate,
    val kisCallOk: Boolean,
    val kisCodeCount: Int,
    val costCodeCount: Int,
    val updatedRows: Int,
    val skippedRows: Int,
    val appliedFee: BigDecimal,
    val appliedTax: BigDecimal,
    val errorMessage: String?,
) {
    companion object {
        fun empty(
            userId: String,
            from: LocalDate,
            to: LocalDate,
        ): ExecutionCostReconcileResult =
            ExecutionCostReconcileResult(userId, from, to, true, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null)

        fun failed(
            userId: String,
            from: LocalDate,
            to: LocalDate,
            errorMessage: String?,
        ): ExecutionCostReconcileResult =
            ExecutionCostReconcileResult(userId, from, to, false, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, errorMessage)
    }
}
