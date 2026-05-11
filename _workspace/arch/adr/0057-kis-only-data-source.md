# ADR 0057 — KIS OpenAPI 단일 데이터 출처 (Yahoo Finance · 네이버 금융 등 보조 출처 폐기)

- 상태: Accepted
- 작성일: 2026-05-11 (amend 2026-05-11 — 사용자 정책 정정 2건 반영: (1) ADR 0019 supersede revoke → "5m~1h 보류 + 1m 합산 가공 결정" 표현으로 정정 (2) KIS 키 분기 제거 — 정보 수집은 실전 키 단일 채널)
- 결정자: 사용자(최고 관리자) + architect
- 관계: **ADR 0019 (캔들 저장 정책 — Watchlist 기반)** 본 ADR과 정합 (5m~1h 처리만 보류 — 폐기 아님) / ADR 0028(Watchlist Ingestion) / ADR 0029(당일 진행 중 캔들 Redis 캐시) / ADR 0037(KIS Rate Limit) / ADR 0048(KIS WS 41 slot)
- 사용자 메모리 `feedback_kis_first_policy.md`(KIS-only 데이터 정책) · `feedback_kis_key_realonly_for_data.md`(정보 수집 API = 실전 키 단일 채널) 정합

> **KIS 키 정책 (사용자 명시 2026-05-11)**: 정보 수집 API(시세·캔들·호가·종목 마스터 등)는 **prod 키(실전 키) 단일 채널** 사용. `AccountType.REAL` credential로 KIS REST/WS 호출. **모의/실전 분기는 주문 라우터(`OrderRouter` 등) 거래 액션에만** 적용 — 본 ADR 본문 어디에도 "모의 키 사용자 vs 실전 키 사용자" 분기는 적용하지 않음. 정보 수집은 단일 채널이므로 분기 자체가 존재하지 않음.

---

## 1. 컨텍스트

### 1-1. 사용자 결정 배경 (2026-05-11)

사용자 dogfooding 중 다음 결함 발견:
- 호출: `GET /api/symbols/000660/candles?interval=1d&limit=200`
- 기대: SK하이닉스 일봉 200건 (2009-11 ~ 2026-05 KST 일자별)
- 실제: 매월 1일 KST 00:00 정확히 떨어지는 ts·`source=YAHOO`·316건 = **월봉이 일봉으로 라벨링되어 응답**

### 1-2. root cause

`consumer/.../infrastructure/yahoo/YahooCandleFetcherAdapter.kt` 의 매핑:

```kotlin
CandleInterval.ONE_DAY to "max"
```

Yahoo Finance v8 chart endpoint(`https://query1.finance.yahoo.com/v8/finance/chart/{ticker}`)가 한국 오래된 상장종목(SK하이닉스 등) `interval=1d&range=max` 조합에서 **monthly downsample** 응답 반환 → 우리 어댑터는 응답 interval 재검증 없이 그대로 `interval='1d'`로 DB INSERT → 일봉 차트에 월봉이 노출.

### 1-3. 사용자 명시 결정 (2026-05-11 채팅 2회)

1. "야후꺼 쓰지 말라니깐"
2. "kis에 있는 데이터만 사용하라고"

→ **Yahoo Finance 전면 폐기 + KIS OpenAPI 단일 출처 정책 확립**

### 1-4. fitness gap 분석 (사용자 메모리 `feedback_v3_skepticism_no_regression.md` lens — "자동매매에 필요한가? v1/v2 잔재 아닌가?")

