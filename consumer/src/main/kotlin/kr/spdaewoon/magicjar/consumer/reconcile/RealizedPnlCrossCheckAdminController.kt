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
 *     — KIS 기간별매매손익(TTTC8715R, 1차 권위 — 전량청산 포함) + TTTC8494R(보조) 호출 + 우리 FIFO 계산 +
 *       net/gross drift·일치판정(matchedDefinition)·원인 반환.
 *
 * REAL 전용(KIS 손익 조회 모의 TR 부재). rate limit 은 어댑터가 BACKFILL priority 로 보호 — 과호출 금지.
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
            matchedDefinition = r.matchedDefinition,
            toleranceWon = r.toleranceWon,
            kisPeriodTotalRealizedPnl = r.kisPeriodTotalRealizedPnl,
            kisPeriodSellFee = r.kisPeriodSellFee,
            kisPeriodSellTax = r.kisPeriodSellTax,
            kisAccountRealizedPnl = r.kisAccountRealizedPnl,
            kisRealEvaluationPnl = r.kisRealEvaluationPnl,
            ourGrossRealizedPnl = r.ourGrossRealizedPnl,
            ourNetRealizedPnl = r.ourNetRealizedPnl,
            ourNetKisCost = r.ourNetKisCost,
            ourTotalCost = r.ourTotalCost,
            kisCostApplied = r.kisCostApplied,
            periodDriftNet = r.periodDriftNet,
            periodDriftGross = r.periodDriftGross,
            periodDriftKisCost = r.periodDriftKisCost,
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
                        kisFee = row.kisFee,
                        kisTax = row.kisTax,
                        ourGrossRealizedPnl = row.ourGrossRealizedPnl,
                        ourNetRealizedPnl = row.ourNetRealizedPnl,
                        ourNetKisCost = row.ourNetKisCost,
                        driftNet = row.driftNet,
                        driftGross = row.driftGross,
                        driftKisCost = row.driftKisCost,
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
        /** 계좌합계 drift(KIS실비용net/net/gross 중 작은 쪽)가 tolerance 이내인지. */
        val matched: Boolean,
        /** 일치한 정의(KIS_COST|NET|GROSS|BOTH) — KIS rlzt_pfls 정의 실측 결과. KIS_COST=KIS 실비용 보정 net(개선 E). */
        val matchedDefinition: String?,
        val toleranceWon: BigDecimal,
        /** TTTC8715R output2 총실현손익(tot_rlzt_pfls) — 1차 권위(전량청산 포함). */
        val kisPeriodTotalRealizedPnl: BigDecimal?,
        /** TTTC8715R output2 매도수수료합계(sll_fee_smtl). */
        val kisPeriodSellFee: BigDecimal?,
        /** TTTC8715R output2 매도제세금합계(sll_tltx_smtl). */
        val kisPeriodSellTax: BigDecimal?,
        /** TTTC8494R output2 실현손익 합계(보유 행 기준 — 참고). */
        val kisAccountRealizedPnl: BigDecimal?,
        val kisRealEvaluationPnl: BigDecimal?,
        /** 우리 gross 실현손익(비용 미차감). */
        val ourGrossRealizedPnl: BigDecimal,
        /** 우리 net 실현손익(우리 order_execution.fee/tax 차감 — 성과 API 와 동일 정의). */
        val ourNetRealizedPnl: BigDecimal,
        /** 우리 gross - KIS 실비용(fee+tl_tax) = KIS 실비용 보정 net (개선 E — matched 1차 축). */
        val ourNetKisCost: BigDecimal?,
        val ourTotalCost: BigDecimal,
        /** ourNetKisCost 도출에 차감한 KIS 실비용 합계(종목별 fee+tl_tax). */
        val kisCostApplied: BigDecimal?,
        /** TTTC8715R 총실현손익 - 우리 net(우리 fee/tax 기준). */
        val periodDriftNet: BigDecimal?,
        /** TTTC8715R 총실현손익 - 우리 gross. */
        val periodDriftGross: BigDecimal?,
        /** TTTC8715R 총실현손익 - 우리 KIS실비용 보정 net (개선 E — 0 수렴 기대, 매수fee 잔차 제외). */
        val periodDriftKisCost: BigDecimal?,
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
        val kisFee: BigDecimal?,
        val kisTax: BigDecimal?,
        val ourGrossRealizedPnl: BigDecimal,
        val ourNetRealizedPnl: BigDecimal,
        /** 우리 gross - KIS(fee+tl_tax) — KIS 실비용 보정 net (개선 E). */
        val ourNetKisCost: BigDecimal?,
        val driftNet: BigDecimal?,
        val driftGross: BigDecimal?,
        /** KIS - ourNetKisCost (개선 E — 0 수렴 기대). */
        val driftKisCost: BigDecimal?,
        val presentInKis: Boolean,
        val matched: Boolean,
    )
}
