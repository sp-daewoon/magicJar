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
    // ADR 0132 R2a — 초단타 백테스트 분봉 backfill lookback clamp 설정.
    BacktestIntradayProperties::class,
)
class ChartCandleConfiguration
