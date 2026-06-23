package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * strategy_position Spring Data JPA Repository (consumer 모듈 — ADR 0110, 분할매수 R2-2).
 *
 * ### 헌법 §6 정합
 * abstract fun + 명시 `@Query` 만 — body 가진 default fun 0건 (Spring Data query inference 함정 회피).
 * 평단/실현손익/exit stage 갱신 수식은 어댑터([ConsumerExecutionStrategyPositionUpdateAdapter])의 helper 책임.
 */
interface StrategyPositionJpaRepository : JpaRepository<StrategyPositionJpaEntity, Long> {
    fun findByUserIdAndStrategyIdAndCode(
        userId: String,
        strategyId: Long,
        code: String,
    ): StrategyPositionJpaEntity?

    fun findByUserIdAndCode(
        userId: String,
        code: String,
    ): List<StrategyPositionJpaEntity>

    /**
     * 주기 reconcile union 측 — 특정 user 의 strategy_position 보유(quantity>0) 종목 distinct code.
     *
     * ADR 0115 §P1-1 amend (2026-06-10 over-count incident). [StrategyPositionReconcileWorker] 가
     * KIS 권위(position quantity>0) 종목과 **합집합**으로 순회 범위를 넓힐 때 사용한다. KIS 가 0주가 된 유령
     * over-count 종목(strategy_position 만 잔존)도 reconcileStrategyPositionDrift 에 도달시켜 [PositionDriftCorrection]
     * Adjust(초과 내림)가 작동하게 한다.
     *
     * strategy_position 에 mode 컬럼이 없으므로 user 기준 code 만 뽑고, 권위 비교(REAL/MOCK 분기)는 worker 가
     * position 측 source 키로 수행한다. @Query 명시 — 헌법 §6 default-fun 추론 함정 회피.
     */
    @Query(
        "SELECT DISTINCT sp.code FROM StrategyPositionJpaEntity sp " +
            "WHERE sp.userId = :userId AND sp.quantity > 0",
    )
    fun findDistinctCodesByUserIdAndQuantityPositive(
        @Param("userId") userId: String,
    ): List<String>

    /** drift 검증용 — user+code 전략별 보유 수량 합(spec §3-1 불변식: == position.quantity). */
    @Query(
        "SELECT COALESCE(SUM(sp.quantity), 0) FROM StrategyPositionJpaEntity sp " +
            "WHERE sp.userId = :userId AND sp.code = :code",
    )
    fun sumQuantityByUserAndCode(
        @Param("userId") userId: String,
        @Param("code") code: String,
    ): Int

    /**
     * 보유 중(quantity>0) 전 strategy_position — ADR 0114 R2-C2b 인메모리 보유 인덱스 60초 refresh·HOLDING owner 등록.
     * @Query 명시 — 헌법 §6 default-fun 함정 회피(method-name 추론 대신 명시 JPQL).
     */
    @Query("SELECT sp FROM StrategyPositionJpaEntity sp WHERE sp.quantity > 0")
    fun findAllHolding(): List<StrategyPositionJpaEntity>

    /**
     * 특정 종목 보유(quantity>0) lot — ADR 0114 R2-C2b 인덱스 miss 시 즉시 재확인·drift 가드용.
     * tick hot path는 인메모리 인덱스를 1차로 쓰고 본 쿼리는 등록/refresh·검증 경로에서만 호출(매 tick DB 조회 금지).
     */
    @Query("SELECT sp FROM StrategyPositionJpaEntity sp WHERE sp.code = :code AND sp.quantity > 0")
    fun findHoldingByCode(
        @Param("code") code: String,
    ): List<StrategyPositionJpaEntity>

    /**
     * ADR 0122 §2-C — 페이퍼(가상 자본) 실현손익 누적 합.
     *
     * 해당 user 의 발굴 페이퍼 전략(`strategy.origin = 'PAPER_DISCOVERY'`)에 귀속된 strategy_position 행의
     * realized_pnl 합. [kr.spdaewoon.magicjar.consumer.risk.PaperCapitalSnapshotProvider] 가
     * `가상 자본 잔액 = 초기 default + 페이퍼 실현손익 합(부호 그대로)` 으로 산출하는 입력.
     *
     * 실 전략(USER_DSL) row 는 join 조건에서 제외 → 실 잔고·실 리스크 판정 오염 0(spec §1-3 격리 LOCK).
     * native query(strategy join · origin enum 컬럼은 V81 add). 행 부재 시 COALESCE 0.
     * @Query 명시 — 헌법 §6 default-fun 추론 함정 회피.
     */
    @Query(
        value =
            "SELECT COALESCE(SUM(sp.realized_pnl), 0) FROM strategy_position sp " +
                "JOIN strategy s ON s.id = sp.strategy_id " +
                "WHERE sp.user_id = :userId AND s.origin = 'PAPER_DISCOVERY'",
        nativeQuery = true,
    )
    fun sumPaperRealizedPnlByUser(
        @Param("userId") userId: String,
    ): java.math.BigDecimal

