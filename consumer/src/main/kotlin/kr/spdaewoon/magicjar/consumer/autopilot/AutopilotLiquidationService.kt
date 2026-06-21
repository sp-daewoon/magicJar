package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.consumer.order.AutoOrderIdempotencyKeys
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import org.slf4j.LoggerFactory
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
 * 2. lot 별 **시장가 SELL** [OrderIntent] 를 기존 [OrderIntentKafkaProducer.publishExitIntent]
 *    (`Order.INTENT = kafka.shared.cmd.order.intent.v1`) 로 발행한다. 그 뒤 OrderRouter →
 *    KisOrderHttpAdapter 의 기존 자동청산 경로를 그대로 탄다.
 * 3. 멱등 키는 [AutoOrderIdempotencyKeys.autopilotLiquidation] (`auto-lq:` prefix) — 한 청산 호출
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
) : AutopilotLiquidator {
    private val log = LoggerFactory.getLogger(AutopilotLiquidationService::class.java)

    override fun liquidateAll(
        userId: String?,
        reason: String,
    ): AutopilotLiquidationOutcome {
        val lots = strategyPositionJpaRepository.findAutopilotHeldPositions(userId)
        if (lots.isEmpty()) {
            log.info(
                "autopilot 청산 no-op — owner=AUTOPILOT 보유 lot 0건: userId={} reason={}",
                userId ?: "ALL",
                reason,
            )
            return AutopilotLiquidationOutcome(liquidatedCodes = emptyList(), totalSellIntents = 0)
        }

        val nowEpoch = Instant.now().epochSecond
        val liquidatedCodes = LinkedHashSet<String>()
        var totalSellIntents = 0

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

            orderIntentKafkaProducer.publishExitIntent(
                userId = lot.getUserId(),
                strategyId = lot.getStrategyId(),
                code = lot.getCode(),
                intervalCode = "1m",
                side = OrderSide.SELL,
                priceType = OrderPriceType.MARKET,
                quantity = quantity,
                limitPrice = null,
                priceAtSignal = priceAtSignal,
                idempotencyKey = idempotencyKey,
                // 청산은 자동 익절·롤백 회수 성격 — AUTO_EXIT + 유발 근거 = 청산 대상 전략 id.
                orderTrigger = OrderTrigger.AUTO_EXIT,
                triggerRefId = lot.getStrategyId(),
            )
            liquidatedCodes.add(lot.getCode())
            totalSellIntents++

            log.warn(
                "autopilot 청산 SELL intent: userId={} strategyId={} code={} qty={} reason={} idemKey={}",
                lot.getUserId(),
                lot.getStrategyId(),
                lot.getCode(),
                quantity,
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
}
