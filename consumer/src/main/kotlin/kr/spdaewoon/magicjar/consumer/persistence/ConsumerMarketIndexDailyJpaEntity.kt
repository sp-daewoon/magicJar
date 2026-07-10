package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * market_index_daily 읽기 전용 JPA 엔티티 (consumer 사영) — Flyway V86 정합 (api [MarketIndexDailyJpaEntity] 미러).
 *
 * 지수×일자 OHLC 롤업(전 전략 공유 레짐 source)을 consumer 실시간 시그널 경로가 읽기 위한 최소 사영이다.
 * publisher(집계·UPSERT)는 batch/api 책임이고 consumer 는 **regime 컬럼만** 읽는다([RegimeEntryGate]). 신규 테이블 0
 * (기존 V86 테이블 read-only 매핑). 헌법 §6 — body 가진 fun 0건.
 *
 * // AI 호출 0 — 룰 기반 진입 가드 read 경로 (ADR 0023 §2-4 정합)
 */
@Entity
@Table(name = "market_index_daily")
class ConsumerMarketIndexDailyJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "index_code", nullable = false, length = 16)
    val indexCode: String = "",
    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "regime", length = 16)
    val regime: String? = null,
) {
    protected constructor() : this(id = null)
}
