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
 *   - ADR 0063 NXT cover 결합 — boundary 정렬은 NXT 거래시간(KST 08:00 ~ 19:55) 기준.
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
 * boundary 정렬 (KST = UTC+9, NXT cover 08:00~19:55 기준):
 *   5m  : 08:00, 08:05, ..., 19:55  (144 bars/일)
 *   15m : 08:00, 08:15, ..., 19:45  (48 bars/일)
 *   30m : 08:00, 08:30, ..., 19:30  (24 bars/일)
 *   90m : 08:00, 09:30, 11:00, 12:30, 14:00, 15:30, 17:00, 18:30  (8 bars/일)
 *   1h  : 08:00, 09:00, ..., 19:00  (12 bars/일)
 *
 * 5m·15m·30m·1h 는 KST 자정 기준의 단순 modulo 로도 자연스럽게 08:00·08:05·... 등에 정렬됨
 * (자정 기준 480분 = 08:00, 480/5·480/15·480/30·480/60 모두 정수).
 *
 * 90m 는 비균등 간격이므로 별도 8개 boundary 테이블을 두고 lower_bound 매핑.
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

    /** NXT cover 거래시간 시작 = KST 08:00. 자정으로부터 480 분. */
    private const val KST_MIDNIGHT_TO_OPEN_MIN: Int = 8 * 60

    /** NXT cover 90m boundary anchor (KST hour·minute, 자정 기준 분 단위). */
    private val NINETY_MIN_BOUNDARIES_KST_MIN: IntArray =
        intArrayOf(
            8 * 60 + 0, // 08:00
            9 * 60 + 30, // 09:30
            11 * 60 + 0, // 11:00
            12 * 60 + 30, // 12:30
            14 * 60 + 0, // 14:00
            15 * 60 + 30, // 15:30
            17 * 60 + 0, // 17:00
            18 * 60 + 30, // 18:30
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
     * @throws IllegalArgumentException target 이 ONE_MIN 이거나 지원 5종 외이거나, 입력에 비-1m candle 포함
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

        if (target == CandleInterval.TWELVE_HOUR) {
            return aggregateTwelveHourActualLast(candles)
        }

        // ts 동일·코드 동일 boundary 그룹화. groupBy 는 순서를 보존하므로 sortedBy 선행 시
        // 그룹 내부도 ts ASC. groupingBy + 누적도 가능하나 가독성 우선.
        val sorted = candles.sortedBy { it.ts.epochSeconds }
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
     * 5m·15m·30m·1h 는 (분 / 간격) × 간격 의 단순 modulo. 480(=08:00) 도 정수 배수라
     * 08:00·08:05 등 NXT cover boundary 에 자연 정렬.
     *
     * 90m 는 비균등 — 자정 기준 분 단위 오프셋이 NXT 8개 anchor 중 어느 구간에 속하는지
     * lower_bound 로 매핑. 08:00 미만(예: 새벽 1m) 은 ADR 0062 정책상 정규 영역 밖이지만,
     * 결정론을 위해 "직전 거래일 18:30 anchor" 가 아닌 "당일 18:30" 로 backward 매핑하지 않고
     * 자정 기준 0 분(= 00:00) 을 별도 anchor 로 두지 않는다 → 첫 anchor(08:00) 미만은 직전일
     * 마지막 anchor(18:30) 로 backward 매핑하는 대신 첫 anchor 직전(자정 1분 전) 에 묶이는 것이
     * 자연스러우므로, 자정 기준 분 < 480(08:00) 이면 직전일 18:30 anchor 로 backward.
     *
     * 단 ADR 0062 정책은 NXT cover 시간 안의 1m 만 합산 대상이므로 실제로 이 backward path 는
     * 거의 호출되지 않음 (가드 보호망 목적).
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
                CandleInterval.NINETY_MIN -> ninetyMinBoundaryMin(minOfDay)
                else ->
                    throw IllegalArgumentException(
                        "computeBoundaryEpochSec: 지원하지 않는 target=$target",
                    )
            }

        // 90m backward path (minOfDay < 480) 처리 — 직전일 18:30 = 자정 -330분.
        return if (target == CandleInterval.NINETY_MIN && minOfDay < NINETY_MIN_BOUNDARIES_KST_MIN.first()) {
            midnightEpochSec - 24 * 3600L + NINETY_MIN_BOUNDARIES_KST_MIN.last() * 60L
        } else {
            midnightEpochSec + boundaryMin * 60L
        }
    }

    /**
     * 자정 기준 분 [minOfDay] 가 속하는 90m anchor 분 단위 오프셋을 반환.
     *
     * lower_bound: 8개 anchor 중 minOfDay 이하 최대 anchor.
     * minOfDay < 첫 anchor(480) 인 경우 → 호출자가 backward 처리하므로 여기서는
     * 안전 default 로 첫 anchor 를 반환 (실제로는 사용되지 않음).
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
     * 4h boundary는 NXT cover 시작(08:00)을 anchor로 08:00·12:00·16:00 세 구간에 매핑한다.
     * 실제 1m 데이터가 없는 분은 만들지 않으므로 종목별 조기 종료 시 마지막 구간은 자연스럽게 partial bar가 된다.
     */
    private fun fourHourBoundaryMin(minOfDay: Int): Int {
        if (minOfDay < KST_MIDNIGHT_TO_OPEN_MIN) return KST_MIDNIGHT_TO_OPEN_MIN
        val offset = minOfDay - KST_MIDNIGHT_TO_OPEN_MIN
        return KST_MIDNIGHT_TO_OPEN_MIN + (offset / 240) * 240
    }

    /**
     * 12h는 사용자가 지적한 정책대로 고정 08:00~20:00 한 개 봉을 만들지 않는다.
     * KST 날짜·종목별 실제 마지막 1m candle을 끝점으로 삼고, 거기서 최대 720개 1분봉을 역산해 하나의 bar로 합산한다.
     */
    private fun aggregateTwelveHourActualLast(candles: List<Candle>): List<Candle> {
        val sorted = candles.sortedBy { it.ts.epochSeconds }
        return sorted
            .groupBy { candle -> candle.code to candle.ts.toLocalDateTime(KST).date }
            .values
            .mapNotNull { dayCandles ->
                val daySorted = dayCandles.sortedBy { it.ts.epochSeconds }
                val last = daySorted.lastOrNull() ?: return@mapNotNull null
                val windowStartEpochSec = last.ts.epochSeconds - 719L * 60L
                val window = daySorted.filter { it.ts.epochSeconds >= windowStartEpochSec }
                if (window.isEmpty()) null else aggregateGroup(window.first().ts.epochSeconds, window, CandleInterval.TWELVE_HOUR)
            }.sortedBy { it.ts.epochSeconds }
    }
}
