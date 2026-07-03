package kr.spdaewoon.magicjar.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * LEGACY 백테스트 정합 옵션 properties — 2026-07-03 감사 §6 P2-1(체결가 낙관 편향)·P2-2(정규장 필터) 대응.
 *
 * Override 패턴 (env override 1:1 · application.yml `magicjar.backtest.legacy` —
 * [IntradayRealisticBacktestProperties] 패턴 정합):
 *   ```
 *   magicjar:
 *     backtest:
 *       legacy:
 *         fill-at-next-open: false     # MAGICJAR_BACKTEST_LEGACY_FILL_AT_NEXT_OPEN
 *         regular-session-only: true   # MAGICJAR_BACKTEST_LEGACY_REGULAR_SESSION_ONLY
 *   ```
 */
@ConfigurationProperties(prefix = "magicjar.backtest.legacy")
data class LegacyBacktestProperties(
    /**
     * 다음 봉 open 체결 옵션(opt-in). default **false** = 현행(신호봉 close 체결) 유지.
     *
     * 낙관 편향 완화 옵션 — 라이브는 신호봉 close 가 아니라 다음 봉 진행 중(+2-hop 지연)에 체결되므로,
     * true 면 LEGACY 비-scale-in 경로의 BUY/DSL SELL 을 다음 봉 open 가격으로 체결한다(감사 §6 P2-1).
     * SL/TP 라인 체결·강제 청산은 대상 아님(라인/close 가격 그대로).
     */
    val fillAtNextOpen: Boolean = false,
    /**
     * 정규장(09:00~15:30 KST) 밖 봉의 신호 평가/체결 skip. default **true** — 라이브 market-hours gate
     * default(BUY·SELL 모두 정규장만 — `magicjar.signal.market-hours-gate`)와 정합(감사 §6 P2-2).
     *
     * 분·시 봉(60분 이하)에만 적용 — 일봉 이상은 ts 가 장중 시각 의미가 없어 필터 오적용을 방지한다.
     * false = 종전 동작(NXT 시간대 봉으로도 매매 시뮬).
     */
    val regularSessionOnly: Boolean = true,
)
