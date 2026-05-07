package kr.spdaewoon.magicjar.consumer.infrastructure.session

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 시장 세션 결정 어댑터 — 1차 시간 기반 stub (spec §8-2 정합).
 *
 * 1차 구현 전략:
 * - 평일 09:00~15:30 KST → Regular
 * - 평일 08:30~09:00 KST → PreMarket
 * - 평일 15:40~16:00 KST → PostMarket
 * - 토·일 → Closed
 *
 * 공휴일 처리:
 * KIS MCP/open-trading-api 레포에서 휴장일 조회 endpoint 검색 결과 없음.
 * KIS 포털(apiportal.koreainvestment.com)에도 휴장일 조회 endpoint 미확인.
 *
 * [사용자 알림] KIS API에 공휴일/휴장일 조회 endpoint가 존재하지 않거나 KIS-first 정책으로
 * 확인하지 못했습니다. 현재 구현은 평일/주말만 구분 (공휴일 인지 없음).
 * 공휴일 처리는 T-MS1 트랙에서 출처 결정 후 별도 PR로 보강 필요합니다.
 *
 * ADR 0048 §2-5 정합:
 * - Regular: 풀 가동 (1+30+10 slot)
 * - PreMarket·PostMarket: REST polling 30초 주기 (시간외 KIS WS 미지원)
 * - Closed: stream unsubscribe + slot 회수
 */
@Component
class SimpleMarketSessionResolver : MarketSessionResolver {
    private val log = LoggerFactory.getLogger(SimpleMarketSessionResolver::class.java)

    private val kst = TimeZone.of("Asia/Seoul")

    override fun resolve(instant: Instant): MarketSession {
        val kstDateTime = instant.toLocalDateTime(kst)
        val dayOfWeek = kstDateTime.dayOfWeek
        val hour = kstDateTime.hour
        val minute = kstDateTime.minute

        // 주말 → Closed
        if (dayOfWeek == kotlinx.datetime.DayOfWeek.SATURDAY ||
            dayOfWeek == kotlinx.datetime.DayOfWeek.SUNDAY
        ) {
            return MarketSession.Closed(reason = "주말")
        }

        // 시각 → HHmm 정수 변환
        val timeInt = hour * 100 + minute

        return when {
            // 정규장 09:00~15:30
            timeInt in 900..1529 -> MarketSession.Regular

            // 장전 시간외 08:30~09:00
            timeInt in 830..859 -> MarketSession.PreMarket

            // 장후 시간외 15:40~16:00
            timeInt in 1540..1600 -> MarketSession.PostMarket

            // 그 외 (야간·심야) → Closed
            else ->
                MarketSession.Closed(
                    reason = "장외시간",
                )
        }
    }
}
