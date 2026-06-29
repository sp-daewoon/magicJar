package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.consumer.order.AuctionLiquidationPrice
import kr.spdaewoon.magicjar.consumer.order.AutoOrderIdempotencyKeys
import kr.spdaewoon.magicjar.consumer.order.RestingSellOrderCanceller
import kr.spdaewoon.magicjar.consumer.persistence.AutopilotHeldLotProjection
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.market.session.KrxAuctionWindow
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

/**
 * magicjar-autopilot **포지션 청산 service** — [AutopilotLiquidator] 구현 (spec 2026-06-22 §Task 5).
 *
 * owner=AUTOPILOT 루프가 보유한(quantity>0) 포지션 전량을 **시장가 SELL**로 청산한다. kill·롤백
 * (`AutopilotControlListener` ROLLBACK)이 호출해 루프 자본을 회수한다.
 *
 * ### 데이터 흐름 (신규 KIS endpoint 0 — 기존 청산 경로 재사용)
 * 1. [StrategyPositionJpaRepository.findAutopilotHeldPositions] 로 owner=AUTOPILOT 보유 lot 조회
 *    (lot = (user, strategy, code, quantity) — strategy_position UQ 단위, `s.owner='AUTOPILOT'` 조인 격리).
 * 2. lot 별 **resting auto-TP SELL 선취소** ([RestingSellOrderCanceller.cancelRestingSells]) — 같은 종목에
 *    걸린 미체결 익절 LIMIT(`auto-tp:`)이 보유 수량을 잠그면 KIS 가 후속 시장가 청산을 "매도가능수량 부족"으로
 *    거부한다(2026-06-24 라이브 080220/122640 EOD 청산 REJECTED → 오버나잇 결함). 매도 시그널·손절 경로
 *    ([kr.spdaewoon.magicjar.consumer.risk.SignalGeneratedKafkaListener]·[kr.spdaewoon.magicjar.consumer.order.PositionStopLossTickWatcher])
 *    와 동일 패턴을 재사용(DRY)해 청산 발행 **전에** resting SELL 을 풀어준다.
 * 3. lot 별 **시장가 SELL** [OrderIntent] 를 기존 [OrderIntentKafkaProducer.publishExitIntent]
 *    (`Order.INTENT = kafka.shared.cmd.order.intent.v1`) 로 발행한다. 그 뒤 OrderRouter →
 *    KisOrderHttpAdapter 의 기존 자동청산 경로를 그대로 탄다.
 * 4. 멱등 키는 [AutoOrderIdempotencyKeys.autopilotLiquidation] (`auto-lq:` prefix) — 한 청산 호출
 *    (동일 reason·ts) 내 이중 발행은 OrderRouter `idem:order:{key}` 가 흡수한다.
 *
 * ### 롱온리·격리 LOCK (`docs/architecture/policies/long-only-trading.md`)
 * - SELL = 보유분 청산만(공매도 0). 수량은 보유 quantity 그대로(전량) — 초과·음수 불가
 *   (조회 단계에서 `quantity > 0` 필터, 발행 직전 한 번 더 양수 가드).
 * - 청산 대상은 **owner=AUTOPILOT lot 만** — 조인 조건으로 사용자 수동 전략·후성은 구조적으로 제외된다.
 *   본 service 는 종목·전략을 자체 추론하지 않고 repository 가 격리한 lot 만 발행한다.
 *
 * ### 가격
 * 시장가 청산이므로 limitPrice 는 null. `priceAtSignal` 은 canary notional cap 단가 source 로 lot
 * avgPrice(매수 평단)를 전달한다(현재가 미조회 — 청산은 체결 보장 우선, 가격 보호 불요. 롱온리 정책 §3-3).
 *
 * AI 호출 0 — 룰 기반 자본 흐름 (ADR 0023 §2-4).
 */
