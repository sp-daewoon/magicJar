package com.mj.domain.market.session

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** KST(Asia/Seoul) ZoneId 헬퍼. 시스템 전역에서 공유. */
fun zoneKst(): ZoneId = ZoneId.of("Asia/Seoul")

/**
 * KRX 단일 거래소 기준 장 상태 FSM. spec §4.1, ADR 0032.
 *
 * KRX 운영 시간 (2026-05 기준):
 *   - 동시호가 전 / 시간외 단일가 준비: 08:30~09:00 (PRE_MARKET)
 *   - 정규장: 09:00~15:30 (OPEN)
 *   - 시간외 단일가·장후: 15:30~18:00 (POST_MARKET)
 *   - 점심시간: 2000년부터 폐지 — LUNCH 상태 없음 (ADR 0032 §D2)
 *   - 비영업일(주말·공휴일): CLOSED / HOLIDAY
 *
 * sealed class 이유: exhaustive when 강제 → 신규 상태 추가 시 컴파일 오류로 누락 방지.
 */
sealed class MarketSessionState {
    /** 09:00 이전 영업일 (동시호가 포함, 08:30~09:00). */
    data object PreMarket : MarketSessionState()

    /** 09:00~15:30 정규장. */
    data object Open : MarketSessionState()

    /** 15:30 이후 영업일 (시간외 단일가·장후 포함). */
    data object PostMarket : MarketSessionState()

    /** 비영업일 — 주말. reason은 "Saturday" / "Sunday". */
    data class Closed(
        val reason: String,
    ) : MarketSessionState()

    /** 캘린더에 명시된 공휴일·임시휴장. */
    data class Holiday(
        val name: String,
        val reason: String,
    ) : MarketSessionState()

    /** KIS 정규장 체결이 가능한 상태인가. */
    fun isTradingAllowed(): Boolean = this is Open

    /**
     * 주문을 KIS에 시도할 수 있는 상태인가.
     * (PRE/POST는 KIS가 부분적으로 허용, 1차는 PASS로 처리해서 KIS 거부 시 audit)
     */
    fun isOrderAcceptable(): Boolean = this is Open || this is PreMarket || this is PostMarket
}

/**
 * 특정 시점의 장 상태 스냅샷. spec §4.1.
 *
 * @param state      현재 장 상태
 * @param kstAt      스냅샷 생성 시각 (KST OffsetDateTime)
 * @param lastBusinessDate 가장 최근 영업일 (휴장 시 fallback asOf 기준)
 * @param nextOpenAt 다음 정규장 개시 시각 (알 수 없으면 null)
 */
data class MarketSession(
    val state: MarketSessionState,
    val kstAt: OffsetDateTime,
    val lastBusinessDate: LocalDate,
    val nextOpenAt: OffsetDateTime?,
)