| 측면 | Yahoo 사용 시 | KIS-only 시 | 자동매매 본체 정합 |
|---|---|---|---|
| **데이터 출처 일관성** | 일·주·월·년봉 = KIS / 분봉 = Yahoo (혼합) | 모두 KIS | KIS-only가 정합 (룰 엔진·DSL·신호 동일 source) |
| **수정주가 정합** | KIS 수정주가 vs Yahoo unadjusted/adjusted 혼동 risk | KIS 단일 모델(ADR 0018 정합) | KIS-only가 정합 |
| **신뢰성** | Yahoo 비공식 endpoint·downsample 결함·KS·KQ 표기 불일치 | KIS 공식 한도 안에서 강제 통과 | KIS-only가 정합 |
| **사용자 자동매매 필요성** | 분봉 backfill만 보조 | 5m~1h 분봉 cold-start이 사라짐(본 ADR §4) | **fitness gap 발생 — backlog § 등록 의무** |
| **v1/v2 잔재 의심** | Yahoo adapter는 v2 시점부터 누적된 보조 출처(룰 본체와 무관) | 제거 → KIS-only 정합 | Yahoo = v2 잔재 lens 정합 — 폐기 정당 |

**결론**: KIS-only는 자동매매 본체(룰 기반 quant trading + KIS-first 정책 — `master-architecture.md` §1) 정합. Yahoo는 v2 잔재. 5m~1h interval cold-start gap만 별도 의제로 흡수.

---

## 2. 결정

### 2-1. 데이터 출처 매트릭스 (이번 사이클 운용 — ADR 0019 §2 매트릭스의 KIS 직접 제공 부분만 발효 / Yahoo 의존 부분은 보류)

> **운용 범위 (사용자 명시 2026-05-11)**: 이번 사이클은 **`1m·1d·1wk·1mo·1y` 5개 interval만 KIS 직접 제공으로 운용**. 5m·15m·30m·90m·1h은 **1m 합산 가공으로 처리 결정** (백엔드 합산 vs 프론트 합산은 별도 ADR로 후속 결정 — `_workspace/backlog.md` §31 트랙). ADR 0019 §2-5 "합성 폐기" 결정은 본 1m 합산 트랙으로 부분 revoke 예정 (트랙 완료 시 ADR 0019 §2 매트릭스에 재통합).

| Interval | Source | KIS endpoint / tr_id | Cache | DB 영속 |
|---|---|---|---|---|
| **틱** | KIS WS `H0STCNT0` | WebSocket (prod credential) | Redis (휘발, retention 1h) | X |
| **1m (1분봉)** | **KIS REST** | `inquire-time-itemchartprice` / `FHKST03010200` (당일 한정, prod credential) | Redis only (당일) | DB INSERT (1분 closing 시) |
| **5m / 15m / 30m / 90m / 1h** | **1m 합산 가공 (방향 확정 — backlog §31 구현 사이클)** | — (별도 ADR로 백엔드/프론트 합산 결정) | — | — (구현 전까지 빈 slot — "1m 합산 가공 예정" 표시) |
| **1d (일봉)** | **KIS REST** | `inquire-daily-itemchartprice` / `FHKST03010100` (prod credential) | Redis cache (옵션) | DB 영속 |
| **당일 진행 중 일봉** | KIS REST + KIS WS tick 누적 (ADR 0029) | `FHKST01010100` + `H0STCNT0` (prod credential) | Redis only (휘발, 장 마감까지) | 장 마감 후 DB INSERT |
| **1wk / 1mo / 1y (주·월·년봉)** | **KIS REST** | `inquire-daily-itemchartprice` / `FHKST03010100` (period_div_code='W'·'M'·'Y', prod credential) | Redis cache (옵션) | DB 영속 |

**credential 정책 (사용자 명시 정책 정정 2)**: 본 매트릭스의 모든 호출은 **prod(실전) credential 단일 채널**. `AccountType.REAL` 자격으로 KIS REST/WS 호출. **모의 credential은 정보 수집에서 사용 0건** — 모의/실전 분기는 주문 라우터(거래 액션)에만 적용.

**raw 출처 (헌법 §3 KIS-first raw fetch 의무 정합)**:
- `FHKST03010100` (일·주·월·년봉) — https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
- `FHKST03010200` (당일 분봉) — https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/inquire_time_itemchartprice/inquire_time_itemchartprice.py
- `FHKST03010230` (일별 분봉 — 모의 미지원·실전 한정) — `docs/operations/kis-api-reference.md` §5-A 후속 검토 대상으로 보존

