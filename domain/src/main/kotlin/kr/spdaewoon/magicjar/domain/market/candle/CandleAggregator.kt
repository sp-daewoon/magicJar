package kr.spdaewoon.magicjar.domain.market.candle

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 1분봉(ONE_MIN) candle list 를 목표 interval 로 합산하는 순수 함수 도메인 컴포넌트.
 *
 * 결정 이력:
 *   - ADR 0019 §2-5 "합성 폐기" 결정 **revoke** (ADR 0062 채택 — 2026-05-11).
 *     사용자(최고 관리자) 결정: 합산 위치 = A-1 백엔드 precomputed,
 *     source 시간 범위 = 옵션 2 과거 30 거래일, UI 매트릭스 = 5종 활성화.
 *   - 2026-05-30 TradingView 정합 패치 — boundary 정렬은 KRX 정규장(KST 09:00~15:30) 기준.
 *
 * 원칙:
 *   - Spring·JPA 의존 0 (도메인 모듈 ADR 0004 헥사고날 정합).
 *   - 외부 의존 0 (Kotlin stdlib + kotlinx.datetime 만).
 *   - 순수 함수 — 입력 1m candle list → 출력 합산 candle list. 부수효과 0, 결정론적.
 *   - mock 합성 금지 (feedback_no_mock_data 정합) — boundary 내 1m 0건이면 해당 bar 생략.
 *
 * 합산 식 (group_by(boundary)):
 *   open  = first.openRaw
 *   high  = max(highRaw)
 *   low   = min(lowRaw)
 *   close = last.closeRaw
 *   vol   = sum(volume)
 *   source = first.source (원천 출처 유지, 보통 "KIS")
 *
 * boundary 정렬 (KST = UTC+9, KRX 정규장 09:00~15:30 기준):
 *   5m  : 09:00, 09:05, ..., 15:25  (78 bars/일)
 *   15m : 09:00, 09:15, ..., 15:15  (26 bars/일)
 *   30m : 09:00, 09:30, ..., 15:00  (13 bars/일)
 *   90m : 09:00, 10:30, 12:00, 13:30, 15:00  (마지막 30분 partial)
 *   1h  : 09:00, 10:00, ..., 15:00  (마지막 30분 partial)
 *   4h  : 09:00, 13:00  (마지막 2시간 30분 partial)
 *   12h : 09:00  (정규장 전체 1 bar, TradingView 세션 truncation 정합)
 *
 * 5m·15m·30m·1h 는 KST 자정 기준의 단순 modulo 로도 자연스럽게 09:00·09:05·... 등에 정렬됨
 * (자정 기준 540분 = 09:00, 540/5·540/15·540/30·540/60 모두 정수).
 *
 * 90m 는 09:00 anchor 기준 90분 간격이지만 15:00~15:30 partial bar가 있어
 * 별도 boundary 테이블을 두고 lower_bound 매핑.
 *
 * 경계 케이스:
 *   - candles 빈 list           → 빈 list 반환
 *   - target == ONE_MIN         → IllegalArgumentException
 *   - target ∉ 지원 7종         → IllegalArgumentException
 *   - 입력 candle 의 interval 이 ONE_MIN 이 아님 → IllegalArgumentException
 *   - 한 boundary 내 1m 0건     → 해당 bar 생략 (mock 합성 금지)
 *   - 거래정지·중단 일자        → 자연스럽게 boundary 내 1m 0건이라 생략
 *
 * @see kr.spdaewoon.magicjar.domain.market.candle.Candle
 * @see kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
 */
object CandleAggregator {
    private val KST: TimeZone = TimeZone.of("Asia/Seoul")

    /** KRX 정규장 시작 = KST 09:00. 자정으로부터 540분. */
    private const val REGULAR_OPEN_MIN: Int = 9 * 60

    /** KRX 정규장 종료 = KST 15:30. 자정으로부터 930분. */
    private const val REGULAR_CLOSE_MIN: Int = 15 * 60 + 30

