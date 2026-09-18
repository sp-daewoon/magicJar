package kr.spdaewoon.magicjar.domain.market.candle

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * CandleWriteArbiter precedence 판정 테스트 (P0-A Phase A-1).
 *
 * canon §1 진실원 우선순위: OFFICIAL_DAILY_CLOSE(1) < REST_CONFIRMED(2) < WS_LIVE(3) < POLLING_FALLBACK(4).
 * priority 값이 작을수록 신뢰 높음. 낮은 신뢰가 높은 신뢰를 덮지 못한다.
 */
class CandleWriteArbiterTest {
    @Test
    fun `신규 삽입(기존 봉 없음)은 항상 APPLY`() {
        CandleWriteArbiter.decide(existingSource = null, incomingSource = CandleSource.POLLING_FALLBACK) shouldBe WriteVerdict.APPLY
        CandleWriteArbiter.decide(existingSource = null, incomingSource = CandleSource.WS_LIVE) shouldBe WriteVerdict.APPLY
    }

    @Test
    fun `높은 신뢰가 낮은 신뢰를 덮는다 — 공식마감가가 WS live 덮기 APPLY`() {
        CandleWriteArbiter.decide(CandleSource.WS_LIVE, CandleSource.OFFICIAL_DAILY_CLOSE) shouldBe WriteVerdict.APPLY
        CandleWriteArbiter.decide(CandleSource.POLLING_FALLBACK, CandleSource.REST_CONFIRMED) shouldBe WriteVerdict.APPLY
    }

    @Test
    fun `낮은 신뢰는 높은 신뢰 확정봉을 못 덮는다 — polling이 공식마감가 덮기 REJECT`() {
        CandleWriteArbiter.decide(CandleSource.OFFICIAL_DAILY_CLOSE, CandleSource.POLLING_FALLBACK) shouldBe WriteVerdict.REJECT
        CandleWriteArbiter.decide(CandleSource.REST_CONFIRMED, CandleSource.WS_LIVE) shouldBe WriteVerdict.REJECT
        CandleWriteArbiter.decide(CandleSource.WS_LIVE, CandleSource.POLLING_FALLBACK) shouldBe WriteVerdict.REJECT
    }

    @Test
    fun `동급은 최신 write 우선 APPLY — WS live 재갱신 허용`() {
        CandleWriteArbiter.decide(CandleSource.WS_LIVE, CandleSource.WS_LIVE) shouldBe WriteVerdict.APPLY
        CandleWriteArbiter.decide(CandleSource.OFFICIAL_DAILY_CLOSE, CandleSource.OFFICIAL_DAILY_CLOSE) shouldBe WriteVerdict.APPLY
    }

    @Test
    fun `문자열 라벨 하위호환 — 기존 DB source 문자열로 판정`() {
        // 기존 12종 자유 문자열이 enum으로 매핑돼 판정된다.
        CandleWriteArbiter.decide("KIS_DAILY_CLOSE", CandleSource.POLLING_FALLBACK) shouldBe WriteVerdict.REJECT
        CandleWriteArbiter.decide("KIS-BACKFILL", CandleSource.WS_LIVE) shouldBe WriteVerdict.REJECT
        CandleWriteArbiter.decide("KIS-POLLING", CandleSource.REST_CONFIRMED) shouldBe WriteVerdict.APPLY
        CandleWriteArbiter.decide("KIS", CandleSource.WS_LIVE) shouldBe WriteVerdict.APPLY // KIS = WS_LIVE 동급
    }

    @Test
    fun `fromLabel 매핑 정합 — 12종 대표 라벨`() {
        CandleSource.fromLabel("KIS_DAILY_CLOSE") shouldBe CandleSource.OFFICIAL_DAILY_CLOSE
        CandleSource.fromLabel("KIS-MKT-DAILY-SNAP") shouldBe CandleSource.OFFICIAL_DAILY_CLOSE
        CandleSource.fromLabel("KIS-BACKFILL") shouldBe CandleSource.REST_CONFIRMED
        CandleSource.fromLabel("KIS-RANGE-GAPFILL") shouldBe CandleSource.REST_CONFIRMED
        CandleSource.fromLabel("KIS-MKT-DAILY-BF") shouldBe CandleSource.REST_CONFIRMED
        CandleSource.fromLabel("KIS-NOTICK-FALLBACK") shouldBe CandleSource.POLLING_FALLBACK
        CandleSource.fromLabel("KIS-POLLING") shouldBe CandleSource.POLLING_FALLBACK
        CandleSource.fromLabel("KIS") shouldBe CandleSource.WS_LIVE
        CandleSource.fromLabel(null) shouldBe CandleSource.WS_LIVE
        CandleSource.fromLabel("") shouldBe CandleSource.WS_LIVE
    }
}