### 2-2. UI 노출 interval

본 ADR 발효 즉시:

```
분봉: [1m]                              ← KIS REST 당일 한정 지원 (prod credential)
중간봉: [5m] [15m] [30m] [90m] [1h]    ← "1m 합산 가공 예정" 표시 (backlog §31 구현 사이클)
일봉: [1d] [1wk] [1mo] [1y]            ← KIS REST 전체 기간 (prod credential)
```

**UI 처리 (이번 사이클)**: 5m·15m·30m·90m·1h 버튼 클릭 시 backend `/api/symbols/{code}/candles?interval=5m` 는 `200 OK + empty array + meta.aggregationPending=true` 반환. frontend는 "1m 합산 가공 예정 (backlog §31 구현 사이클)" 메시지 표시. **mock 합성 금지** (사용자 메모리 `feedback_no_mock_data.md` 정합) — 빈 slot이 아니라 합산 가공 완료 시 채워질 예정 정보임을 명시.

**향후 (backlog §31 트랙 완료 후)**: 1m 합산 가공이 백엔드 또는 프론트에서 활성화되면 본 매트릭스에 5m·15m·30m·90m·1h 행 추가 (`source = 1m aggregation`, KIS REST `FHKST03010200`(당일) + 향후 `FHKST03010230`(과거) 검토).

### 2-3. cold-start 흐름 (ADR 0028 §2-3 정합 갱신)

```
사용자 [관심등록 클릭] (frontend /watchlist)
    ↓
api POST /api/watchlists  (body: {code})
    ↓
DB watchlist INSERT + Kafka watchlist.changed.v1
    ↓
consumer WatchlistIngestionWorker (5초 polling LOCK, ADR 0028+0048)
    ├─ SubscriptionRouter.add(code, priority) — slot 1+30+10 분배 (ADR 0048)
    │    ├─ slot 여유 → KIS WS subscribe(code) — H0STCNT0 체결가
    │    └─ slot 가득 + priority 낮음 → WatchlistPollingWorker 진입 (5초 주기 KIS REST inquire-price)
    ├─ KIS REST 1d  (FHKST03010100, 전체 기간) → DB INSERT
    ├─ KIS REST 1wk·1mo·1y (FHKST03010100 period_div_code) → DB INSERT
    └─ 1m 분봉 — 장중일 때만 KIS REST `FHKST03010200` 당일분 fetch + Redis only
        (5m~1h backfill 0 — 1m 합산 가공 트랙 backlog §31 완료 시 합산 source로 활성화)
    ↓
사용자 /symbol/{code} 진입 시:
  - 1d·1wk·1mo·1y → 즉시 응답 (DB)
  - 1m → 장중일 때 Redis only / 장외일 때 "데이터 없음"
  - 5m·15m·30m·90m·1h → "1m 합산 가공 예정 (backlog §31 구현 사이클)"
```

### 2-4. Yahoo 어댑터 폐기 절차

본 PR(architect ADR/문서만) 이후 별도 코드 PR(market-data sub-agent 디스패치 예정)에서 다음 일괄 처리:

1. **`consumer/.../infrastructure/yahoo/YahooCandleFetcherAdapter.kt`** — `@Component` 제거 + `@Deprecated` 마킹 + 미사용 처리. 실제 삭제는 v3.3.0 minor에서 일괄.
2. **`api/.../infrastructure/yahoo/YahooFinanceClient.kt`** (있다면) — 동일 처리.
3. **`WatchlistIngestionWorker`** — Yahoo cold-start 호출부 제거 + KIS REST cold-start으로 전환.
4. **`application.yml`** — `yahoo.base-url`·`yahoo.timeout` 등 환경 제거.
5. **테스트** — Yahoo mock 테스트 케이스 제거 또는 `@Disabled` + backlog § 이관.
6. **DB 정리** — `_workspace/market-data/hotfix-purge-yahoo-source.sql` 일회성 — `DELETE FROM candle WHERE source='YAHOO' AND interval IN ('5m','15m','30m','90m','1h')` (1m은 v2 잔재 1m 분봉 보존 여부는 market-data 검토 후 결정). 사용자 dogfooding 결함의 일봉 월봉 오염도 `DELETE WHERE source='YAHOO' AND interval='1d'` 후 KIS REST 재backfill.