    /**
     * replay TOCTOU 직렬화용 advisory xact lock — (user, strategyId, code) 2개 정수 키.
     *
     * `pg_advisory_xact_lock(key1, key2)` 는 현재 트랜잭션 범위 lock 으로, commit/rollback 시 자동 해제(leak 0).
     * 같은 키의 동시 replay 트랜잭션을 직렬화해 [StrategyPositionExecutionReplayService] 의
     * 존재확인→가산 read-modify-write 이중 진입(over-count)을 차단한다.
     *
     * - key1 = (user_id, strategy_id) 결합 해시 — `('x' || md5(...))::bit(32)::int`.
     * - key2 = code 해시 — 동.
     * 32bit int 충돌은 advisory lock 의 한 키에 한해 (다른 종목이 같은 lock 을 공유) 약간의 직렬화만 유발할 뿐
     * 정합성을 깨지 않는다(보수적 over-lock).
     *
     * @Query nativeQuery — JPQL 로 표현 불가한 Postgres 함수. 반드시 활성 트랜잭션 안에서만 호출(xact-scope).
     */
    @Query(
        value =
            "SELECT pg_advisory_xact_lock(" +
                "('x' || md5(:userId || ':' || cast(:strategyId as text)))::bit(32)::int, " +
                "('x' || md5(:code))::bit(32)::int)",
        nativeQuery = true,
    )
    fun acquireReplayAdvisoryLock(
        @Param("userId") userId: String,
        @Param("strategyId") strategyId: Long,
        @Param("code") code: String,
    )

    /**
     * magicjar-autopilot **루프 전용 노출 합**(원) — owner=AUTOPILOT 전략 보유 평가액 Σ(avg_price × quantity)
     * (spec 2026-06-22 §2-0-A · Task 3 Guard input).
     *
     * 루프 전용 자본 풀은 사용자 수동 전략·후성과 **분리** 계산한다 → `strategy.owner = 'AUTOPILOT'` 조인으로
     * 루프 귀속 lot 만 합산한다(매수원가 기준 — RiskBudget totalExposure 와 동일 산식). 행 부재 시 COALESCE 0.
     * native query(strategy join). @Query 명시 — 헌법 §6 default-fun 추론 함정 회피.
     */
    @Query(
        value =
            "SELECT COALESCE(SUM(sp.avg_price * sp.quantity), 0) FROM strategy_position sp " +
                "JOIN strategy s ON s.id = sp.strategy_id " +
                "WHERE sp.user_id = :userId AND sp.quantity > 0 AND s.owner = 'AUTOPILOT'",
        nativeQuery = true,
    )
    fun sumAutopilotExposureByUser(
        @Param("userId") userId: String,
    ): java.math.BigDecimal

    /**
     * magicjar-autopilot **동시 보유 종목수** — owner=AUTOPILOT 전략이 현재 보유(quantity>0)한 distinct 종목수
     * (spec 2026-06-22 §2-0-A maxPositions input · Task 3). 행 부재 시 0. native query(strategy join).
     */
    @Query(
        value =
            "SELECT COUNT(DISTINCT sp.code) FROM strategy_position sp " +
                "JOIN strategy s ON s.id = sp.strategy_id " +
                "WHERE sp.user_id = :userId AND sp.quantity > 0 AND s.owner = 'AUTOPILOT'",
        nativeQuery = true,
    )
    fun countAutopilotHeldCodesByUser(
        @Param("userId") userId: String,
    ): Int

    /**
     * magicjar-autopilot **보유 종목 코드 목록** — owner=AUTOPILOT 전략 보유(quantity>0) distinct 종목 코드
     * (spec 2026-06-22 §2-0-D kill 저널 held_codes 스냅샷 · Task 4). 행 부재 시 빈 목록. native query(strategy join).
     */
    @Query(
        value =
            "SELECT DISTINCT sp.code FROM strategy_position sp " +
                "JOIN strategy s ON s.id = sp.strategy_id " +
                "WHERE sp.user_id = :userId AND sp.quantity > 0 AND s.owner = 'AUTOPILOT'",
        nativeQuery = true,
    )
    fun findAutopilotHeldCodesByUser(
        @Param("userId") userId: String,
    ): List<String>

