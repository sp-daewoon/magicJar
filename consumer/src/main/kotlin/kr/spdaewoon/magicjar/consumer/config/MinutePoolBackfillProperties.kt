package kr.spdaewoon.magicjar.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 분봉 수집 풀(minute-pool) — 과거 1분봉 백필 + 전방 수집 설정 (2026-07-10 · market-data).
 *
 * ## 배경 (최고 관리자 2026-07-10 지시)
 *   인트라데이 백테스트 가능 종목이 소수(≈15)뿐이라 전략 설계·검증·동적 유니버스가 막혔다.
 *   KIS 분봉 API(`FHKST03010230`)는 과거 소급 깊이가 최대 1년(호출당 최대 120봉)이므로, 후보 풀을
 *   정의해 (1) 최근 N영업일 1분봉을 **과거 백필**하고 (2) 장중 **전방 수집**으로 최신분을 이어 붙인다.
 *
 * ## 후보 풀 구성 (union — [kr.spdaewoon.magicjar.consumer.ingestion.MinutePoolProvider])
 *   1. **거래대금 상위 N** — 최신 1d 봉의 `close_raw × volume` 상위 (existing candle DB · KIS 콜 0).
 *   2. **발굴 스코어카드 후보** — 최신 snapshot TOP N (existing `discovery_scorecard` · KIS 콜 0).
 *   3. **ACTIVE/autopilot 전략 명시 종목** — [kr.spdaewoon.magicjar.consumer.ingestion.ActiveStrategyCodeProvider].
 *   4. **yml seed 종목** — ops 지정 유동성 baseline([seedCodes]).
 *   → dedupe 후 [poolSize] 상한 clamp.
 *
 * ## rate-limit (기존 BACKFILL(BG) 버킷 재사용 — ADR 0116 §2-B · ADR 0132 §D2-3 LOCK)
 *   모든 KIS 콜은 [kr.spdaewoon.magicjar.domain.market.port.KisMinuteBackfillRangePort]
 *   ([kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority.BACKFILL] 고정)를 재사용한다.
 *   BG 버킷(capacity 3/초)은 USER_PATH(주문·현재가·차트) reserve·INGESTION(WS approval·watchlist)를
 *   절대 굶기지 않는다. 백필은 장 마감 후 실행이 목표이므로 자본 path 침범 risk 0.
 *
 * ## 멱등성 (재실행 시 중복 0)
 *   과거 1분봉은 immutable — [kr.spdaewoon.magicjar.domain.market.port.CandleRepository.saveAll]
 *   (ON CONFLICT DO NOTHING)로만 적재. 재실행해도 중복 INSERT 0. 5m/15m 등 합산봉은 기존 롤업 경로
 *   ([kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner.backfill], upsert DO UPDATE)로 재생성.
 *
 * 자본 영향 0 — 데이터 적재만(주문 0). LLM import 0 (룰 5). 외부 fallback 0 (KIS-only).
 */
@ConfigurationProperties(prefix = "magicjar.minute-pool-backfill")
data class MinutePoolBackfillProperties(
    /** 마스터 kill-switch (default true). false 시 백필·전방 수집 모두 가동 0(적재분 보존). */
    val enabled: Boolean = true,
    /** 후보 풀 상한 (dedupe union 후 clamp). 대략 30~50 권장. */
    val poolSize: Int = 30,
    /** 과거 백필 소급 영업일 수 (KIS 1년 보관 한도 내 — default 30 거래일). */
    val lookbackTradingDays: Int = 30,
    /** 거래대금 상위 후보 개수 (최신 1d 봉 close×volume 기준). */
    val turnoverTopN: Int = 30,
    /** 발굴 스코어카드 후보 개수 (최신 snapshot). */
    val scorecardTopN: Int = 20,
    /** ACTIVE/autopilot 전략 명시 종목 풀 포함 여부. */
    val includeActiveStrategyCodes: Boolean = true,
    /**
     * yml seed 종목 (ops 지정 유동성 baseline). 동적 소스(거래대금·스코어카드)가 비어도 최소 풀 확보용.
     * default = 대형 유동주 15종 (삼성전자·SK하이닉스 등 — env override 가능).
     */
    val seedCodes: List<String> =
        listOf(
            "005930", // 삼성전자
            "000660", // SK하이닉스
            "373220", // LG에너지솔루션
            "207940", // 삼성바이오로직스
            "005380", // 현대차
            "000270", // 기아
            "051910", // LG화학
            "006400", // 삼성SDI
            "035420", // NAVER
            "035720", // 카카오
            "105560", // KB금융
            "055550", // 신한지주
            "005490", // POSCO홀딩스
            "012330", // 현대모비스
            "068270", // 셀트리온
        ),
    /**
     * 과거 백필 skip 판정 커버리지 비율 — 기대 1분봉 대비 이미 보유 비율이 이 값 이상이면 KIS 콜 skip(멱등 재개).
     * 기대봉 = lookbackTradingDays × 390(정규장 분). 0.70 = 70% 이상 보유 시 완료로 간주(공휴일·거래정지 보수).
     */
    val coverageRatio: Double = 0.70,
    /** 1회 run 당 처리 종목 상한 (안전 cap — 풀보다 크게). 초과분은 다음 run 이연(멱등). */
    val maxCodesPerRun: Int = 60,
    /** 종목 간 launch 지연(ms) — BG 버킷 burst 분산(rate-limit 완충). */
    val perCodeDelayMs: Long = 200L,
    /** 장중 전방 수집 poller 설정 (2순위 — 백필로 채운 풀에 최신분 이어 붙이기). */
    val forwardPoller: ForwardPollerProperties = ForwardPollerProperties(),
) {
    fun normalizedLookbackTradingDays(): Int = lookbackTradingDays.coerceIn(1, 252)

    fun normalizedPoolSize(): Int = poolSize.coerceIn(1, 200)

    fun normalizedCoverageRatio(): Double = coverageRatio.coerceIn(0.0, 1.0)

    /** 기대 1분봉 수 (정규장 09:00~15:30 = 390분/거래일). skip 판정 분모. */
    fun expectedOneMinBars(): Int = normalizedLookbackTradingDays() * REGULAR_SESSION_MINUTES

    companion object {
        const val REGULAR_SESSION_MINUTES = 390
    }
}

/**
 * 장중 전방 수집 poller 설정 (원래 스코프 — 2순위).
 *
 * 백필이 채운 후보 풀에 대해 장중 주기적으로 "마지막 닫힌 분봉 → 최신분" gap 을 KIS REST 로 이어 붙인다.
 * WS 슬롯은 절대 사용 0(매매 전용 — ADR 0124). 동일 BG 버킷 재사용.
 */
data class ForwardPollerProperties(
    /** 전방 수집 poller 가동 여부 (default true). */
    val enabled: Boolean = true,
    /** poller 주기(ms) — default 5분(종목당 5~10분 주기 최신분 갱신). BG 버킷 예산 내 보수적. */
    val fixedDelayMs: Long = 300_000L,
    /** 부팅 후 첫 발사 지연(ms) — 부팅 warmup 경로와 겹치지 않게 지연. */
    val initialDelayMs: Long = 90_000L,
)