@Service
class AutopilotLiquidationService(
    private val strategyPositionJpaRepository: StrategyPositionJpaRepository,
    private val orderIntentKafkaProducer: OrderIntentKafkaProducer,
    private val restingSellOrderCanceller: RestingSellOrderCanceller,
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    // D1 (2026-06-29 사고) — 단일가(동시호가) 구간 판정 시각 출처. 결정론 테스트용 주입점.
    private val clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
) : AutopilotLiquidator {
    private val log = LoggerFactory.getLogger(AutopilotLiquidationService::class.java)

    override fun liquidateAll(
        userId: String?,
        reason: String,
    ): AutopilotLiquidationOutcome = liquidateLots(strategyPositionJpaRepository.findAutopilotHeldPositions(userId), userId, reason)

    /**
     * EOD 청산 (ADR 0129 레버 1) — owner=AUTOPILOT **AND allow_overnight=false** lot 만 시장가 SELL.
     *
     * [liquidateAll] 과 동일 발행 메커니즘(기존 OrderIntent·멱등 키)이되 청산 대상 쿼리만
     * [StrategyPositionJpaRepository.findAutopilotHeldPositionsNoOvernight] 로 교체한다(spec §4 레버 1).
     * USER/SYSTEM lot·allow_overnight=true AUTOPILOT lot 은 쿼리 격리로 구조적 제외(zero-regression).
     */
    override fun liquidateNoOvernightAutopilot(
        userId: String?,
        reason: String,
    ): AutopilotLiquidationOutcome =
        liquidateLots(strategyPositionJpaRepository.findAutopilotHeldPositionsNoOvernight(userId), userId, reason)

    /**
     * lot 목록을 시장가 SELL OrderIntent 로 청산하는 공용 발행 루틴 (DRY — [liquidateAll]·[liquidateNoOvernightAutopilot]
     * 공유). 격리는 호출자가 넘긴 lot 목록(repository 쿼리가 owner=AUTOPILOT 조인으로 이미 격리)에 위임한다.
     */
    private fun liquidateLots(
        lots: List<AutopilotHeldLotProjection>,
        userId: String?,
        reason: String,
    ): AutopilotLiquidationOutcome {
        if (lots.isEmpty()) {
            log.info(
                "autopilot 청산 no-op — owner=AUTOPILOT 보유 lot 0건: userId={} reason={}",
                userId ?: "ALL",
                reason,
            )
            return AutopilotLiquidationOutcome(liquidatedCodes = emptyList(), totalSellIntents = 0)
        }

        val nowEpoch = clock.instant().epochSecond
        val liquidatedCodes = LinkedHashSet<String>()
        var totalSellIntents = 0
        // resting auto-TP SELL 선취소용 open SELL 영속 포트. 미합류 시 선취소 없이 진행(degraded — 청산 우선 성격상
        // 보류하지 않는다. 매도 시그널·손절 경로의 fail-closed 보류와 달리 EOD/kill 청산은 오버나잇·자본 회수가 목적).
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable()
        if (orderRepo == null) {
            log.warn(
                "autopilot 청산 — OrderRequestRepository 미합류: resting SELL 선취소 없이 진행(degraded) userId={} reason={}",
                userId ?: "ALL",
                reason,
            )
        }

        log.warn(
            "autopilot 청산 시작 — owner=AUTOPILOT lot {}건: userId={} reason={}",
            lots.size,
            userId ?: "ALL",
            reason,
        )

        lots.forEach { lot ->
            val quantity = lot.getQuantity()
            // 롱온리 가드 — 조회는 quantity>0 필터지만, 발행 직전 한 번 더 양수 확인(공매도·음수 0 LOCK).
            if (quantity <= 0) {
                log.warn(
                    "autopilot 청산 skip(비양수 수량 — 롱온리 가드): userId={} strategyId={} code={} qty={}",
                    lot.getUserId(),
                    lot.getStrategyId(),
                    lot.getCode(),
                    quantity,
                )
                return@forEach
            }

            // 같은 종목 resting auto-TP SELL(미체결 익절 LIMIT) 선취소 — 발행 가능해야 청산 진행("먼저 오는 쪽이 이긴다").
            // 선취소 미완료(수동/타전략 SELL 침범·KIS ACK 미확정·취소 실패) lot 은 이 회차 skip → 다음 청산 호출/EOD tick·
            // 손절 watcher 가 재시도. rtCd=7(원주문 KIS 부재)은 canceller 가 already-cancelled 로 종결해 그대로 진행한다.
            if (orderRepo != null) {
                val cancelResult =
                    restingSellOrderCanceller.cancelRestingSells(
                        orderRepo = orderRepo,
                        userId = lot.getUserId(),
                        strategyId = lot.getStrategyId(),
                        code = lot.getCode(),
                    )
                if (!cancelResult.canProceed) {
                    log.warn(
                        "autopilot 청산 skip(resting SELL 선취소 미완료 — 다음 기회 재시도): userId={} strategyId={} code={} result={}",
                        lot.getUserId(),
                        lot.getStrategyId(),
                        lot.getCode(),
                        cancelResult::class.simpleName,
                    )
                    return@forEach
                }
            }

            val priceAtSignal = lot.getAvgPrice().takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ZERO
            val idempotencyKey =
                AutoOrderIdempotencyKeys.autopilotLiquidation(
                    lot.getUserId(),
                    lot.getStrategyId(),
                    lot.getCode(),
                    quantity,
                    reason,
                    nowEpoch,
                )

            // D1 (2026-06-29 사고) — 단일가(동시호가) 구간엔 KIS 가 시장가 SELL 을 거부한다. 롱온리 청산은 체결 보장이
            // 본질(헌법 §13)이라 시장가 대신 평단 기준 하한가 marketable LIMIT 으로 발행한다. 연속매매 구간은 MARKET 유지.
            // 단일가 구간이라도 LIMIT 산정 불가(평단 ≤ 0)면 MARKET fallback — 청산 우선(가격 결함으로 청산 보류 안 함).
            val (priceType, limitPrice) = resolveExitPricing(priceAtSignal)

            orderIntentKafkaProducer.publishExitIntent(
                userId = lot.getUserId(),
                strategyId = lot.getStrategyId(),
                code = lot.getCode(),
                intervalCode = "1m",
                side = OrderSide.SELL,
                priceType = priceType,
                quantity = quantity,
                limitPrice = limitPrice,
                priceAtSignal = priceAtSignal,
                idempotencyKey = idempotencyKey,
                // 청산은 자동 익절·롤백 회수 성격 — AUTO_EXIT + 유발 근거 = 청산 대상 전략 id.
                orderTrigger = OrderTrigger.AUTO_EXIT,
                triggerRefId = lot.getStrategyId(),
            )
            liquidatedCodes.add(lot.getCode())
            totalSellIntents++

            log.warn(
                "autopilot 청산 SELL intent: userId={} strategyId={} code={} qty={} priceType={} limitPrice={} reason={} idemKey={}",
                lot.getUserId(),
                lot.getStrategyId(),
                lot.getCode(),
                quantity,
                priceType,
                limitPrice,
                reason,
                idempotencyKey,
            )
        }

        log.warn(
            "autopilot 청산 완료 — 종목 {}개·SELL intent {}건: userId={} reason={}",
            liquidatedCodes.size,
            totalSellIntents,
            userId ?: "ALL",
            reason,
        )
        return AutopilotLiquidationOutcome(
            liquidatedCodes = liquidatedCodes.toList(),
            totalSellIntents = totalSellIntents,
        )
    }

    /**
     * D1 — 청산 주문 가격 유형 결정. 단일가(동시호가) 구간이면 평단 기준 marketable SELL LIMIT, 그 외 MARKET.
     *
     * 단일가 구간이라도 LIMIT 산정 불가(referencePrice ≤ 0)면 MARKET fallback — 청산 우선(가격 결함으로 보류 안 함).
     * 현재 시각은 [Instant.now] — EOD 워커(15:15)는 이미 동시호가 전이라 항상 MARKET, 늦게 발화하는 kill·롤백·재청산
     * 대비 안전망이다.
     */
    private fun resolveExitPricing(referencePrice: BigDecimal): Pair<OrderPriceType, BigDecimal?> {
        val kotlinNow = kotlinx.datetime.Instant.fromEpochMilliseconds(clock.instant().toEpochMilli())
        if (!KrxAuctionWindow.isSinglePriceAuction(kotlinNow)) return OrderPriceType.MARKET to null
        val limit = AuctionLiquidationPrice.marketableSellLimit(referencePrice)
        return if (limit != null) OrderPriceType.LIMIT to limit else OrderPriceType.MARKET to null
    }
}
