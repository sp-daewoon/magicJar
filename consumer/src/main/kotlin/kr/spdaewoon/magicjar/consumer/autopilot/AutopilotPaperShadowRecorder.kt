package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyOwner
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * autopilot **페이퍼 섀도 기록기** (개선 G · 슬롯 공정성 · paper-parallel hook).
 *
 * ## 책임 (단일)
 * 실슬롯/풀에 막혀 starve 된 owner=AUTOPILOT BUY 를 **실 자본 0** 으로 `autopilot_paper_shadow` 에 가상 진입 1행
 * 기록한다. 후속 캔들 TP/SL 도달 판정은 [AutopilotPaperShadowScoringWorker] 가 비동기로 수행한다. 본 기록기는
 * "어떤 BUY 를 섀도로 남길지"의 게이트 + 가상 진입 스냅샷 영속만 책임진다(SRP).
 *
 * ## 적용 대상 (격리 LOCK — zero-regression 핵심)
 * 다음을 **전부** 만족할 때만 기록한다. 하나라도 어긋나면 no-op(기존 경로 byte 동일):
 *  1. [MagicjarAutopilotProperties.enabled]=true — autopilot 루프 활성 구간.
 *  2. signal.side == BUY — 진입만(SELL·HOLD 무관).
 *  3. ownerIsAutopilot — owner=AUTOPILOT 전략(USER/SYSTEM 무관).
 *  4. blockReason ∈ [SHADOWABLE_RULES] — **슬롯/풀 경합으로 starve** 된 경우만. kill·daily-loss·dedup·
 *     price 결함 등은 "정당한 차단"이라 섀도 대상이 아니다(가설이 틀려서가 아니라 안전·중복 차단).
 *
 * ## 자본 안전 (LOCK)
 * 본 기록기는 KIS·실 capital·order_request·strategy_position 을 **절대 만지지 않는다**. 별 테이블
 * (autopilot_paper_shadow)에만 INSERT 한다. 실보유 동시 cap 3 불변 — 섀도는 자본 0 이라 슬롯과 무관.
 *
 * ## fail-open (학습 보조 — 자본 게이트 아님)
 * 섀도 기록 실패(DB 예외·strategy 미상)는 **실 매매 경로에 영향 0** 이어야 한다 — 호출자(listener)는 본 기록기의
 * 예외를 무시하고 기존 BLOCKED 처리를 그대로 진행한다. 페이퍼 학습이 실 자본 안전·청산을 막아선 안 된다.
 *
 * // AI 호출 0 — 룰 기반 학습 장부 (ADR 0023 §2-4 정합)
 */
