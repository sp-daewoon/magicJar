package com.mj.domain.order

/**
 * 주문 거부 사유 sealed class. spec §4.2, ADR 0034 §2-2, ADR 0042.
 *
 * Guard chain 6단 각각의 reject 사유 + KIS API 오류 + 내부 오류 포함.
 * sealed 구조 → exhaustive when 강제 (신규 사유 추가 시 컴파일 오류).
 *
 * DB 저장 시 code 필드를 VARCHAR(64) 컬럼에 저장.
 */
sealed class RejectReason(
    /** DB 저장용 코드 문자열. */
    val code: String,
) {
    /** @Order(100) KillSwitchGuard — GLOBAL KillSwitch 활성 상태. */
    data object KillSwitchActive : RejectReason("KILL_SWITCH")

    /** @Order(150) MarketSessionGuard — 영업일이지만 장 시간 외. */
    data object MarketSessionClosed : RejectReason("SESSION_CLOSED")

    /** @Order(150) MarketSessionGuard — 비영업일 (주말·공휴일). */
    data object MarketNotBusinessDay : RejectReason("NOT_BUSINESS_DAY")

    /** @Order(200) DailyOrderLimitGuard — 일별 주문 횟수 또는 명목금액 초과. */
    data object DailyOrderLimitExceeded : RejectReason("DAILY_LIMIT")

    /** @Order(250) PositionLimitGuard — 종목당 또는 전체 포지션 비중 초과. (T12 트랙 구현) */
    data object PositionLimitExceeded : RejectReason("POSITION_LIMIT")

    /** @Order(280) DrawdownStopGuard — 일일·주간·전체 drawdown 한도 초과. (T14 트랙 구현) */
    data object DrawdownStopTriggered : RejectReason("DRAWDOWN_STOP")

    /** @Order(300) PriceSanityGuard — tick stale 또는 주문가 이상. */
    data object PriceSanityFailed : RejectReason("PRICE_SANITY")

    /**
     * KIS REST API 오류 응답 (rt_cd != "0").
     * EGW00202 GW 라우팅 오류, 40100000 영업일 아님 등.
     *
     * @param rtCd KIS 응답 rt_cd 코드 (예: "40100000", "EGW00202")
     * @param msg  KIS 응답 msg1 원문
     */
    data class KisApiError(
        val rtCd: String,
        val msg: String,
    ) : RejectReason("KIS_API:$rtCd")

    /**
     * 시스템 내부 오류 (예: DB 쓰기 실패, 직렬화 오류 등).
     *
     * @param message 내부 오류 메시지
     */
    data class Internal(
        val message: String,
    ) : RejectReason("INTERNAL")

    /**
     * code + 상세 메시지 조합 (audit trail DB 저장용).
     * orders.reject_detail 컬럼에 저장.
     */
    fun toDetail(): String =
        when (this) {
            is KillSwitchActive -> "GLOBAL KillSwitch 활성 — 매매 전면 차단"
            is MarketSessionClosed -> "장 시간 외 — 정규장(09:00~15:30)·동시호가(08:30~09:00)·장후(15:30~18:00) 아님"
            is MarketNotBusinessDay -> "비영업일 — 주말 또는 공휴일"
            is DailyOrderLimitExceeded -> "일별 주문 한도 초과 (건수 또는 명목금액)"
            is PositionLimitExceeded -> "포지션 한도 초과 (종목당 또는 전체 비중)"
            is DrawdownStopTriggered -> "Drawdown 한도 초과 (일일·주간·전체)"
            is PriceSanityFailed -> "가격 이상 감지 (tick stale 또는 호가 범위 이탈)"
            is KisApiError -> "KIS API 오류: rt_cd=$rtCd msg=$msg"
            is Internal -> "내부 오류: $message"
        }
}