### 2-5. 5m·15m·30m·90m·1h = 1m 합산 가공 (방향 확정, 구현은 backlog §31)

ADR 0019 §2-5 "합성 폐기" 결정은 본 KIS-only 전환 + 사용자 명시 정책 정정으로 **부분 revoke**.

**사용자 명시 결정 (2026-05-11)**:

> "5m·15m·30m·90m·1h 백엔드 또는 프론트 합산으로 할거고 ADR 폐기해버리면 안될듯 1분봉만 kis에서 제공하니깐 임시로 쓰는거고 나중에 5m·15m·30m·90m·1h 봉에 대한 처리를 보류할뿐임"

→ **5m·15m·30m·90m·1h은 1m 합산 가공으로 처리 방향 확정**. 합산 위치(백엔드 vs 프론트)는 별도 ADR로 후속 결정.

**잔여 의제 (backlog §31 — 합산 위치 결정 트랙)**:

- **위치 옵션 A: 백엔드 합산** (`CandleAggregator` 컴포넌트 — api 또는 consumer 모듈). Pro: 모든 클라이언트(SPA·MCP·후속 모바일)가 동일 합산 결과 공유. Con: 백엔드 부하 + DB 또는 cache 추가 영속 검토 필요.
- **위치 옵션 B: 프론트 합산** (frontend `candleAggregator.ts` 부활). Pro: 백엔드 변경 0 · 사용자 클라이언트에서 자유롭게 interval 변경 가능. Con: 클라이언트마다 합산 중복 + MCP·후속 모바일에서도 합산 재구현 의무.

합산 source 1m 데이터의 시간 범위 (당일 한정 vs 과거 포함) 도 함께 결정 — KIS `FHKST03010230` (1분봉 과거 데이터, 실전 credential 단일 채널) 도입 여부는 합산 트랙 결정과 함께 검토. **본 ADR에서는 합산 방향만 확정** — 위치·source 범위는 backlog §31 트랙 사이클에서 사용자 결정 + ADR 0062로 발행.

ADR 0019 §2 매트릭스 재통합은 backlog §31 트랙 완료 시 본 ADR §2-1 + ADR 0019 §2를 함께 정정.

### 2-6. CHECK constraint 갱신 (DB migration 예정 — backlog §31 처리 후)

본 PR에서는 **DB constraint 변경 0건**. 5m·15m·30m·90m·1h interval 자체는 enum 유지 (UI에서 "데이터 없음" 표시 정합 + backlog §31 결정 후 합산 부활 가능성 보존). DDL 변경은 옵션 B·C 결정 후 별도 sub-agent PR.

---

## 3. 결과

- **데이터 출처 단일** — KIS OpenAPI 100% (틱·1m·1d·1wk·1mo·1y). Yahoo·네이버·DART 캔들 0건.
- **사용자 dogfooding 결함 root cause 해소** — `YahooCandleFetcherAdapter`가 일봉 위치에 월봉을 INSERT하는 결함 자체가 더 이상 발생 불가.
- **KIS 단일 source 정합** — 룰 엔진·DSL·신호·백테스트 모두 동일 출처(KIS 수정주가) — fitness gap 0.
- **cold-start 단순화** — Yahoo backfill 호출 7건 제거(1m·5m·15m·30m·90m·1h·1d) → KIS REST 4건만(1d·1wk·1mo·1y) + 당일 1m Redis only.
- **5m~1h interval = 1m 합산 가공 (방향 확정)** — backlog §31 구현 사이클 완료 전까지 "1m 합산 가공 예정" 표시. 합산 위치(백엔드/프론트)는 backlog §31 트랙에서 결정 + ADR 0062 발행.
- **자동매매 본체 정합** — 룰 시그널·DSL·백테스트는 1d·1wk·1mo·1y 기반이 1차 (DSL 카탈로그 `_workspace/strategy/` 정합). 5m~1h 구간은 1m 합산 가공 활성화 후 룰 엔진·백테스트가 동일하게 정합. backlog §31 결정 사이클에서 합산 source 시간 범위(당일 한정 vs `FHKST03010230` 과거 포함) 측정.
- **credential 단일 채널** — 모든 정보 수집 호출은 prod 자격으로 단일 채널. 모의/실전 분기는 주문 라우터에만 적용 (사용자 명시 정책).

