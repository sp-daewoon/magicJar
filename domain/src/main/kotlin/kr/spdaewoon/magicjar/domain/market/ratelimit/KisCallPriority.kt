package kr.spdaewoon.magicjar.domain.market.ratelimit

/**
 * KIS REST 호출 path 우선순위 분류 (spec PR #564 §2-2 LOCK).
 *
 * Phase 1: 정보 수집만 — 모든 priority 동일 bucket (단일 LOCK).
 * Phase 2: USER_PATH > INGESTION > BACKFILL 우선순위 큐 적용 (별도 사이클).
 *
 * sealed class 대신 enum 선택 사유:
 *   - Phase 1은 분류 라벨 LOCK (인스턴스 상태 0)
 *   - log·통계 라벨 친화 (`name` property 직접 사용)
 *   - when 분기 exhaustive 강제 — 신규 우선순위 추가 시 빌드 게이트
 */
enum class KisCallPriority {
    /**
     * 사용자 직접 path — 현재가·차트·주문 등.
     * Phase 2에서 우선 처리 (다른 path 대기 중에도 즉시 acquire).
     */
    USER_PATH,

    /**
     * Watchlist Ingestion (1~5초 polling) + 정기 cron.
     * Phase 2에서 중간 우선순위.
     */
    INGESTION,

    /**
     * Backfill·corp action·reconcile (백그라운드 batch).
     * Phase 2에서 최후 우선순위 (사용자 path 방해 0).
     */
    BACKFILL,
}