    /** KRX 정규장 90m boundary anchor (KST hour·minute, 자정 기준 분 단위). */
    private val NINETY_MIN_BOUNDARIES_KST_MIN: IntArray =
        intArrayOf(
            9 * 60 + 0, // 09:00
            10 * 60 + 30, // 10:30
            12 * 60 + 0, // 12:00
            13 * 60 + 30, // 13:30
            15 * 60 + 0, // 15:00 (정규장 종료 15:30 때문에 30분 partial)
        )

    /** 지원 target interval 7종 (ADR 0062 UI 매트릭스 + FVG/OB 고타임프레임). */
    private val SUPPORTED_TARGETS: Set<CandleInterval> =
        setOf(
            CandleInterval.FIVE_MIN,
            CandleInterval.FIFTEEN_MIN,
            CandleInterval.THIRTY_MIN,
            CandleInterval.NINETY_MIN,
            CandleInterval.ONE_HOUR,
            CandleInterval.FOUR_HOUR,
            CandleInterval.TWELVE_HOUR,
        )

    /**
     * 1분봉 list 를 [target] interval 로 합산.
     *
     * 입력 candles 는 정렬 무관 (내부에서 ts ASC 정렬). 모든 candle 의 interval 은 ONE_MIN 이어야 함.
     *
     * @param candles 1m candle list
     * @param target  목표 합산 interval (FIVE_MIN·FIFTEEN_MIN·THIRTY_MIN·NINETY_MIN·ONE_HOUR·FOUR_HOUR·TWELVE_HOUR)
     * @return 합산된 candle list (ts ASC 정렬, target boundary 정합)
     * @throws IllegalArgumentException target 이 ONE_MIN 이거나 지원 대상 외이거나, 입력에 비-1m candle 포함
     */
    fun aggregate(
        candles: List<Candle>,
        target: CandleInterval,
    ): List<Candle> {
        require(target != CandleInterval.ONE_MIN) {
            "target=$target — ONE_MIN 합산 불가 (1m → 1m 항등)"
        }
        require(target in SUPPORTED_TARGETS) {
            "target=$target — 지원하지 않는 interval (지원: 5m·15m·30m·90m·1h·4h·12h)"
        }
        if (candles.isEmpty()) return emptyList()

        val invalidInterval = candles.firstOrNull { it.interval != CandleInterval.ONE_MIN }
        require(invalidInterval == null) {
            "입력 candles 는 모두 ONE_MIN 이어야 함 (발견: interval=${invalidInterval?.interval})"
        }

        // ts 동일·코드 동일 boundary 그룹화. groupBy 는 순서를 보존하므로 sortedBy 선행 시
        // 그룹 내부도 ts ASC. groupingBy + 누적도 가능하나 가독성 우선.
        val sorted =
            candles
                .asSequence()
                .filter { isRegularSessionMinute(it.ts) }
                .sortedBy { it.ts.epochSeconds }
                .toList()
        if (sorted.isEmpty()) return emptyList()

        val grouped: Map<Long, List<Candle>> =
            sorted.groupBy { computeBoundaryEpochSec(it.ts, target) }

        return grouped.entries
            .map { (boundaryEpoch, group) ->
                aggregateGroup(boundaryEpoch, group, target)
            }.sortedBy { it.ts.epochSeconds }
    }

    /**
     * 한 boundary 그룹의 1m candle list 를 1건의 합산 candle 로 reduce.
     */
    private fun aggregateGroup(
        boundaryEpochSec: Long,
        group: List<Candle>,
        target: CandleInterval,
    ): Candle {
        val first = group.first()
        val last = group.last()
        return Candle(
            id = null,
            code = first.code,
            interval = target,
            ts = Instant.fromEpochSeconds(boundaryEpochSec),
            openRaw = first.openRaw,
            highRaw = group.maxOf { it.highRaw },
            lowRaw = group.minOf { it.lowRaw },
            closeRaw = last.closeRaw,
            openAdj = first.openAdj,
            highAdj = group.mapNotNull { it.highAdj }.maxOrNull(),
            lowAdj = group.mapNotNull { it.lowAdj }.minOrNull(),
            closeAdj = last.closeAdj,
            volume = group.sumOf { it.volume },
            source = first.source,
        )
    }