    /**
     * magicjar-autopilot **청산 대상 보유 lot** — owner=AUTOPILOT 전략이 보유(quantity>0)한 lot 단위 목록
     * (spec 2026-06-22 §Task 5 · [kr.spdaewoon.magicjar.consumer.autopilot.AutopilotLiquidationService] 입력).
     *
     * 롤백/kill 시 루프 자본을 회수하기 위해 청산할 (user, strategy, code, quantity)를 lot 단위로 뽑는다.
     * 같은 종목을 여러 owner=AUTOPILOT 전략이 보유하면 각 전략 lot 이 별 행으로 나오며, 청산 service 가
     * lot 단위로 SELL intent 를 발행한다(전략별 ledger 정합 — strategy_position UQ=(user,strategy,code)).
     *
     * - userId not null → 해당 user 의 owner=AUTOPILOT lot.
     * - userId null → 전체 owner=AUTOPILOT lot(account-wide 롤백).
     *
     * 격리 LOCK: `s.owner = 'AUTOPILOT'` 조인으로 사용자 수동 전략·후성 lot 은 구조적으로 제외된다.
     * native query(strategy join). @Query 명시 — 헌법 §6 default-fun 추론 함정 회피.
     */
    @Query(
        value =
            "SELECT sp.user_id AS userId, sp.strategy_id AS strategyId, sp.code AS code, " +
                "sp.quantity AS quantity, sp.avg_price AS avgPrice " +
                "FROM strategy_position sp " +
                "JOIN strategy s ON s.id = sp.strategy_id " +
                "WHERE sp.quantity > 0 AND s.owner = 'AUTOPILOT' " +
                "AND (:userId IS NULL OR sp.user_id = :userId)",
        nativeQuery = true,
    )
    fun findAutopilotHeldPositions(
        @Param("userId") userId: String?,
    ): List<AutopilotHeldLotProjection>

    /**
     * magicjar-autopilot **EOD 청산 대상 보유 lot** — owner=AUTOPILOT **AND allow_overnight=false** 전략이
     * 보유(quantity>0)한 lot (ADR 0129 레버 1 · [kr.spdaewoon.magicjar.consumer.autopilot.AutopilotEodLiquidationWorker] 입력).
     *
     * [findAutopilotHeldPositions] 의 `allow_overnight=false` 필터판이다. 오버나잇 금지(allow_overnight=false) 전략의
     * 보유분만 장마감 전 시장가 청산해 갭 리스크를 회피한다(2026-06-23 080220 −16,137 = 손실 55% 복기).
     *
     * 격리 LOCK(zero-regression): `s.owner = 'AUTOPILOT'` 조인으로 **USER/SYSTEM 전략 lot 은 절대 반환하지 않는다**
     * (allow_overnight 값과 무관 — 사용자 스윙 전략 보호). allow_overnight=true 인 AUTOPILOT lot 도 제외(토글 존중).
     *
     * - userId not null → 해당 user 의 대상 lot. userId null → 전체 대상 lot(account-wide EOD).
     * native query(strategy join). @Query 명시 — 헌법 §6 default-fun 추론 함정 회피.
     */
    @Query(
        value =
            "SELECT sp.user_id AS userId, sp.strategy_id AS strategyId, sp.code AS code, " +
                "sp.quantity AS quantity, sp.avg_price AS avgPrice " +
                "FROM strategy_position sp " +
                "JOIN strategy s ON s.id = sp.strategy_id " +
                "WHERE sp.quantity > 0 AND s.owner = 'AUTOPILOT' AND s.allow_overnight = false " +
                "AND (:userId IS NULL OR sp.user_id = :userId)",
        nativeQuery = true,
    )
    fun findAutopilotHeldPositionsNoOvernight(
        @Param("userId") userId: String?,
    ): List<AutopilotHeldLotProjection>
}

/**
 * owner=AUTOPILOT 보유 lot 투영 — [StrategyPositionJpaRepository.findAutopilotHeldPositions] 결과.
 *
 * Spring Data 인터페이스 기반 projection(native query alias → getter 매핑). 청산 service 가
 * 종목별 시장가 SELL OrderIntent 발행에 사용한다(avgPrice 는 priceAtSignal 단가 source).
 */
interface AutopilotHeldLotProjection {
    fun getUserId(): String

    fun getStrategyId(): Long

    fun getCode(): String

    fun getQuantity(): Int

    fun getAvgPrice(): java.math.BigDecimal
}
