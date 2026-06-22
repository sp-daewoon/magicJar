package kr.spdaewoon.magicjar.consumer.reconcile

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 거래비용 reconcile admin REST endpoint — 손익 net화 (follow-up H, 2026-06-22 market-data).
 *
 * consumer 모듈 내부 REST(port 8082). 당일/특정 기간 매도 체결의 fee/tax 를 KIS 실비용으로 즉시 안분 영속한다.
 * cron(평일 16:30) 외에 운영자가 과거 기간 backfill·즉시 보정을 호출하는 훅.
 *
 * endpoint:
 *   POST /admin/reconcile/execution-cost?from=YYYY-MM-DD&to=YYYY-MM-DD&userId=
 *     — from·to 미지정 시 오늘. userId 미지정 시 REAL 자격증명 전체.
 *
 * REAL 전용(KIS 손익 TR 모의 부재). 멱등(fee=0&tax=0 행만 UPDATE) — 자본 영향 0(손익 표시 정확화만).
 */
@RestController
@RequestMapping("/admin/reconcile")
class ExecutionCostReconcileAdminController(
    private val executionCostReconcileService: ExecutionCostReconcileService,
    private val executionCostReconcileWorker: ExecutionCostReconcileWorker,
) {
    private val log = LoggerFactory.getLogger(ExecutionCostReconcileAdminController::class.java)

    @PostMapping("/execution-cost")
    fun reconcile(
        @RequestParam(required = false) userId: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): ResponseEntity<ExecutionCostReconcileBatchResponse> {
        val today = LocalDate.now()
        val fromDate = from?.let(LocalDate::parse) ?: today
        val toDate = to?.let(LocalDate::parse) ?: today
        log.info("admin execution-cost reconcile — userId={} [{}~{}]", userId?.let(::mask) ?: "ALL", fromDate, toDate)

        val results =
            if (userId != null) {
                listOf(executionCostReconcileService.reconcile(userId, fromDate, toDate))
            } else {
                executionCostReconcileWorker.runForAllRealUsers(fromDate, toDate)
            }
        return ResponseEntity.ok(toBatchResponse(fromDate, toDate, results))
    }

    private fun toBatchResponse(
        from: LocalDate,
        to: LocalDate,
        results: List<ExecutionCostReconcileResult>,
    ): ExecutionCostReconcileBatchResponse =
        ExecutionCostReconcileBatchResponse(
            from = from.toString(),
            to = to.toString(),
            userCount = results.size,
            totalUpdatedRows = results.sumOf { it.updatedRows },
            totalSkippedRows = results.sumOf { it.skippedRows },
            totalAppliedFee = results.fold(BigDecimal.ZERO) { acc, r -> acc + r.appliedFee },
            totalAppliedTax = results.fold(BigDecimal.ZERO) { acc, r -> acc + r.appliedTax },
            results =
                results.map { r ->
                    ExecutionCostReconcileRow(
                        userId = mask(r.userId),
                        kisCallOk = r.kisCallOk,
                        kisCodeCount = r.kisCodeCount,
                        costCodeCount = r.costCodeCount,
                        updatedRows = r.updatedRows,
                        skippedRows = r.skippedRows,
                        appliedFee = r.appliedFee,
                        appliedTax = r.appliedTax,
                        errorMessage = r.errorMessage,
                    )
                },
        )

    private fun mask(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    data class ExecutionCostReconcileBatchResponse(
        val from: String,
        val to: String,
        val userCount: Int,
        val totalUpdatedRows: Int,
        val totalSkippedRows: Int,
        val totalAppliedFee: BigDecimal,
        val totalAppliedTax: BigDecimal,
        val results: List<ExecutionCostReconcileRow>,
    )

    data class ExecutionCostReconcileRow(
        val userId: String,
        val kisCallOk: Boolean,
        val kisCodeCount: Int,
        val costCodeCount: Int,
        val updatedRows: Int,
        val skippedRows: Int,
        val appliedFee: BigDecimal,
        val appliedTax: BigDecimal,
        val errorMessage: String?,
    )
}
