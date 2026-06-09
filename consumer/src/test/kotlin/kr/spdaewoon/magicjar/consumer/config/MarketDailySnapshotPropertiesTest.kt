package kr.spdaewoon.magicjar.consumer.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MarketDailySnapshotProperties — ADR 0118 R2 토글 매핑 검증.
 *
 * NXT/정규장 양쪽 빌드(§2-D 결재 ③) + default = NXT_INTEGRATED(결재값) 확인.
 */
class MarketDailySnapshotPropertiesTest {
    @Test
    fun `default authority는 NXT_INTEGRATED (결재 ③ 값)`() {
        val props = MarketDailySnapshotProperties()
        assertThat(props.todayCloseAuthority).isEqualTo(TodayCloseAuthority.NXT_INTEGRATED)
        assertThat(props.enabled).isFalse() // canary 전 신중 default
        assertThat(props.cron).isEqualTo("0 0 23 * * *")
    }

    @Test
    fun `NXT_INTEGRATED는 멀티시세 시장구분 NX로 매핑`() {
        val props = MarketDailySnapshotProperties(todayCloseAuthority = TodayCloseAuthority.NXT_INTEGRATED)
        assertThat(props.marketDivCode()).isEqualTo("NX")
    }

    @Test
    fun `KRX_REGULAR 폴백은 멀티시세 시장구분 J로 매핑`() {
        val props =
            MarketDailySnapshotProperties(
                todayCloseAuthority = TodayCloseAuthority.KRX_REGULAR_WITH_FINALIZER_FALLBACK,
            )
        assertThat(props.marketDivCode()).isEqualTo("J")
    }
}