---

## 4. 절충 + Rollback 시나리오

### 4-1. 절충

- **5m·15m·30m·90m·1h 일시 부재 (합산 가공 활성화 전)** — 사용자 차트 UX 축소. backlog §31 트랙 완료 시 1m 합산 가공으로 복구.
- **장기 1m 분봉 부재** — 본 ADR 발효 시점 1m은 KIS REST 당일 한정. 장기 1m이 필요한 quant 전략은 `FHKST03010230` (1분봉 과거 데이터 — prod credential 단일 채널) 도입 검토 (backlog §31 트랙). 사용자 메모리 `feedback_kis_first_policy.md` lens.

### 4-2. Rollback 시나리오 (Yahoo 부활이 필요할 시)

본 ADR을 supersede 또는 revoke 결정이 나는 시나리오:

1. **KIS rate limit 영향이 과도 (사용자 dogfooding 측정)** — 30종목 universe에서 일봉 backfill 누적 호출이 KIS 초당 10건 한도(ADR 0037) 또는 EGW00201 위반 발생 → Yahoo 보조 출처 부활 검토.
2. **`FHKST03010230` 도입 후에도 5m~1h 합산 성능 부족** — 5m·15m·30m·90m·1h 백테스트가 합산 비용으로 25초 한도(ADR 0056) 초과 → Yahoo 보조 출처 검토.

### 4-3. Rollback 절차

1. 본 ADR 상태를 `Superseded by ADR XXXX, YYYY-MM-DD` 헤더 갱신
2. ADR 0019 의 보류 노트 갱신 (Yahoo 매트릭스 부활 부분 명시)
3. `consumer/.../infrastructure/yahoo/YahooCandleFetcherAdapter.kt` `@Deprecated` 해제 + ts 응답 interval 재검증 가드 추가 (사용자 dogfooding 결함 root cause 재발 방지)
4. `WatchlistIngestionWorker` cold-start에 Yahoo 호출부 복원
5. `external-api-policy.md` §3 Yahoo Finance 복원
6. `candle-data-policy-v2.md` 매트릭스에 Yahoo 행 복원
7. CHANGELOG row 추가 + master-architecture §10 ADR 인덱스 갱신

**Rollback risk**: Yahoo v8 chart endpoint는 비공식이라 언제든 차단 가능. 부활 시에도 응답 interval 재검증 가드 의무 (본 결함 재발 방지). 사용자 메모리 `feedback_kis_first_policy.md` 정책 강하게 위반 — Rollback 시 사용자 직접 결정 의무.

---

## 5. 후속 작업 (별도 PR — 본 ADR PR 머지 후)

본 ADR PR은 **architect 단독 — ADR/문서만**. 코드 변경은 별도 sub-agent PR.

### 5-1. market-data sub-agent (별도 PR — `agent/market-data/kis-only-cold-start-migration`)

