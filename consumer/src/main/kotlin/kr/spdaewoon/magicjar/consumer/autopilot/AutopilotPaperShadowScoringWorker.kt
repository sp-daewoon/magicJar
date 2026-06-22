package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaRepository
import kr.spdaewoon.magicjar.strategy.autopilot.AutopilotPaperShadowScorer
import kr.spdaewoon.magicjar.strategy.autopilot.ShadowOutcome
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * autopilot **페이퍼 섀도 스코어링 워커** (개선 G · 슬롯 공정성 · paper-parallel 점수 산정).
 *
 * ## 책임 (단일)
 * 미결(OPEN) `autopilot_paper_shadow` 행을 주기적으로 순회하며, 진입 **이후** 캔들의 high/low 가 TP/SL 에
 * 도달했는지를 [AutopilotPaperShadowScorer](순수 함수)로 판정해 WIN/LOSS 로 마킹 + net 가상 PnL 누적한다.
 * 미도달이면 OPEN 유지(다음 주기 재평가). 이로써 starve 된 102·103 같은 가설도 점수가 쌓여 학습 신호가 된다.
 *
 * ## 자본 안전 (LOCK)
 * 실 capital·order_request·strategy_position·KIS 를 **절대 만지지 않는다** — candle(읽기) + autopilot_paper_shadow
 * (장부)만 접근. 실보유 동시 cap 3 불변(섀도는 자본 0).
 *
 * ## 게이트 (spec §3-bis Tier A)
 * `magicjar.autopilot.enabled=false`(default)면 OPEN 섀도가 애초에 0건이라 no-op. toggle 로도 워커를 끌 수 있다
 * (`magicjar.autopilot.paper-shadow.scoring.enabled`, default true). 평상시 OPEN 0건이면 즉시 종료(부담 0).
 *
 * ## cron (fixedDelay 60초)
 * 단타 3m·5m 캔들이 마감되는 주기 대비 충분히 촘촘. 한 주기 batch 상한([batchSize])으로 cap.
 *
 * // AI 호출 0 — 룰 기반 결정론 스코어링 (ADR 0023 §2-4 정합)
 */
@Component
class AutopilotPaperShadowScoringWorker(
    private val properties: MagicjarAutopilotProperties,
    private val shadowRepository: AutopilotPaperShadowJpaRepository,
    private val candleRepository: ConsumerCandleJpaRepository,
    @Value("\${magicjar.autopilot.paper-shadow.scoring.enabled:true}")
    private val scoringEnabled: Boolean = true,
    @Value("\${magicjar.autopilot.paper-shadow.scoring.batch-size:200}")
    private val batchSize: Int = 200,
    @Value("\${magicjar.autopilot.paper-shadow.scoring.lookahead-bars:500}")
    private val lookaheadBars: Int = 500,
) {
    private val log = LoggerFactory.getLogger(AutopilotPaperShadowScoringWorker::class.java)

    /**
     * 60초 cron — OPEN 섀도 batch 스코어링. autopilot disabled 또는 toggle off 면 즉시 종료.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    fun scoreOpenShadows() {
        if (!properties.enabled || !scoringEnabled) return

        val open = shadowRepository.findOpen(PageRequest.of(0, batchSize.coerceAtLeast(1)))
        if (open.isEmpty()) return

        var resolved = 0
        var wins = 0
        var losses = 0
        for (shadow in open) {
            try {
                val bars =
                    candleRepository
                        .findByCodeAndIntervalAfter(
                            code = shadow.code,
                            interval = shadow.interval,
                            entryExclusive = shadow.entryTs,
                            pageable = PageRequest.of(0, lookaheadBars.coerceAtLeast(1)),
                        ).mapNotNull { entity ->
                            val high = entity.highRaw ?: return@mapNotNull null
                            val low = entity.lowRaw ?: return@mapNotNull null
                            AutopilotPaperShadowScorer.Bar(high = high, low = low, ts = entity.ts)
                        }

                when (
                    val outcome =
                        AutopilotPaperShadowScorer.score(
                            entryPrice = shadow.entryPrice,
                            takeProfitPct = shadow.takeProfitPct,
                            stopLossPct = shadow.stopLossPct,
                            subsequentBars = bars,
                        )
                ) {
                    is ShadowOutcome.Win -> {
                        markResolved(shadow, AutopilotPaperShadowJpaEntity.STATUS_WIN, outcome.exitPrice, outcome.hitBarTs)
                        wins++
                        resolved++
                    }
                    is ShadowOutcome.Loss -> {
                        markResolved(shadow, AutopilotPaperShadowJpaEntity.STATUS_LOSS, outcome.exitPrice, outcome.hitBarTs)
                        losses++
                        resolved++
                    }
                    ShadowOutcome.Open -> Unit // 미도달 — 다음 주기 재평가.
                }
            } catch (e: Exception) {
                log.warn(
                    "페이퍼 섀도 스코어링 1건 실패(skip — 다음 주기 재시도): shadowId={} code={} err={}",
                    shadow.id,
                    shadow.code,
                    e.message,
                )
            }
        }

        if (resolved > 0) {
            log.info(
                "페이퍼 섀도 스코어링: open={} resolved={} (win={} loss={}) — 가설별 학습 신호 누적",
                open.size,
                resolved,
                wins,
                losses,
            )
        }
    }

    private fun markResolved(
        shadow: AutopilotPaperShadowJpaEntity,
        status: String,
        exitPrice: BigDecimal,
        exitTs: Instant,
    ) {
        shadow.status = status
        shadow.exitPrice = exitPrice
        shadow.exitTs = exitTs
        shadow.realizedPnl =
            AutopilotPaperShadowScorer.realizedPnl(
                entryPrice = shadow.entryPrice,
                exitPrice = exitPrice,
                quantity = shadow.quantity,
            )
        shadow.resolvedAt = Instant.now()
        shadowRepository.save(shadow)
        log.debug(
            "페이퍼 섀도 판정: shadowId={} strategyId={} code={} status={} entry={} exit={} pnl={}",
            shadow.id,
            shadow.strategyId,
            shadow.code,
            status,
            shadow.entryPrice.toPlainString(),
            exitPrice.toPlainString(),
            shadow.realizedPnl?.toPlainString(),
        )
    }
}