    /**
     * 주어진 ts 가 속하는 [target] boundary 의 epoch second 를 계산.
     *
     * 알고리즘:
     *   1. ts 를 KST LocalDateTime 으로 변환.
     *   2. KST 자정 기준 분 단위 오프셋(0..1439) 계산.
     *   3. target 별 boundary 분 단위 오프셋 산출.
     *   4. KST 자정의 epoch second + (boundary 분 × 60) 으로 복원.
     *
     * 5m·15m·30m·1h 는 (분 / 간격) × 간격 의 단순 modulo. 540(=09:00) 도 정수 배수라
     * 09:00·09:05 등 KRX 정규장 boundary 에 자연 정렬.
     *
     * 90m 는 09:00 anchor 기준 90분 간격이며, 마지막 15:00~15:30은 세션 종료로 잘리는 partial bar.
     */
    internal fun computeBoundaryEpochSec(
        ts: Instant,
        target: CandleInterval,
    ): Long {
        val kstLdt = ts.toLocalDateTime(KST)
        val minOfDay = kstLdt.hour * 60 + kstLdt.minute
        val midnightEpochSec =
            ts.epochSeconds - (minOfDay * 60L) - kstLdt.second.toLong()

        val boundaryMin =
            when (target) {
                CandleInterval.FIVE_MIN -> (minOfDay / 5) * 5
                CandleInterval.FIFTEEN_MIN -> (minOfDay / 15) * 15
                CandleInterval.THIRTY_MIN -> (minOfDay / 30) * 30
                CandleInterval.ONE_HOUR -> (minOfDay / 60) * 60
                CandleInterval.FOUR_HOUR -> fourHourBoundaryMin(minOfDay)
                CandleInterval.TWELVE_HOUR -> REGULAR_OPEN_MIN
                CandleInterval.NINETY_MIN -> ninetyMinBoundaryMin(minOfDay)
                else ->
                    throw IllegalArgumentException(
                        "computeBoundaryEpochSec: 지원하지 않는 target=$target",
                    )
            }

        return midnightEpochSec + boundaryMin * 60L
    }

    private fun isRegularSessionMinute(ts: Instant): Boolean {
        val kstLdt = ts.toLocalDateTime(KST)
        val minOfDay = kstLdt.hour * 60 + kstLdt.minute
        return minOfDay >= REGULAR_OPEN_MIN && minOfDay < REGULAR_CLOSE_MIN
    }

    /**
     * 자정 기준 분 [minOfDay] 가 속하는 90m anchor 분 단위 오프셋을 반환.
     *
     * lower_bound: 8개 anchor 중 minOfDay 이하 최대 anchor.
     * minOfDay < 첫 anchor(540) 인 경우 → 정규장 밖이므로 실제 합산에서는 호출되지 않지만,
     * 안전 default 로 첫 anchor 를 반환한다.
     */
    private fun ninetyMinBoundaryMin(minOfDay: Int): Int {
        if (minOfDay < NINETY_MIN_BOUNDARIES_KST_MIN.first()) {
            return NINETY_MIN_BOUNDARIES_KST_MIN.first()
        }
        var picked = NINETY_MIN_BOUNDARIES_KST_MIN.first()
        for (anchor in NINETY_MIN_BOUNDARIES_KST_MIN) {
            if (anchor <= minOfDay) picked = anchor else break
        }
        return picked
    }

    /**
     * 4h boundary는 KRX 정규장 시작(09:00)을 anchor로 09:00·13:00 두 구간에 매핑한다.
     * 실제 1m 데이터가 없는 분은 만들지 않으므로 종목별 조기 종료 시 마지막 구간은 자연스럽게 partial bar가 된다.
     */
    private fun fourHourBoundaryMin(minOfDay: Int): Int {
        if (minOfDay < REGULAR_OPEN_MIN) return REGULAR_OPEN_MIN
        val offset = minOfDay - REGULAR_OPEN_MIN
        return REGULAR_OPEN_MIN + (offset / 240) * 240
    }
}