- `YahooCandleFetcherAdapter.kt` `@Deprecated` 마킹 (실제 삭제는 v3.3.0)
- `WatchlistIngestionWorker` cold-start KIS-only로 전환 (Yahoo 호출부 제거)
- `application.yml` Yahoo 환경 제거
- 일회성 SQL — Yahoo source row 정리 (사용자 dogfooding 결함의 오염 데이터 정리)
- KIS REST 1d backfill 검증 — 사용자 dogfooding 종목 000660 SK하이닉스 일봉 200건 재backfill 후 evidence 보고

### 5-2. frontend sub-agent (별도 PR — `agent/frontend/aggregation-pending-display-5m-1h`)

- `IntervalSelector.tsx` 5m·15m·30m·90m·1h 버튼은 유지하되 클릭 시 "1m 합산 가공 예정 (backlog §31 구현 사이클)" 표시
- 1m 버튼 — 장중 데이터 있을 때만 차트 노출, 장외 시 "당일 분봉 데이터 없음" 표시

### 5-3. backlog §31 트랙 처리 (별도 사이클 — 합산 위치·source 시간 범위 결정)

- 합산 위치(백엔드 vs 프론트) 결정 + 합산 source 시간 범위(당일 한정 vs `FHKST03010230` 과거 포함) 결정 → ADR 0062 발행
- 채택안 따라 구현 — 백엔드 합산 시 `CandleAggregator` 컴포넌트 신설 / 프론트 합산 시 `candleAggregator.ts` 신설 / `FHKST03010230` 도입 시 prod credential 단일 채널 호출 + KIS rate limit 모니터링 (ADR 0037)
- ADR 0019 §2 매트릭스 + 본 ADR §2-1 매트릭스에 5m·15m·30m·90m·1h 행 동시 추가 (통합)

---

## 6. 검증 의무 (헌법 §3 정합)

- [x] **KIS-first raw fetch 의무 충족** — §2-1 raw 출처 URL 인용 (FHKST03010100·FHKST03010200·FHKST03010230)
- [x] **fitness gap 분석 섹션** (§1-4) — 사용자 메모리 `feedback_v3_skepticism_no_regression.md` lens 정합
- [x] **Rollback 시나리오** (§4-2·4-3) — Yahoo 부활 시 절차 명시
- [x] **v1/v2 잔재 검증** — Yahoo adapter = v2 잔재 lens 정합 (§1-4) — 폐기 정당
- [x] **사용자 메모리 정합** — `feedback_kis_first_policy.md` (KIS-only) · `feedback_no_mock_data.md` (mock 합성 금지) · `feedback_v3_skepticism_no_regression.md` (v1/v2 lens) 모두 정합

---

## 7. 참고

- **ADR 0019** — 캔들 저장 정책 (Accepted, Yahoo 의존 부분만 보류 — 사용자 명시 "ADR 폐기는 하지말어 백로그로 남겨")
- **ADR 0028** — Watchlist 기반 Ingestion Worker (cold-start 흐름 본 ADR §2-3 정합 갱신 의무)
- **ADR 0029** — 당일 진행 중 캔들 Redis 캐시 (1m·1d 정합 — 본 ADR과 변화 없음)
- **ADR 0037** — KIS API Rate Limit 강제 (cold-start KIS REST 호출량 증가 → 모니터링 의무)
- **ADR 0048** — KIS WS 41건 구독 한도 + polling fallback (변화 없음)
- **`docs/architecture/external-api-policy.md`** — Yahoo Finance §3 제거 의무 (본 PR 동반)
- **`docs/architecture/candle-data-policy-v2.md`** — 매트릭스 §3·§4·§5·§6 정합 갱신 의무 (본 PR 동반)
- **`docs/operations/kis-api-reference.md`** — `FHKST03010230` 후속 검토 §5-A를 본격 의제로 격상 (backlog §31)
- **사용자 메모리 `feedback_kis_first_policy.md`** — KIS-only 데이터 정책 (사용자 갱신)
- **사용자 dogfooding 채팅 2026-05-11** — "야후꺼 쓰지 말라니깐" / "kis에 있는 데이터만 사용하라고"
- **CHANGELOG row** — 2026-05-11 본 PR 한 줄
