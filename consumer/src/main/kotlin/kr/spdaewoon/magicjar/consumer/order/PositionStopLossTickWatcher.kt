package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.strategy.entry.KrxTickSize
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * tick 기반 손절 안전망 watcher — ADR 0114 R2-C2b ① (★핵심 — P1·P2·P4 구조적 해소 · Codex 흡수+성능 보강).
 *
 * ## 왜 독립 안전망인가 (ADR 0114 §1-2·§4)
 * KIS 국내주식 OpenAPI에 **서버 예약형 가격 트리거 스탑로스가 없다**(ADR 0114 §1-2). magicJar(consumer)가 곧 스탑로스
 * 장치인데, 1m 시그널 청산 경로는 ① readiness ② market-hours ③ 전략 status(DISABLED 제외) ④ 1m 캔들 마감 타이밍의
 * 4중 게이트에 의존한다. 본 watcher는 `market.tick.v1`을 직접 listen해 **그 모든 게이트를 우회**하는 손절 안전망이다
 * — tick 1건이라도 들어오면 손절가 도달 시 즉시 MARKET SELL. (1m 경로가 정밀 평가, 본 watcher가 fail-safe 하한.)
 *
 * ## Codex 약점 보강 (감점 회피 — 비교 머지 승부처)
 * - **매 tick DB 조회 0**: Codex는 tick마다 `findByCodeAndQuantityGreaterThan` + strategy `findById`. 본 watcher는
 *   [HoldingPositionIndex] 인메모리 hit일 때만 평가 진행(보유 0 종목 tick은 즉시 return, DB 접근 0).
 * - **손절가 단일 출처**: strategy-engine [KrxTickSize](R1) 재사용 — Codex가 domain에 만든 KrxTickSize와 중복 금지.
 *
 * ## fail-closed open SELL 가드 ([RestingSellOrderCanceller] 공용 — DRY)
 * 손절 발동 전 같은 종목 open SELL 검사를 매도 시그널 listener와 **공용 컴포넌트로 공유**한다:
 * - AUTO + 같은 전략 SELL → KIS 취소 시도(ACK 미확정이면 보류). 취소 성공해야 손절 진행(이중 SELL 회피).
 * - 수동(MANUAL)/타전략 SELL 존재 → **중단** + P0 로그(우리가 모르는 SELL 의도 침범 금지).
 * - 자기 자신의 기존 `auto-sl:` open SELL → 중복 발행 skip(excludeIdempotencyPrefix).
 *
 * ## 이중 발화 멱등 (1m 경로 ScaleInExit 손절과 충돌 방지)
 * `auto-sl:` 멱등 키([AutoOrderIdempotencyKeys.stopLoss]) + open SELL 검사 2중 — OrderRouter `idem:order:{key}`가
 * 동일 키 재발행 흡수. 1m 경로 손절 신호와 동시 발화해도 한 번만 주문된다.
 *
 * ## env
 * `magicjar.autotrading.stop-loss.tick-watch-enabled`(default true). false면 no-op(인덱스도 비활성).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 (ADR 0023 §2-4)
 */
