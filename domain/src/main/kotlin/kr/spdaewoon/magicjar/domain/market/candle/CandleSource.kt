package kr.spdaewoon.magicjar.domain.market.candle

/**
 * 캔들 데이터 출처 등급 (P0-D — 감사 2026-09-17 §1-②, canon §1 진실원 우선순위).
 *
 * ## 왜 enum + priority 인가
 * 종전 `candle.source`는 VARCHAR(20) 자유 문자열 12종(`KIS`·`KIS-BACKFILL`·`KIS-MKT-DAILY-BF`·
 * `KIS-MKT-DAILY-SNAP`·`KIS-NOTICK-FALLBACK`·`KIS-RANGE-GAPFILL`·`KIS-POLLING`·`KIS_DAILY_CLOSE`·
 * `KIS_KSDINFO` …)이 하이픈/언더스코어 혼재로 섞였고, 정작 정합성 판정에는 안 쓰였다(감사 §1-②).
 * 본 enum은 각 출처를 **신뢰 등급(priority)** 으로 서열화해 [CandleWriteArbiter] precedence 판정과
 * DB UPSERT 가드(P0-B)의 단일 근거로 삼는다. priority 숫자가 **작을수록 신뢰가 높다**(1=최고).
 *
 * ## 순수성 (LOCK)
 * Spring·JPA·IO 의존 0. 문자열 ↔ enum 매핑은 결정론. 기존 문자열 라벨과의 하위호환을 [fromLabel]이 흡수한다.
 *
 * ## precedence 규칙 (canon §1)
 * 같은 (code, interval, ts) 봉 충돌 시 priority 값이 더 작은(신뢰 높은) source가 이긴다.
 * 동급(같은 priority)은 최신 write가 이긴다(WS live 동일 등급 재갱신 허용).
 */
enum class CandleSource(
    val priority: Int,
    /** DB 영속용 표준 라벨(대문자·언더스코어 통일 — VARCHAR(20) 이내). */
    val label: String,
) {
    /** 공식 마감 확정 — 장마감 finalizer·야간 멀티시세 snapshot(마감 후). 최고 신뢰. */
    OFFICIAL_DAILY_CLOSE(1, "KIS_DAILY_CLOSE"),

    /** REST 과거 확정 — 갭 backfill·차트 진입 range gapfill·전종목 일봉 캠페인. */
    REST_CONFIRMED(2, "KIS_REST_CONFIRMED"),

    /** WS 실시간 마감봉 — tick 집계 1m closing·상위봉 합산. */
    WS_LIVE(3, "KIS_WS_LIVE"),

    /** polling·fallback — WS silent 종목 REST 보완·degraded polling. 최저 신뢰(빈 칸만 채움). */
    POLLING_FALLBACK(4, "KIS_POLLING_FALLBACK"),
    ;

    companion object {
        /**
         * 기존 자유 문자열 라벨 → enum 매핑(하위호환). 미상 라벨은 [WS_LIVE](등급 3, 보수적 중간)로 보되
         * 호출자가 명시 source를 주면 그 값을 우선한다. 대소문자·하이픈/언더스코어 무시.
         */
        fun fromLabel(raw: String?): CandleSource {
            if (raw.isNullOrBlank()) return WS_LIVE
            val norm = raw.trim().uppercase().replace('-', '_')
            return when {
                norm.contains("DAILY_CLOSE") || norm.contains("SNAP") -> OFFICIAL_DAILY_CLOSE
                norm.contains("BACKFILL") ||
                    norm.contains("GAPFILL") ||
                    norm.contains("MKT_DAILY") ||
                    norm.contains("RANGE") ||
                    norm.contains("KSDINFO") -> REST_CONFIRMED
                norm.contains("POLLING") || norm.contains("NOTICK") || norm.contains("FALLBACK") -> POLLING_FALLBACK
                else -> WS_LIVE
            }
        }
    }
}
