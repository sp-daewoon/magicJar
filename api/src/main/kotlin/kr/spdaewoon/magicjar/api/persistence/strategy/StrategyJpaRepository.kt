package kr.spdaewoon.magicjar.api.persistence.strategy

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 전략 Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [StrategyJpaAdapter] 또는 [StrategyLookupJpaAdapter] private fun 위치.
 *
 * V14 추가: status 쿼리 (ADR 0066 §2-6 F-1), V61 BUY_BLOCKED 평가 포함.
 */
interface StrategyJpaRepository : JpaRepository<StrategyJpaEntity, Long> {
    fun findAllByUserIdAndStatusNot(
        userId: Long,
        status: String,
        pageable: Pageable,
    ): Page<StrategyJpaEntity>

    fun findByIdAndStatusNot(
        id: Long,
        status: String,
    ): StrategyJpaEntity?

    /**
     * 활성 페이퍼 전략 카운트 (ADR 0122 §5 — _MAX_INSTANCES 상한 가드).
     * origin(V81) + status 조합 — DELETED 제외 페이퍼 인스턴스 수. 헌법 §6: query inference abstract fun(body 0).
     */
    fun countByUserIdAndOriginAndStatusNot(
        userId: Long,
        origin: String,
        status: String,
    ): Long

    @Modifying
    @Query(
        "UPDATE StrategyJpaEntity s " +
            "SET s.disabledReason = :reason, s.lastDisabledAt = CURRENT_TIMESTAMP, s.disabledAt = CURRENT_TIMESTAMP, " +
            "s.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE s.id = :id",
    )
    fun updateDisabledReason(
        @Param("id") id: Long,
        @Param("reason") reason: String,
    ): Int

    @Modifying
    @Query(
        "UPDATE StrategyJpaEntity s SET s.disabledReason = NULL, s.lastDisabledAt = NULL, s.disabledAt = NULL, " +
            "s.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE s.id = :id",
    )
    fun clearDisabledReason(
        @Param("id") id: Long,
    ): Int

    @Modifying
    @Query(
        "UPDATE StrategyJpaEntity s " +
            "SET s.disabledReason = :reason, s.lastDisabledAt = CURRENT_TIMESTAMP, " +
            "s.status = 'BUY_BLOCKED', s.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE s.id = :id",
    )
    fun markBuyBlocked(
        @Param("id") id: Long,
        @Param("reason") reason: String,
    ): Int

    /**
     * status 전환 — JPQL UPDATE (V14 status + timestamp 동기).
     * ADR 0066 §2-6 F-1 transition 매트릭스 정합.
     */
    @Modifying
    @Query(
        "UPDATE StrategyJpaEntity s " +
            "SET s.status = :status, s.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE s.id = :id",
    )
    fun updateStatus(
        @Param("id") id: Long,
        @Param("status") status: String,
    ): Int

    /**
     * 평가 가능한 전략(ACTIVE·BUY_BLOCKED) 중 codes JSONB에 특정 code를 포함하고 intervals JSONB에 특정 interval을 포함하는 목록 조회.
     * 운영 전략은 명시 종목/인터벌 매핑이 있어야 실시간 평가 대상이 된다.
     * native query — PostgreSQL jsonb @> 연산자 사용.
     */
    @Query(
        value =
            "SELECT * FROM strategy " +
                "WHERE owner = 'USER' " +
                "AND status IN ('ACTIVE', 'BUY_BLOCKED') " +
                "AND codes @> CAST(:codeJson AS jsonb) " +
                "AND intervals @> CAST(:intervalJson AS jsonb)",
        nativeQuery = true,
    )
    fun findActiveByCodeAndInterval(
        @Param("codeJson") codeJson: String,
        @Param("intervalJson") intervalJson: String,
    ): List<StrategyJpaEntity>

    /**
     * 특정 status 전략 전건 — 관리자 실시간 상태(candleFreshness ACTIVE 종목 집계)용.
     * 헌법 §6 (ADR 0051): query inference abstract fun (body 0).
     */
    fun findAllByStatus(status: String): List<StrategyJpaEntity>

    /**
     * owner+status 전략 전건 — 자동매매 평가/집계 경로 SYSTEM 제외 가드 (spec 2026-06-18 §5.1).
     * RealtimeStatusService가 owner='USER' ACTIVE 전략만 집계하도록 [findAllByStatus] 대체.
     * 헌법 §6 (ADR 0051): query inference abstract fun (body 0).
     */
    fun findAllByOwnerAndStatus(
        owner: String,
        status: String,
    ): List<StrategyJpaEntity>

    /**
     * owner 별 전략 전건 — 시작 화면 SYSTEM 템플릿 조회용 (spec 2026-06-18 §6.1).
     * 헌법 §6 (ADR 0051): query inference abstract fun (body 0).
     */
    fun findAllByOwner(owner: String): List<StrategyJpaEntity>

    /**
     * owner 별 미삭제 전략 수 — magicjar-autopilot `/api/autopilot/status` owner=AUTOPILOT 전략수 표시용
     * (spec 2026-06-22 Task 4). 헌법 §6 (ADR 0051): query inference abstract fun (body 0).
     */
    fun countByOwnerAndStatusNot(
        owner: String,
        status: String,
    ): Long