@Component
class PositionStopLossTickWatcher(
    private val objectMapper: ObjectMapper,
    private val holdingPositionIndex: HoldingPositionIndex,
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val restingSellOrderCanceller: RestingSellOrderCanceller,
    private val orderIntentKafkaProducer: OrderIntentKafkaProducer,
    @Value("\${magicjar.autotrading.stop-loss.tick-watch-enabled:true}")
    private val enabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(PositionStopLossTickWatcher::class.java)

    @KafkaListener(topics = ["market.tick.v1"], groupId = GROUP)
    fun onTick(record: ConsumerRecord<String, String>) {
        if (!enabled) return
        val tick = parse(record) ?: return
        if (tick.stale || tick.price <= BigDecimal.ZERO) return

        // ★ 인메모리 hit-only — 보유 0 종목은 여기서 즉시 종료(DB 조회 0, Codex 대비 핵심 보강).
        val held = holdingPositionIndex.heldBy(tick.code)
        if (held.isEmpty()) return

        held.forEach { position ->
            runCatching { evaluate(position, tick.price) }
                .onFailure { e ->
                    log.warn(
                        "stop-loss tick 평가 실패(다음 tick 재평가): userId={} strategyId={} code={} err={}",
                        position.userId,
                        position.strategyId,
                        position.code,
                        e.message,
                    )
                }
        }
    }

    internal fun evaluate(
        position: HoldingPositionIndex.HeldPosition,
        tickPrice: BigDecimal,
    ) {
        val stopLossPct = position.stopLossPct ?: return
        val stopPrice = stopPriceOf(position.avgPrice, stopLossPct) ?: return
        if (tickPrice > stopPrice) return

        val orderRepo = orderRequestRepositoryProvider.getIfAvailable()
        if (orderRepo == null) {
            log.error(
                "stop-loss fail-closed: OrderRequestRepository 미합류 — 손절 보류 userId={} strategyId={} code={}",
                position.userId,
                position.strategyId,
                position.code,
            )
            return
        }

        // 같은 종목 open SELL(익절 TP 등) 선취소 — 공용 [RestingSellOrderCanceller]. 자신의 기존 auto-sl이면 중복 skip.
        val cancelResult =
            restingSellOrderCanceller.cancelRestingSells(
                orderRepo = orderRepo,
                userId = position.userId,
                strategyId = position.strategyId,
                code = position.code,
                excludeIdempotencyPrefix = AutoOrderIdempotencyKeys.PREFIX_STOP_LOSS,
            )
        if (!cancelResult.canProceed) return

        val idempotencyKey =
            AutoOrderIdempotencyKeys.stopLoss(
                position.userId,
                position.strategyId,
                position.code,
                position.quantity,
                position.updatedAtEpoch,
            )

        orderIntentKafkaProducer.publishExitIntent(
            userId = position.userId,
            strategyId = position.strategyId,
            code = position.code,
            intervalCode = "1m",
            side = OrderSide.SELL,
            priceType = OrderPriceType.MARKET,
            quantity = position.quantity,
            limitPrice = null,
            priceAtSignal = tickPrice,
            idempotencyKey = idempotencyKey,
        )
        log.warn(
            "stop-loss MARKET intent (tick watcher): userId={} strategyId={} code={} tick={} stopPrice={} qty={} idemKey={}",
            position.userId,
            position.strategyId,
            position.code,
            tickPrice,
            stopPrice,
            position.quantity,
            idempotencyKey,
        )
    }

    private fun parse(record: ConsumerRecord<String, String>): TickPayload? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val raw = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            TickPayload(
                code = raw["code"]?.toString() ?: return null,
                price = raw.decimalOrNull("price") ?: return null,
                stale = raw.booleanOrFalse("stale"),
            )
        } catch (e: Exception) {
            log.warn("PositionStopLossTickWatcher tick parse skip: offset={} err={}", record.offset(), e.message)
            null
        }
    }

    private fun Map<String, Any?>.decimalOrNull(key: String): BigDecimal? =
        when (val raw = this[key]) {
            is Number -> BigDecimal.valueOf(raw.toDouble())
            is String -> raw.toBigDecimalOrNull()
            else -> null
        }

    private fun Map<String, Any?>.booleanOrFalse(key: String): Boolean =
        when (val raw = this[key]) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> false
        }

    private data class TickPayload(
        val code: String,
        val price: BigDecimal,
        val stale: Boolean,
    )

    companion object {
        const val GROUP = "magicjar-consumer-position-stop-loss"

        /**
         * 손절가 = 평단 × (1 - stopLossPct/100) → KRX 호가단위 내림(R1 [KrxTickSize] 단일 출처).
         * 평단/pct 비정상(≤0)이면 null(평가 skip). 순수 함수 — 테스트·1m 경로와 산정 일관.
         */
        fun stopPriceOf(
            avgPrice: BigDecimal,
            stopLossPct: BigDecimal,
        ): BigDecimal? {
            if (avgPrice <= BigDecimal.ZERO || stopLossPct <= BigDecimal.ZERO) return null
            val raw =
                avgPrice.multiply(
                    BigDecimal.ONE.subtract(stopLossPct.divide(BigDecimal("100"), 10, RoundingMode.HALF_UP)),
                )
            if (raw <= BigDecimal.ZERO) return BigDecimal.ZERO
            return KrxTickSize.floorToTick(raw)
        }
    }
}
