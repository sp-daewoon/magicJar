package kr.spdaewoon.magicjar.domain.order.execution

import java.math.BigDecimal
import java.time.Instant

/**
 * 주문 영속 도메인 (ADR 0068 §14-1 LOCK · §6-1 order_request 테이블 정합).
 *
 * OrderRouterKafkaListener step 5에서 INSERT, step 8에서 UPDATE(ACK), KisExecutionWsListener에서 종료 UPDATE.
 * Phase 2 backend-core JPA 어댑터가 본 도메인을 영속(§15-2).
 *
 * 필드 LOCK (§14-1 17 컬럼 매트릭스):
 *   id                — bigserial PK
 *   userId            — KIS credential identity
 *   signalId          — T-SIG cross-ref
 *   strategyId        — strategy cross-ref
 *   code              — 종목코드
 *   side              — BUY/SELL
 *   priceType         — MARKET/LIMIT
 *   quantity          — 주문 수량
 *   limitPrice        — LIMIT 시 not null
 *   status            — [OrderStatus] 전이 매트릭스
 *   mode              — [TradingMode] (3중 게이트 결과 LOCK)
 *   kisOrderNo        — KIS ODNO (ACK 시점 채워짐)
 *   kisFwdgOrgno      — KIS KRX_FWDG_ORD_ORGNO (정정/취소 의무 동반)
 *   idempotencyKey    — UQ
 *   rejectReason      — REJECTED 시 사유 (REAL_GATE_DENIED:cond / rt_cd:msg / KIS_5XX 등)
 *   requestedAt       — listener 진입 시각
 *   acknowledgedAt    — KIS ACK 응답 시각
 *   completedAt       — FILLED/REJECTED/CANCELLED 종료 시각
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
data class OrderRequest(
    val id: Long? = null,
    val userId: String,
    val signalId: Long,
    val strategyId: Long,
    val code: String,
    val side: OrderSide,
    val priceType: OrderPriceType,
    val quantity: Int,
    val limitPrice: BigDecimal?,
    val status: OrderStatus,
    val mode: TradingMode,
    val kisOrderNo: String? = null,
    val kisFwdgOrgno: String? = null,
    val idempotencyKey: String,
    val rejectReason: String? = null,
    val requestedAt: Instant,
    val acknowledgedAt: Instant? = null,
    val completedAt: Instant? = null,
)
