package kr.spdaewoon.magicjar.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * MARKET_DAILY 야간 멀티시세 snapshot 설정 (ADR 0118 R2 — §2-C·§2-D).
 *
 * 매 영업일 23:00 KST 1회: MARKET_DAILY universe(보통주 ~2,517) → 30종목 chunk →
 * `intstock-multprice`(FHKST11300006) 호출 → 그날(today) 1d 봉 ON CONFLICT DO UPDATE 교정.
 *
 * ★ enabled default = false (canary 전 운영 영향 신중 — ADR 0118 §6 롤백 toggle).
 *   off 시 야간 갱신 0 (기존 active universe·DailyCloseFinalizer 흐름 무영향).
 *
 * ★ today-close-authority (§2-D 결재 ③ LOCK = NXT_INTEGRATED, 단 NXT 실측 PENDING):
 *   - NXT_INTEGRATED: 멀티시세 시장구분 "NX"로 NXT 통합(08:00~20:00) 종가 권위. default(결재값).
 *   - KRX_REGULAR_WITH_FINALIZER_FALLBACK: 멀티시세 시장구분 "J"로 정규장(09:00~15:30) 종가 한정.
 *       NXT after-hour 반영은 DailyCloseFinalizer(WS 집계 20:00) 경로에 위임(폴백).
 *   R1 NXT probe(KisMultiPriceNxtProbeIT)는 PENDING(장중 실행 대기)이므로 default는
 *   결재값(NXT_INTEGRATED)으로 두되, 실측 완료 후 토글 한 줄로 안전하게 뒤집을 수 있다.
 *   추측으로 "멀티시세가 NXT 준다" 단정 금지 — 토글로 분기 (ADR 0118 §2-D LOCK).
 */
@ConfigurationProperties(prefix = "magicjar.market-daily-snapshot")
data class MarketDailySnapshotProperties(
    /** 스케줄러 활성화 — canary 전 운영 영향 신중 default false (ADR 0118 §6 롤백 toggle). */
    val enabled: Boolean = false,
    /** 발사 cron (KST) — default 23:00 (NXT 20:00 마감 + 3h 여유 · KIS 야간점검 23:40 이전). */
    val cron: String = "0 0 23 * * *",
    /** today 1d 종가 권위 source (§2-D 결재 ③). default = NXT_INTEGRATED(결재값·NXT 실측 PENDING). */
    val todayCloseAuthority: TodayCloseAuthority = TodayCloseAuthority.NXT_INTEGRATED,
    /** rate-limit acquire timeout(ms) — BG 버킷 혼잡 시 대기 상한. */
    val acquireTimeoutMillis: Long = 5000L,
) {
    /**
     * snapshot이 사용할 KIS 멀티시세 시장구분 코드.
     *   NXT_INTEGRATED → "NX" (NXT 통합), KRX_REGULAR_* → "J" (정규장).
     * raw spec: FID_COND_MRKT_DIV_CODE_N 입력값 "J"(KRX) 또는 "NX"(NXT).
     * https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/intstock_multprice/intstock_multprice.py
     */
    fun marketDivCode(): String =
        when (todayCloseAuthority) {
            TodayCloseAuthority.NXT_INTEGRATED -> "NX"
            TodayCloseAuthority.KRX_REGULAR_WITH_FINALIZER_FALLBACK -> "J"
        }
}

/**
 * today 1d 종가 권위 source 토글 (ADR 0118 §2-D 결재 ③).
 *
 * 결재 ③ LOCK = NXT 통합 권위. 단 멀티시세 endpoint가 NXT 통합값을 실제로 반환하는지는
 * R1 raw 실측(KisMultiPriceNxtProbeIT) PENDING. default는 결재값(NXT_INTEGRATED)으로 두고,
 * 실측이 "정규장만 반환"으로 판명되면 KRX_REGULAR_WITH_FINALIZER_FALLBACK로 토글 — follow-up.
 */
enum class TodayCloseAuthority {
    /** 멀티시세 "NX" — NXT 통합(08:00~20:00) 종가 권위. default(결재 ③ 값). */
    NXT_INTEGRATED,

    /** 멀티시세 "J" — 정규장(09:00~15:30) 종가 한정. NXT after-hour는 DailyCloseFinalizer 폴백. */
    KRX_REGULAR_WITH_FINALIZER_FALLBACK,
}
