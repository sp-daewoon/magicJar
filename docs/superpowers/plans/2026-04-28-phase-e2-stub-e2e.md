# Phase E-P2 — Stub fixture 기반 1종목 1전략 e2e

> backend-core·market-data 협업. **market-data(sonnet)** 단독 위임. 1회 opus 에스컬레이션 가능.

**Goal:** KIS 모의 키 발급 전이라도 stub fixture로 e2e 파이프라인(시세 backfill → 백테스트 → 신호 → 모의 주문 → 매매일지) 끝까지 통과시킨다. 실제 KIS 키 들어오면 stub 어댑터를 KIS REST 어댑터로 교체만 하면 됨(hexagonal port-and-adapter).

**Source:** main `452c84c` 이후 + E-P1 머지된 상태. spec §2 §4 §15-2 + ADR 0019·0021.

**Branch:** `agent/market-data/e2-stub-e2e`
**Author:** `bot+market-data@magicjar.local`
**Model:** sonnet

---

## 0. Pre-flight

- [ ] main pull, `:api:test :batch:test :consumer:test` PASS 확인
- [ ] `_workspace/_legacy/market-data-resources/kis-samples/{candles-d1.json, quote.json}` 존재
- [ ] `.env` `KIS_MOCK_APP_KEY` 비어있음 확인 — stub 모드로 작동해야

---

## 1. KIS 어댑터 stub/real 분기 (api·consumer 양쪽)

### 1-A. domain port 확인
domain의 port (이미 P2에서 `domain.market.port.KisRestClient` 또는 그 비슷 정의됨 — 본 작업 시작 시 확인). 없으면 신설:

```kotlin
// domain/src/main/kotlin/com/mj/domain/market/port/KisRestPort.kt
package com.mj.domain.market.port

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.common.Quote
import java.time.LocalDate

interface KisRestPort {
    fun fetchCandles(code: String, interval: Interval, from: LocalDate, to: LocalDate): List<Candle>
    fun fetchQuote(code: String): Quote
    fun token(): String  // 캐시된 access token (또는 발급)
}
```

### 1-B. api/consumer infrastructure에 stub + real 어댑터

```
api/src/main/kotlin/com/mj/api/infrastructure/kis/
  StubKisRestAdapter.kt    @Profile("!real-kis") — kis-samples JSON 로드해서 응답
  RealKisRestAdapter.kt    @Profile("real-kis") — KisRestClient(이미 _legacy 백업분 또는 신규) 호출
  KisRestConfigBean.kt     @Bean — 둘 중 하나 빈 활성화
```

`consumer`도 동일 패턴 (또는 domain 라이브러리 안에 stub 두고 consumer가 그대로 사용).

### 1-C. application.yml 분기

`api/src/main/resources/application.yml`에:
```yaml
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:stub-kis}
---
spring:
  config:
    activate:
      on-profile: stub-kis
magicjar:
  kis:
    mode: STUB
---
spring:
  config:
    activate:
      on-profile: real-kis
magicjar:
  kis:
    mode: REAL
    base-url: https://openapivts.koreainvestment.com:29443
    app-key: ${KIS_MOCK_APP_KEY}
    app-secret: ${KIS_MOCK_APP_SECRET}
    account: ${KIS_ACCOUNT_CANO}-${KIS_ACCOUNT_PRDT}
```

→ 기본 `stub-kis` 활성. 사용자가 키 입력 + `SPRING_PROFILES_ACTIVE=real-kis`로 전환.

---

## 2. e2e 시나리오 1건 (stub 기반)

**대상**: 005930 삼성전자, 일봉 1년치, SMA(5,20) cross 전략.

### 2-A. 캔들 backfill (batch 잡)

`batch/src/main/kotlin/com/mj/batch/job/backfill/DailyCandleBackfillJob.kt`:
- `KisRestPort.fetchCandles("005930", D1, today-365, today)` 호출
- StubKisRestAdapter가 kis-samples/candles-d1.json 반환
- ItemWriter로 PostgreSQL `candle` 테이블에 INSERT

### 2-B. 백테스트 (batch 잡)

`batch/src/main/kotlin/com/mj/batch/job/backtest/BacktestRunJob.kt` (이미 P2에서 일부 구현):
- `BacktestRunRequest{strategyId="sma-cross-5-20", symbol="005930", from, to}`
- BacktestExecutionContext + MetricsCalculator (domain) 호출
- 결과: BacktestRunJpa(runId, status=COMPLETED, summary{wins, losses, totalReturn}) DB INSERT
- 매 진입/청산을 journal entry로 INSERT
- Kafka `journal.entry.v1` publish

### 2-C. e2e 호출 (api → batch)

E-P1에서 만든 `BacktestController.POST /api/backtest`가 batch-app HTTP 호출(또는 Kafka publish)로 잡 트리거. runId 응답.

### 2-D. 검증

`e2e/SmokeTest.kt` (api 또는 통합 모듈):
- `POST /api/backtest` body 1건 → runId 받음
- batch 잡이 완료될 때까지 polling (최대 30초)
- `GET /api/backtest/{runId}` → status COMPLETED + summary
- `GET /api/journal?runId={runId}` → 매매일지 N건 (>= 1) 응답

### 2-E. WebSocket(KIS) 실시간 stream — Phase E-P3로 위임

stub로는 실시간 stream 시뮬레이션 어렵고, 실제 KIS 모의 WebSocket은 사용자 키 필요 + 장중에만. 본 phase 범위 외.

---

## 3. 종료 게이트

- [ ] `./gradlew :batch:test --tests "*DailyCandleBackfillJobTest"` PASS (stub 캔들 1년치 적재 검증)
- [ ] `./gradlew :batch:test --tests "*BacktestRunJobTest"` PASS (백테스트 결과 + journal entry 생성)
- [ ] e2e SmokeTest: `POST /api/backtest` → runId → polling → COMPLETED → journal 조회 → N건 확인 (`@SpringBootTest` + Testcontainers PG + Kafka)
- [ ] `docker compose up -d && scripts/smoke.sh` S1 PASS (E-P1에서 SKIP였던 항목 해소)
- [ ] PR `agent/market-data/e2-stub-e2e` → main 발행

---

## 4. 통보 경계

다음만 사용자 통보:
1. `kis-samples/candles-d1.json` 형식이 도메인 Candle과 매칭 불가 (예: 컬럼명·시간 형식 차이)
2. KIS REST 어댑터 위치 결정 (api vs consumer vs 둘 다)
3. batch 잡 트리거 방식 결정 (HTTP vs Kafka)

---

## 5. 후속 (사용자 환경)

E-P2 머지 후:
- 사용자가 KIS 모의 계좌 개설 → 앱키·앱시크릿 발급 → `.env` 입력
- `SPRING_PROFILES_ACTIVE=real-kis docker compose up`
- 같은 e2e 시나리오를 RealKisRestAdapter로 재검증
- 통과 시 ADR 0028(가능): "stub 어댑터 시연 완료 + real KIS 연동 통과 기록"

→ 이건 E-P2-bis 또는 사용자 직접.
