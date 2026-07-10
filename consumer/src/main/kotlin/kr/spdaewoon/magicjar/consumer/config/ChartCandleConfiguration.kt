package kr.spdaewoon.magicjar.consumer.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 차트 캔들 properties wiring (ADR 0093 §3-1 LOCK · consumer 모듈 mirror).
 *
 * api 모듈 [kr.spdaewoon.magicjar.api.config.ChartCandleConfiguration]와 1:1.
 */
@Configuration
@EnableConfigurationProperties(
    ChartCandleProperties::class,
    // ADR 0118 R-BF — MARKET_DAILY 10년 일봉 backfill 캠페인 설정.
    MarketDailyBackfillProperties::class,
    // ADR 0118 R2 — MARKET_DAILY 야간 전종목 일봉 today snapshot 스케줄러 설정.
    MarketDailySnapshotProperties::class,
    // 2026-07-10 minute-pool — 후보 풀 과거 1분봉 백필 + 장중 전방 수집 설정 (market-data).
    MinutePoolBackfillProperties::class,
    // ADR 0132 R2a 의 BacktestIntradayProperties 는 ADR 0133 #4(api in-process 통합)로 제거 —
    // 백테스트 backfill lookback clamp 는 api IntradayRealisticBacktestProperties 가 단일 출처.
)
class ChartCandleConfiguration