@Component
class AutopilotPaperShadowRecorder(
    private val properties: MagicjarAutopilotProperties,
    private val shadowRepository: AutopilotPaperShadowJpaRepository,
    @Value("\${magicjar.autopilot.paper-shadow.virtual-notional-krw:1000000}")
    private val virtualNotionalKrw: BigDecimal = BigDecimal("1000000"),
) {
    private val log = LoggerFactory.getLogger(AutopilotPaperShadowRecorder::class.java)

    /**
     * starve 된 owner=AUTOPILOT BUY 를 페이퍼 섀도로 기록(조건 충족 시). 조건 미충족·실패 시 no-op(fail-open).
     *
     * ## 가상 수량 산정 — 고정 가상 notional ÷ 진입가 (가설 공정 비교)
     * 실 슬롯/풀이 **소진**돼 막힌 상황이라 실 buyable 로 사이징하면 0 이 된다(starve 의 정의). 따라서 섀도는
     * 가설 간 **공정 비교**를 위해 고정 가상 notional(env `...virtual-notional-krw`, default 100만)을 진입가로 나눈
     * 균등 수량으로 사이징한다 — 모든 가설이 같은 명목으로 평가돼 승률·기대값이 동일 척도다. 실 자본 0.
     *
     * @param signal 차단된 BUY 시그널.
     * @param ownerIsAutopilot 호출자가 확정한 owner=AUTOPILOT 여부(신호 운반 또는 lookup).
     * @param strategy 전략(RiskPlan TP/SL 출처). null 이면 기록 skip(TP/SL 미상 — 점수 산정 불가).
     * @param blockReason 차단 룰(SHADOWABLE 만 기록).
     */
    @Transactional
    fun recordIfStarved(
        signal: Signal,
        ownerIsAutopilot: Boolean,
        strategy: Strategy?,
        blockReason: RiskRule,
    ) {
        try {
            if (!properties.enabled) return
            if (signal.side != SignalSide.BUY) return
            if (!ownerIsAutopilot) return
            if (blockReason !in SHADOWABLE_RULES) return

            val entryPrice = signal.priceAtSignal
            if (entryPrice <= BigDecimal.ZERO) return

            // 가상 수량 = 고정 가상 notional ÷ 진입가(균등 명목 — 가설 공정 비교). ≥1 floor.
            val quantity =
                virtualNotionalKrw
                    .divide(entryPrice, 0, RoundingMode.DOWN)
                    .toInt()
                    .coerceAtLeast(1)

            // TP/SL 은 전략 RiskPlan 이 권위. 부재면 점수 산정 불가 → skip(0 fabricate 금지).
            val tp = strategy?.definition?.takeProfitPct
            val sl = strategy?.definition?.fixedStopLoss
            if (strategy == null || tp == null || sl == null || tp <= BigDecimal.ZERO || sl <= BigDecimal.ZERO) {
                log.debug(
                    "페이퍼 섀도 skip — TP/SL 미상(점수 산정 불가): strategyId={} code={} tp={} sl={}",
                    signal.strategyId,
                    signal.code,
                    tp,
                    sl,
                )
                return
            }

            // owner 재확인(방어) — strategy 가 실제 AUTOPILOT 인지 한 번 더(legacy lookup 신뢰 보강).
            if (strategy.owner != StrategyOwner.AUTOPILOT) return

            // 같은 가설·같은 종목 OPEN 섀도 중복 방지(장부 노이즈 차단 — 실 dedup 정신 정합).
            if (shadowRepository.countOpenByStrategyAndCode(signal.strategyId, signal.code) > 0) {
                log.debug(
                    "페이퍼 섀도 skip — 동일 (strategyId,code) OPEN 섀도 이미 존재: strategyId={} code={}",
                    signal.strategyId,
                    signal.code,
                )
                return
            }

            val interval = strategy.intervals.firstOrNull() ?: signal.interval.code
            val entity =
                AutopilotPaperShadowJpaEntity(
                    userId = signal.userId,
                    strategyId = signal.strategyId,
                    code = signal.code,
                    interval = interval,
                    signalId = signal.id,
                    entryPrice = entryPrice,
                    quantity = quantity,
                    takeProfitPct = tp,
                    stopLossPct = sl,
                    entryTs = Instant.now(),
                    blockReason = blockReason.name,
                )
            shadowRepository.save(entity)
            log.info(
                "페이퍼 섀도 기록(slot starve → 학습 신호): strategyId={} code={} interval={} entry={} qty={} tp={}% sl={}% reason={}",
                signal.strategyId,
                signal.code,
                interval,
                entryPrice.toPlainString(),
                quantity,
                tp.toPlainString(),
                sl.toPlainString(),
                blockReason,
            )
        } catch (e: Exception) {
            // fail-open — 페이퍼 학습 실패가 실 매매 경로를 막지 않는다(자본 게이트 아님).
            log.warn(
                "페이퍼 섀도 기록 실패(무시 — fail-open): strategyId={} code={} err={}",
                signal.strategyId,
                signal.code,
                e.message,
            )
        }
    }

    companion object {
        /**
         * 섀도 대상 룰 — **슬롯/풀 경합으로 starve** 된 경우만. 진단(#1101)에서 102·103 이 막힌 정확한 두 룰.
         * kill·daily-loss·dedup·price 결함은 "정당한 차단"(가설이 틀려서가 아님)이라 섀도 대상 아님.
         */
        val SHADOWABLE_RULES: Set<RiskRule> =
            setOf(RiskRule.MAX_CONCURRENT_POSITIONS, RiskRule.POSITION_LIMIT)
    }
}
