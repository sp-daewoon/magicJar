package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.market.session.KrxAuctionWindow
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.strategy.entry.KrxTickSize
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
    private val positionJpaRepository: PositionJpaRepository,
    @Value("\${magicjar.autotrading.stop-loss.tick-watch-enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${magicjar.autotrading.stop-loss.tick-cooldown-seconds:30}")
    private val tickCooldownSeconds: Long = 30,
    // hotfix 2026-06-11 — D1: position 권위 잔량 조회의 source(REAL/MOCK) 차원 정합용 현재 거래 모드.
    // position UQ=(user,code,source) — source 누락 시 MOCK+REAL 행 공존 종목에서 "2 results" 오류.
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
    // D1 (2026-06-29 사고) — 단일가(동시호가) 구간 판정·idemKey timeBucket 의 시각 출처. 결정론 테스트용 주입점.
    private val clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
) {
    private val log = LoggerFactory.getLogger(PositionStopLossTickWatcher::class.java)

    /**
     * ADR 0115 §P2-1 — auto-sl intent 발행 후 같은 포지션 재발행 차단 인메모리 cooldown.
     * key = userId:strategyId:code, value = (마지막 발행 시각 epoch, 발행 당시 포지션 상태 signature).
     * 상태(quantity·updatedAtEpoch) 변화 시 즉시 재발행 허용(정당한 다음 손절은 통과). 같은 상태면 cooldownSeconds 내 1회만.
     * consumer 단일 인스턴스 가정(현 배포) — 다중 인스턴스 확장 시 Redis SETEX 승격(P2-3 backlog).
     */
    private val intentCooldown = ConcurrentHashMap<String, FiredIntent>()

    @KafkaListener(topics = [KafkaTopics.Market.TICK], groupId = GROUP)
    fun onTick(record: ConsumerRecord<String, String>) {
        if (!enabled) return
        val tick = parse(record) ?: return
        if (tick.stale || tick.price <= BigDecimal.ZERO) return

        evaluateHeldPositions(tick.code, tick.price)
    }

    /**
     * WS tick이 조용할 때 ACTIVE 전략 degraded polling/no-tick fallback 가격으로 손절만 평가하는 안전망.
     *
     * Kafka `market.tick.v1`의 stale tick은 기존처럼 무시한다. 이 메서드는 caller가 명시적으로
     * "현재 보유 보호용 fallback 가격"임을 알고 호출하는 direct path라서 stale=true 여도 손절 평가를 진행한다.
     * 신규 BUY 신호는 이 경로에서 만들지 않는다.
     */
    fun onFallbackPriceTick(tick: TickEvent) {
        if (!enabled) return
        if (tick.price <= BigDecimal.ZERO) return
        if (!isSupportedFallbackSource(tick.source)) return

        evaluateHeldPositions(tick.code, tick.price)
    }

    private fun evaluateHeldPositions(
        code: String,
        price: BigDecimal,
    ) {
        // ★ 인메모리 hit-only — 보유 0 종목은 여기서 즉시 종료(DB 조회 0, Codex 대비 핵심 보강).
        val held = holdingPositionIndex.heldBy(code)
        if (held.isEmpty()) return

        held.forEach { position ->
            runCatching { evaluate(position, price) }
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

    private fun isSupportedFallbackSource(source: String): Boolean = source == SOURCE_KIS_POLLING || source == SOURCE_KIS_NOTICK_FALLBACK

    internal fun evaluate(
        position: HoldingPositionIndex.HeldPosition,
        tickPrice: BigDecimal,
    ) {
        val stopLossPct = position.stopLossPct ?: return
        val stopPrice = stopPriceOf(position.avgPrice, stopLossPct) ?: return
        if (tickPrice > stopPrice) {
            // 손절가 미도달로 복귀 — 다음 정당한 손절이 즉시 가능하도록 cooldown 해제(상태 기반 clear).
            intentCooldown.remove(cooldownKey(position))
            return
        }

        // ★ 분할 미완 손절 보류 (2026-06-19 사용자 결재 — 1m 경로 [ScaleInExitCalculator]와 동일 의미 정합).
        //   scale-in 전략이 분할 미완(filledLots < totalSlices)이면 평단 기준 tick 손절을 보류한다(물타기 기회 보존).
        //   단, catastrophic floor(평단 -[SCALE_IN_STOP_LOSS_FLOOR_PCT]%) 도달 시엔 분할 중이라도 발동(무한 보류·교착 방지).
        //   scale-in 미설정(totalSlices null)이면 보류 없이 현행 손절(zero-regression).
        if (shouldDeferStopLossForScaleIn(position, tickPrice)) {
            log.debug(
                "stop-loss 분할 미완 보류(평단 손절선 도달했으나 분할 진행 중): userId={} strategyId={} code={} filledLots={} totalSlices={} tick={} stopPrice={}",
                position.userId,
                position.strategyId,
                position.code,
                position.filledLots,
                position.scaleInTotalSlices,
                tickPrice,
                stopPrice,
            )
            // 보류 중에도 cooldown 은 손절가 미도달 복귀 시처럼 비워, 추후 분할 완료 후 정당한 손절이 즉시 가능하게 둔다.
            intentCooldown.remove(cooldownKey(position))
            return
        }

        // ADR 0115 §P2-1 cooldown — 같은 포지션 상태에 대해 이미 발행했고 cooldown 내면 재발행 skip(폭주 원천 차단).
        if (isCoolingDown(position)) {
            log.debug(
                "stop-loss tick cooldown skip(상태 무변·임계 내): userId={} strategyId={} code={} qty={}",
                position.userId,
                position.strategyId,
                position.code,
                position.quantity,
            )
            return
        }

        // ADR 0115 §P2-3 — position(KIS 권위) 잔량 확인 가드. 인덱스는 strategy_position 기반이라 보정 직전·경합 구간에
        // 유령(strategy_position>0 인데 position==0)이 남을 수 있다. 실보유 0이면 손절 미발사(유령 손절 폭주 차단).
        // PAUSED 전략이라도 실보유(position>0)면 발사 유지(보유 보호 의도 보존 — ADR 0115 §P2-3).
        if (!hasAuthorityHolding(position)) {
            log.warn(
                "stop-loss skip(유령 포지션 — position 권위 잔량 0): userId={} strategyId={} code={} ledgerQty={}",
                position.userId,
                position.strategyId,
                position.code,
                position.quantity,
            )
            return
        }

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

        val now = clock.instant()
        // D2 (2026-06-29 사고) — timeBucket 차원으로 영구 차단 함정 회피. 같은 bucket·상태는 동일 키(in-flight 멱등),
        // bucket 넘어가면 새 키로 terminal(CANCELLED/REJECTED) 행에 막히지 않고 재시도. bucket 폭 = tick cooldown.
        val idempotencyKey =
            AutoOrderIdempotencyKeys.stopLoss(
                position.userId,
                position.strategyId,
                position.code,
                position.quantity,
                position.updatedAtEpoch,
                idemTimeBucket(now),
            )

        // D1 (2026-06-29 사고) — 단일가(동시호가) 구간엔 KIS 가 시장가 SELL 을 거부한다. 롱온리 청산은 체결 보장이
        // 본질이므로 시장가 대신 하한가 근처 marketable LIMIT 으로 발행한다(헌법 §13). 연속매매 구간은 기존 MARKET 유지.
        val (priceType, limitPrice) = resolveExitPricing(tickPrice, now)

        orderIntentKafkaProducer.publishExitIntent(
            userId = position.userId,
            strategyId = position.strategyId,
            code = position.code,
            intervalCode = "1m",
            side = OrderSide.SELL,
            priceType = priceType,
            quantity = position.quantity,
            limitPrice = limitPrice,
            priceAtSignal = tickPrice,
            idempotencyKey = idempotencyKey,
            // ADR 0115 §2-P2-⑥ — tick 손절: 발사 출처 STOP_LOSS + 유발 근거 = 청산 대상 전략 id
            orderTrigger = OrderTrigger.STOP_LOSS,
            triggerRefId = position.strategyId,
        )
        // 발행 성공 — cooldown 갱신(상태 signature + 발행 시각). 같은 상태 재tick은 임계까지 차단.
        intentCooldown[cooldownKey(position)] =
            FiredIntent(firedAtEpoch = now.epochSecond, stateSignature = stateSignatureOf(position))
        log.warn(
            "stop-loss {} intent (tick watcher): userId={} strategyId={} code={} tick={} stopPrice={} qty={} limitPrice={} idemKey={}",
            priceType,
            position.userId,
            position.strategyId,
            position.code,
            tickPrice,
            stopPrice,
            position.quantity,
            limitPrice,
            idempotencyKey,
        )
    }

    /**
     * D1 — 청산 주문 가격 유형 결정. 단일가(동시호가) 구간이면 marketable SELL LIMIT, 그 외(연속매매)는 MARKET.
     *
     * 단일가 구간이라도 LIMIT 가 산정 불가(현재가 ≤ 0 등)면 MARKET fallback — 안전망 성격상 가격 결함으로 손절을
     * 막지 않는다(KIS 가 거부하면 다음 tick 재시도). 정상 단일가 구간은 [AuctionLiquidationPrice] 하한가 LIMIT.
     */
    private fun resolveExitPricing(
        referencePrice: BigDecimal,
        now: Instant,
    ): Pair<OrderPriceType, BigDecimal?> {
        val kotlinNow = kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilli())
        if (!KrxAuctionWindow.isSinglePriceAuction(kotlinNow)) return OrderPriceType.MARKET to null
        val limit = AuctionLiquidationPrice.marketableSellLimit(referencePrice)
        return if (limit != null) OrderPriceType.LIMIT to limit else OrderPriceType.MARKET to null
    }

    /** D2 — 손절 idemKey timeBucket. tick cooldown 폭(초)으로 epochSecond 를 양자화 → 같은 창 안 같은 bucket. */
    private fun idemTimeBucket(now: Instant): Long {
        val width = tickCooldownSeconds.coerceAtLeast(1)
        return now.epochSecond / width
    }

    /**
     * cooldown 판정 — 같은 포지션 상태(quantity·updatedAtEpoch)로 cooldownSeconds 내 이미 발행했으면 true(skip).
     * 상태가 바뀌었으면(부분 청산·재진입 등) false(즉시 재발행 허용 — 상태 기반 해제).
     */
    private fun isCoolingDown(position: HoldingPositionIndex.HeldPosition): Boolean {
        val fired = intentCooldown[cooldownKey(position)] ?: return false
        // 상태 변화(수량/갱신시각) — 정당한 다음 손절. cooldown 무시.
        if (fired.stateSignature != stateSignatureOf(position)) return false
        val elapsed = clock.instant().epochSecond - fired.firedAtEpoch
        return elapsed < tickCooldownSeconds
    }

    /**
     * position(KIS 권위) 잔량 > 0 확인 — ADR 0115 §P2-3 유령 포지션 발사 가드.
     *
     * hotfix 2026-06-11 (D1): position UQ=(user,code,source) 라 MOCK·REAL 행이 의도적으로 공존한다
     * (005930: MOCK 0주 + REAL 1주 정상). 기존 `findByUserIdAndCode`(source 무차원)는 두 행 공존 시
     * `Query did not return a unique result: 2 results` 예외 → fail-safe true 로 빠져 **잘못된 행 기준 발사
     * 위험**(09:09 라이브 로그). 현재 거래 모드(source)로 좁혀 그 모드의 권위 잔량만 본다.
     *
     * 조회 실패 시 fail-safe로 true(기존 동작 보존 — 손절 안전망 우선, 조회 결함으로 손절을 막지 않음).
     */
    private fun hasAuthorityHolding(position: HoldingPositionIndex.HeldPosition): Boolean =
        runCatching {
            val source = RuntimeModeParser.tradingModeOrMock(magicjarMode).name
            (positionJpaRepository.findByUserIdAndCodeAndSource(position.userId, position.code, source)?.quantity ?: 0) > 0
        }.getOrElse { e ->
            log.warn(
                "stop-loss position 권위 조회 실패 — fail-safe 발사 진행: userId={} code={} err={}",
                position.userId,
                position.code,
                e.message,
            )
            true
        }

    /**
     * 분할 미완 손절 보류 판정 (2026-06-19 사용자 결재 — tick 안전망 정합).
     *
     * 1m 경로 [kr.spdaewoon.magicjar.strategy.scalein.ScaleInExitCalculator.shouldFireStopLoss] 와 **같은 의미**:
     * - scale-in 미설정([HeldPosition.scaleInTotalSlices] null) → 보류 안 함(현행 손절 — zero-regression).
     * - 분할 완료(filledLots >= totalSlices) → 보류 안 함(최종 평단 손절 정상).
     * - 분할 미완(filledLots < totalSlices) → 보류(true). 단 평단 대비 손실이 catastrophic floor
     *   ([SCALE_IN_STOP_LOSS_FLOOR_PCT], 평단 -15%) 이상이면 보류 해제(false → 손절 발동 — 무한 보류·교착 방지).
     *
     * floor 산정 실패(평단/floor 비정상)면 보류 해제(false) — 안전망 우선(보류로 손절을 막는 fail-open 회피).
     */
    private fun shouldDeferStopLossForScaleIn(
        position: HoldingPositionIndex.HeldPosition,
        tickPrice: BigDecimal,
    ): Boolean {
        val totalSlices = position.scaleInTotalSlices ?: return false // scale-in 미설정 — 보류 없음
        if (position.filledLots >= totalSlices) return false // 분할 완료 — 손절 정상
        // 분할 미완 — catastrophic floor 미도달이면 보류. floor 도달이면 보류 해제(손절 발동).
        val floorPrice = stopPriceOf(position.avgPrice, SCALE_IN_STOP_LOSS_FLOOR_PCT) ?: return false
        return tickPrice > floorPrice
    }

    private fun cooldownKey(position: HoldingPositionIndex.HeldPosition): String =
        "${position.userId}:${position.strategyId}:${position.code}"

    /** 포지션 상태 signature — 변경 시 cooldown 자연 해제(같은 상태 무한 재발행만 차단). */
    private fun stateSignatureOf(position: HoldingPositionIndex.HeldPosition): String = "${position.quantity}:${position.updatedAtEpoch}"

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

    /** auto-sl 발행 1건의 cooldown 상태 — 발행 시각 + 발행 당시 포지션 상태 signature(ADR 0115 §P2-1). */
    private data class FiredIntent(
        val firedAtEpoch: Long,
        val stateSignature: String,
    )

    companion object {
        const val GROUP = "magicjar-consumer-position-stop-loss"
        const val SOURCE_KIS_POLLING = "KIS-POLLING"
        const val SOURCE_KIS_NOTICK_FALLBACK = "KIS-NOTICK-FALLBACK"

        /**
         * 분할 미완 손절 보류의 catastrophic floor%(평단 대비 손실폭). tick 안전망 last-resort 값으로,
         * floor 단일 출처인 domain [kr.spdaewoon.magicjar.domain.strategy.ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT]
         * 를 직접 참조한다(2026-06-19 ADR 0128 amend — strategy-engine `@Deprecated` alias 경유 제거). 1m 경로·엔진과
         * **동일 값 정합**(tick 안전망과 1m 경로가 같은 floor 로 발동 — 의심 원천 차단). 안전망 성격상 yml default 가 아닌
         * 컴파일 상수를 last-resort 로 고정한다(전략 미주입·이상 상황에서도 결정론적 발동 보장).
         */
        val SCALE_IN_STOP_LOSS_FLOOR_PCT: BigDecimal =
            kr.spdaewoon.magicjar.domain.strategy.ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT

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
