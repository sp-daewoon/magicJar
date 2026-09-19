package kr.spdaewoon.magicjar.persistence.shared.system

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * system_config JPA 엔티티 — singleton row (id=1, ADR 0046 §2-3).
 * DART API 키·Slack webhook 등 BYTEA (AES-256-GCM 암호화).
 */
@Entity
@Table(name = "system_config")
class SystemConfigJpaEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: Short = 1,
    @Column(name = "dart_api_key")
    var dartApiKey: ByteArray? = null,
    @Column(name = "notification_slack_webhook")
    var notificationSlackWebhook: ByteArray? = null,
    /** 주문 체결 알림 전용 추가 Slack 채널 webhook (V79 · 이중 송출 — 미설정 시 추가 송출 skip). */
    @Column(name = "notification_slack_webhook_order")
    var notificationSlackWebhookOrder: ByteArray? = null,
    @Column(name = "realtime_fallback_buy_enabled", nullable = false)
    var realtimeFallbackBuyEnabled: Boolean = true,
    /**
     * 활성 주문 모드 (V102 · 감사 2026-09-19 §5 A) — "REAL" | "MOCK".
     * MOCK/REAL 주문 모드를 재기동 없이 전환하는 DB 단일 권위.
     * default 'MOCK' 안전 기본값. env REAL 상한이 열려 있을 때만 REAL로 유효(TradingModeGate 책임).
     * 시세/차트/지표는 이 값과 무관하게 항상 REAL(모드 분리).
     */
    @Column(name = "active_trading_mode", nullable = false)
    var activeTradingMode: String = "MOCK",
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(id = 1)
}
