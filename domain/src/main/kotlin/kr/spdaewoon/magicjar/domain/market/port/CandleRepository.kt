package kr.spdaewoon.magicjar.domain.market.port

import kotlinx.datetime.toJavaInstant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import java.time.Instant

/**
 * 캔들 영속 포트 (ADR 0019 — UQ candle.code+interval+ts).
 * 구현은 api 모듈 persistence + consumer 모듈 persistence 각각.
 */
interface CandleRepository {
    /**
     * 캔들 일괄 저장 (ON CONFLICT DO NOTHING — idempotent).
     * @return 실제 INSERT된 row 수
     */
    fun saveAll(candles: List<Candle>): Int

    /**
     * 캔들 일괄 upsert (ON CONFLICT DO UPDATE).
     *
     * 1m gap fill 이후 3m~12h 합산봉을 재생성할 때 기존 aggregate candle 값도
     * 교정되어야 하므로, 일반 KIS raw 적재와 분리된 명시적 경로로만 사용한다.
     *
     * @return INSERT 또는 UPDATE 된 row 수
     */
    fun upsertAll(candles: List<Candle>): Int

    fun countByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): Long

    fun countByCodeAndIntervalIn(
        code: String,
        intervals: List<CandleInterval>,
    ): Long

    /**
     * 특정 종목·interval 의 전체 candle list 반환 (ts ASC).
     *
     * ADR 0062 §9 — AggregateBackfillRunner 가 1m 전량 → 3m·5m·15m·30m·90m·1h·4h·12h 합산 시 사용.
     * 30 거래일 1m 기준 ~10k~20k row 예상 (in-memory aggregation 가능 범위).
     */
    fun findByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Candle>

    /**
     * 특정 종목·interval 의 최신 candle N개 반환 (ts ASC).
     *
     * 전략 평가·실시간 신호처럼 최근 lookback만 필요한 path는 전체 시계열을 읽지 말고
     * DB에서 `ORDER BY ts DESC LIMIT N`으로 제한해야 한다. 기본 구현은 테스트/임시 구현 호환용이며,
     * 실제 JPA adapter는 native DB limit 조회로 override한다.
     */
    fun findLatestByCodeAndInterval(
        code: String,
        interval: CandleInterval,
        limit: Int,
    ): List<Candle> = findByCodeAndInterval(code, interval).takeLast(limit.coerceAtLeast(1))

    /**
     * beforeExclusive 이전 candle 중 최신 N개 반환 (ts ASC).
     *
     * boundary candle을 별도로 받은 실시간 평가에서 DB에 같은 boundary row가 있더라도 중복하지 않기 위해
     * `ts < beforeExclusive`로 조회한다.
     */
    fun findLatestBeforeByCodeAndInterval(
        code: String,
        interval: CandleInterval,
        beforeExclusive: Instant,
        limit: Int,
    ): List<Candle> =
        findByCodeAndInterval(code, interval)
            .asSequence()
            .filter { it.ts.toJavaInstant().isBefore(beforeExclusive) }
            .toList()
            .takeLast(limit.coerceAtLeast(1))

    /**
     * 특정 종목·interval 의 마지막 candle ts.
     *
     * hotfix 2026-05-15: CandleBootstrapRunner 가 부팅 시 N일 stale 검출하여 cold-start 재트리거.
     * row 0건이면 null 반환.
     */
    fun findMaxTsByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): Instant?

    /**
     * 특정 종목·interval 의 범위 내 모든 ts 조회 (ts ASC) — ADR 0086 §2-2 add-only.
     *
     * T-CC Phase 2-A: CandleGapDetector 가 expected sequence diff 산출 시 사용.
     * 전체 fetch(findByCodeAndInterval) 대비 효율 — 30 거래일 윈도우 LOCK + Instant 만 반환.
     *
     * @param code 종목 코드
     * @param interval 캔들 인터벌
     * @param from 시작 (inclusive)
     * @param to 종료 (inclusive)
     * @return 범위 내 모든 ts (ASC). row 0건이면 empty list.
     */
    fun findAllTsByCodeAndIntervalBetween(
        code: String,
        interval: CandleInterval,
        from: Instant,
        to: Instant,
    ): List<Instant>
}
