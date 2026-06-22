package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.domain.account.port.RealizedPnlCrossCheck
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/**
 * KIS 실현손익 ↔ 우리 FIFO 계산 크로스체크 admin REST endpoint (2026-06-22 market-data 신설).
 *
 * consumer 모듈 내부 REST (port 8082). "우리 숫자가 진짜 KIS 와 맞는가" 를 즉시 호출로 증명하는 토대.
 * 에이전틱 모니터링/검증이 "애매하면 KIS 와 대조" 표준으로 호출하는 훅.
 *
 * endpoint:
 *   GET /admin/reconcile/realized-pnl?userId=&period=DAY
 *     — KIS 실현손익(TTTC8494R) 호출 + 우리 FIFO 계산 + drift·일치판정·원인 반환.
 *
 * REAL 전용(KIS 실현손익 모의 TR 부재). rate limit 은 어댑터가 BACKFILL priority 로 보호 — 과호출 금지.
 * 자본 영향 0 — read-only 검증만 수행(주문·포지션 변경 0).
 */
@RestController
@RequestMapping("/admin/reconcile")
class RealizedPnlCrossCheckAdminController(
    private val realizedPnlCrossCheckService: RealizedPnlCrossCheckService,
) {
    private val log = LoggerFactory.getLogger(RealizedPnlCrossCheckAdminController::class.java)

    @GetMapping("/realized-pnl")
    fun crossCheck(
        @RequestParam userId: String,
        @RequestParam(defaultValue = "DAY") period: String,
    ): ResponseEntity<RealizedPnlCrossCheckResponse> {
        log.info("admin realized-pnl 크로스체크 — userId={} period={}", maskUserId(userId), period)
        val result = realizedPnlCrossCheckService.crossCheck(userId, period)
        return ResponseEntity.ok(toResponse(result))
    }

    private fun toResponse(r: RealizedPnlCrossCheck): RealizedPnlCrossCheckResponse =
        RealizedPnlCrossCheckResponse(
            userId = maskUserId(r.userId),
            period = r.period,
            periodStart = r.periodStart.toString(),
            periodEnd = r.periodEnd.toString(),
            kisCallOk = r.kisCallOk,
            matched = r.matched,
            toleranceWon = r.toleranceWon,
            kisAccountRealizedPnl = r.kisAccountRealizedPnl,
            kisRealEvaluationPnl = r.kisRealEvaluationPnl,
            ourGrossRealizedPnl = r.ourGrossRealizedPnl,
            ourNetRealizedPnl = r.ourNetRealizedPnl,
            ourTotalCost = r.ourTotalCost,
            accountDriftGross = r.accountDriftGross,
            accountDriftNet = r.accountDriftNet,
            driftCauses = r.driftCauses,
            errorMessage = r.errorMessage,
            checkedAt = r.checkedAt.toString(),
            perCode =
                r.perCode.map { row ->
                    PerCodeRow(
                        code = row.code,
                        name = row.name,
                        kisRealizedPnl = row.kisRealizedPnl,
                        ourGrossRealizedPnl = row.ourGrossRealizedPnl,
                        ourNetRealizedPnl = row.ourNetRealizedPnl,
                        driftGross = row.driftGross,
                        presentInKis = row.presentInKis,
                        matched = row.matched,
                    )
                },
        )

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    data class RealizedPnlCrossCheckResponse(
        val userId: String,
        val period: String,
        val periodStart: String,
        val periodEnd: String,
        /** KIS 호출 성공(rt_cd=0) 여부. */
        val kisCallOk: Boolean,
        /** gross 계좌합계 drift 가 tolerance 이내인지. */
        val matched: Boolean,
        val toleranceWon: BigDecimal,
        /** KIS output2 실현손익 합계(gross, COST_ICLD_YN=N). */
        val kisAccountRealizedPnl: BigDecimal?,
        val kisRealEvaluationPnl: BigDecimal?,
        /** 우리 gross 실현손익(비용 미차감 — KIS 와 직접 비교). */
        val ourGrossRealizedPnl: BigDecimal,
        /** 우리 net 실현손익(비용 차감 — 성과 API 와 동일 정의). */
        val ourNetRealizedPnl: BigDecimal,
        val ourTotalCost: BigDecimal,
        val accountDriftGross: BigDecimal?,
        val accountDriftNet: BigDecimal?,
        val driftCauses: List<String>,
        val errorMessage: String?,
        val checkedAt: String,
        val perCode: List<PerCodeRow>,
    )

    data class PerCodeRow(
        val code: String,
        val name: String?,
        val kisRealizedPnl: BigDecimal?,
        val ourGrossRealizedPnl: BigDecimal,
        val ourNetRealizedPnl: BigDecimal,
        val driftGross: BigDecimal?,
        val presentInKis: Boolean,
        val matched: Boolean,
    )
}