    /**
     * 내 전략 목록 — "자동매매 시작(ACTIVE) 전략 우선 + 그룹 내 최신 변경순" 정렬.
     *
     * Spring Data [org.springframework.data.domain.Sort]는 CASE식 정렬을 표현하지 못하므로 @Query JPQL로 처리.
     * 정렬 규칙(가동도 → 최신 변경):
     *   1. status 우선순위: ACTIVE(0) > BUY_BLOCKED(1) > PAUSED(2) > DISABLED(3) > 기타(4)
     *   2. 동일 그룹 내 updatedAt DESC (전략 토글 시 updated_at 갱신 → 방금 켠 전략이 맨 위)
     *
     * DELETED는 WHERE 절에서 제외. ORDER BY 가 포함되어 Spring Data가 count 파생에 실패할 수 있어 countQuery 명시.
     * 헌법 §6 (ADR 0051): Repository interface 내부 abstract fun (body 0) — 정렬 로직은 @Query 위임.
     */
    @Query(
        value =
            "SELECT s FROM StrategyJpaEntity s " +
                "WHERE s.userId = :userId AND s.status <> 'DELETED' " +
                "ORDER BY CASE s.status " +
                "WHEN 'ACTIVE' THEN 0 " +
                "WHEN 'BUY_BLOCKED' THEN 1 " +
                "WHEN 'PAUSED' THEN 2 " +
                "WHEN 'DISABLED' THEN 3 " +
                "ELSE 4 END ASC, s.updatedAt DESC",
        countQuery =
            "SELECT COUNT(s) FROM StrategyJpaEntity s " +
                "WHERE s.userId = :userId AND s.status <> 'DELETED'",
    )
    fun findAllByUserIdActiveFirst(
        @Param("userId") userId: Long,
        pageable: Pageable,
    ): Page<StrategyJpaEntity>

    /**
     * 내 전략 목록 — 검색(q)·정렬(sort) 파라미터 반영 (LIST 트랙 LOCKED CONTRACT).
     *
     * ## 왜 native 인가
     * 검색 대상에 `codes`(JSONB)가 포함되는데, JPQL은 PostgreSQL `jsonb::text ILIKE` 부분일치를 표현하지 못한다.
     * 그래서 q·sort 가 모두 한 SQL 안에서 평가되도록 native query 1건으로 통합하고, q 없음+recent/status 의 현행
     * 경로는 [findAllByUserIdActiveFirst](JPQL)를 그대로 호출해 **완전 동일 동작(zero-regression)** 을 보존한다.
     * 이 메서드는 q 가 있거나 sort=name 일 때만 사용한다.
     *
     * ## 파라미터
     * - `qLike`: `%키워드%`(소문자) 또는 null. null/빈값이면 필터 안 함(전체).
     *   부분일치 대상 = name / name_ko / codes(JSONB ::text) 모두 `LOWER(...) LIKE`.
     * - `sort`: `recent`·`name`·`status`. 정렬 식은 CASE WHEN 으로 단일 SQL 안에서 분기.
     *   - `name`: COALESCE(name_ko, name) 가나다/알파벳 오름차순(NULLS LAST), tie-break updated_at DESC.
     *   - 그 외(recent·status): status 우선순위(ACTIVE>BUY_BLOCKED>PAUSED>DISABLED) 그룹 → updated_at DESC
     *     (= findAllByUserIdActiveFirst 와 동일 규칙. recent·status 모두 동일 그룹 정렬을 사용한다).
     *
     * DELETED 제외. ORDER BY 포함이라 countQuery 명시.
     * 헌법 §6 (ADR 0051): Repository interface 내부 abstract fun (body 0) — 정렬·필터 로직은 @Query 위임.
     */
    @Query(
        value =
            "SELECT * FROM strategy s " +
                "WHERE s.user_id = :userId AND s.status <> 'DELETED' " +
                "AND (:qLike IS NULL OR " +
                "  LOWER(s.name) LIKE :qLike OR " +
                "  LOWER(COALESCE(s.name_ko, '')) LIKE :qLike OR " +
                "  LOWER(COALESCE(s.codes::text, '')) LIKE :qLike) " +
                "ORDER BY " +
                "  CASE WHEN :sort = 'name' THEN LOWER(COALESCE(s.name_ko, s.name)) END ASC NULLS LAST, " +
                "  CASE WHEN :sort <> 'name' THEN " +
                "    CASE s.status WHEN 'ACTIVE' THEN 0 WHEN 'BUY_BLOCKED' THEN 1 " +
                "      WHEN 'PAUSED' THEN 2 WHEN 'DISABLED' THEN 3 ELSE 4 END " +
                "  END ASC, " +
                "  s.updated_at DESC",
        countQuery =
            "SELECT COUNT(*) FROM strategy s " +
                "WHERE s.user_id = :userId AND s.status <> 'DELETED' " +
                "AND (:qLike IS NULL OR " +
                "  LOWER(s.name) LIKE :qLike OR " +
                "  LOWER(COALESCE(s.name_ko, '')) LIKE :qLike OR " +
                "  LOWER(COALESCE(s.codes::text, '')) LIKE :qLike)",
        nativeQuery = true,
    )
    fun searchByUserId(
        @Param("userId") userId: Long,
        @Param("qLike") qLike: String?,
        @Param("sort") sort: String,
        pageable: Pageable,
    ): Page<StrategyJpaEntity>
}
