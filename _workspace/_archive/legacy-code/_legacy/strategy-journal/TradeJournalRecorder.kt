package com.mj.domain.strategy.journal

import kr.spdaewoon.magicjar.core.domain.Mode
import kr.spdaewoon.magicjar.core.domain.OrderStatus
import kr.spdaewoon.magicjar.core.domain.Side
import kr.spdaewoon.magicjar.core.event.AiReviewRequestedEvent
import kr.spdaewoon.magicjar.core.event.OrderFilledEvent
import kr.spdaewoon.magicjar.core.port.InstrumentCatalog
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 주문 체결 이벤트 → 매매일지 자동 기록.
 *
 * - BUY 체결: 새 엔트리 생성. 같은 mode+code의 미청산(`exitAt is null`) 엔트리가 있으면 평단 업데이트.
 * - SELL 체결: 미청산 엔트리를 찾아 청산 정보 채움. pnl/pnlRate 계산.
 *
 * 지표 스냅샷은 [IndicatorSnapshotProvider]로 채운다(현 구현은 OrderFilledEvent에 캔들이 없으므로
 * 백테스트 엔진은 [recordEntry]/[recordExit]를 직접 호출하는 경로를 권장).
 *
 * `magicjar.journal.ai-review=true`이면 청산 시점에 [AiReviewRequestedEvent] 발행.
 */
@Component
class TradeJournalRecorder(
    private val jpa: TradeJournalJpaRepository,
    private val instrumentCatalog: InstrumentCatalog,
    private val publisher: ApplicationEventPublisher,
    @Value("\${magicjar.journal.ai-review:false}")
    private val aiReviewEnabled: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(TradeJournalRecorder::class.java)

    @EventListener
    @Transactional
    fun onOrderFilled(event: OrderFilledEvent) {
        val r = event.result
        if (r.resultStatus != OrderStatus.FILLED) return
        val price = r.avgFillPrice ?: return
        val now = Instant.now(clock)
        val name = instrumentCatalog.findByCode(r.order.code)?.name ?: r.order.code
        when (r.order.side) {
            Side.BUY ->
                recordEntryInternal(
                    tradeId = UUID.randomUUID(),
                    mode = event.mode,
                    code = r.order.code,
                    name = name,
                    qty = r.filledQty,
                    price = price,
                    at = r.filledAt ?: now,
                    strategyId = "default",
                    entryReason = "auto: filled BUY order ${r.order.id}",
                    snapshot = emptyMap(),
                    aiExplanation = null,
                    tags = emptyList(),
                    now = now,
                )
            Side.SELL ->
                recordExitInternal(
                    mode = event.mode,
                    code = r.order.code,
                    qty = r.filledQty,
                    price = price,
                    at = r.filledAt ?: now,
                    exitReason = "auto: filled SELL order ${r.order.id}",
                    snapshot = emptyMap(),
                    now = now,
                )
        }
    }

    /** 외부(백테스트 엔진)에서 직접 호출해 풍부한 컨텍스트로 기록할 때 사용. */
    @Transactional
    fun recordEntry(
        tradeId: UUID = UUID.randomUUID(),
        mode: Mode,
        code: String,
        name: String,
        qty: Int,
        price: BigDecimal,
        at: Instant,
        strategyId: String,
        entryReason: String,
        snapshot: Map<String, BigDecimal> = emptyMap(),
        aiExplanation: String? = null,
        tags: List<String> = emptyList(),
    ): UUID =
        recordEntryInternal(
            tradeId = tradeId,
            mode = mode,
            code = code,
            name = name,
            qty = qty,
            price = price,
            at = at,
            strategyId = strategyId,
            entryReason = entryReason,
            snapshot = snapshot,
            aiExplanation = aiExplanation,
            tags = tags,
            now = Instant.now(clock),
        )

    /** 외부에서 직접 호출. 매칭되는 미청산 엔트리에 청산을 채워 넣는다. */
    @Transactional
    fun recordExit(
        mode: Mode,
        code: String,
        qty: Int,
        price: BigDecimal,
        at: Instant,
        exitReason: String,
        snapshot: Map<String, BigDecimal> = emptyMap(),
    ): UUID? = recordExitInternal(mode, code, qty, price, at, exitReason, snapshot, Instant.now(clock))

    private fun recordEntryInternal(
        tradeId: UUID,
        mode: Mode,
        code: String,
        name: String,
        qty: Int,
        price: BigDecimal,
        at: Instant,
        strategyId: String,
        entryReason: String,
        snapshot: Map<String, BigDecimal>,
        aiExplanation: String?,
        tags: List<String>,
        now: Instant,
    ): UUID {
        // 같은 mode+code 미청산 엔트리가 있으면 평단 갱신(추가 매수)
        val existing =
            jpa.findFirstByCodeAndModeAndExitAtIsNullOrderByEntryAtDesc(code, mode)
        return if (existing != null) {
            val newQty = existing.entryQty + qty
            val totalCost =
                existing.entryPrice
                    .multiply(BigDecimal(existing.entryQty))
                    .add(price.multiply(BigDecimal(qty)))
            existing.entryPrice = totalCost.divide(BigDecimal(newQty), MC)
            existing.entryQty = newQty
            existing.updatedAt = now
            jpa.save(existing)
            existing.id
        } else {
            val id = UUID.randomUUID()
            val jpaEntity =
                TradeJournalJpa(
                    id = id,
                    tradeId = tradeId,
                    mode = mode,
                    code = code,
                    name = name,
                    side = Side.BUY,
                    entryAt = at,
                    entryPrice = price,
                    entryQty = qty,
                    strategyId = strategyId,
                    entrySnapshot = JournalJsonCodec.encodeSnapshot(snapshot),
                    entryReason = entryReason,
                    aiExplanation = aiExplanation,
                    tags = JournalJsonCodec.encodeTags(tags),
                    createdAt = now,
                    updatedAt = now,
                )
            jpa.save(jpaEntity)
            id
        }
    }

    private fun recordExitInternal(
        mode: Mode,
        code: String,
        qty: Int,
        price: BigDecimal,
        at: Instant,
        exitReason: String,
        snapshot: Map<String, BigDecimal>,
        now: Instant,
    ): UUID? {
        val open =
            jpa.findFirstByCodeAndModeAndExitAtIsNullOrderByEntryAtDesc(code, mode)
        if (open == null) {
            log.warn("recordExit: no open entry for code={} mode={}", code, mode)
            return null
        }
        open.exitAt = at
        open.exitPrice = price
        open.exitQty = qty
        open.exitSnapshot = JournalJsonCodec.encodeSnapshot(snapshot)
        open.exitReason = exitReason
        val gross = price.subtract(open.entryPrice).multiply(BigDecimal(qty))
        open.pnl = gross
        open.pnlRate =
            if (open.entryPrice.signum() == 0) {
                BigDecimal.ZERO
            } else {
                price.subtract(open.entryPrice).divide(open.entryPrice, MC)
            }
        open.updatedAt = now
        jpa.save(open)
        if (aiReviewEnabled) {
            publisher.publishEvent(AiReviewRequestedEvent(open.id))
        }
        return open.id
    }

    companion object {
        private val MC = MathContext(12, RoundingMode.HALF_UP)
    }
}
