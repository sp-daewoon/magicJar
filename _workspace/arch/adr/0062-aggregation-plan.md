# ADR 0062 — 5m·15m·30m·90m·1h 합산 가공 plan (1m → N분봉)

- 상태: **Accepted (2026-05-11 사용자 결정)** — 채택안 = 위치 A-1(백엔드 precomputed) + source 옵션 2(과거 30 거래일) + UI 5종 활성화(5m·15m·30m·90m·1h)
- 작성일: 2026-05-11
- 채택일: 2026-05-11 (사용자 채팅 명시)
- 작성자: architect
- 결정자: 사용자(최고 관리자) — 본 ADR Accepted 갱신, sub-agent 디스패치 진행
- 관계:
  - **ADR 0057 (KIS OpenAPI 단일 데이터 출처)** — §2-5에서 본 ADR 발행을 명시 위임
  - **ADR 0019 (캔들 저장 정책 — Watchlist 기반)** — §2-5 "합성 폐기" 결정 부분 revoke 후속 (본 ADR 채택 시 §2 매트릭스 재통합)
  - **ADR 0029 (당일 진행 중 캔들 Redis 캐시)** — Redis 키 매트릭스 갱신 동반
  - **ADR 0028 (Watchlist Ingestion Worker)** — cold-start 흐름 갱신 동반
  - **ADR 0037 (KIS Rate Limit)** — source 옵션 2 채택 시 호출 영향 측정
  - **ADR 0056 (백테스트 엔진 아키텍처)** — 5m·15m·30m·90m·1h 백테스트 정합 lens
  - **ADR 0030 (master-architecture SoT)** — §10 ADR 인덱스 동시 갱신

> **사용자 명시 결정 (2026-05-11 채팅, backlog §31 인용 baseline)**:
> "5m·15m·30m·90m·1h 백엔드 또는 프론트 합산으로 할거고" / "3번 해야지"
> → 합산 방향은 확정. 본 ADR은 **합산 위치 + source 시간 범위** 결정.
>
> **사용자 채택 결정 (2026-05-11 채팅 명시)**:
> - 의제 A 합산 위치 = **A-1 백엔드 precomputed** (cold-start 시 1m 적재 직후 5m·15m·30m·90m·1h 일괄 합산 + DB INSERT)
> - 의제 B source 시간 범위 = **옵션 2 과거 30 거래일**
> - 의제 C UI 매트릭스 = **5종 활성화** (5m·15m·30m·90m·1h IntervalToggle 노출)
> → 본 ADR Status `Accepted` 갱신, sub-agent 디스패치 매트릭스(§7-1) 진행.

> **KIS 키 정책 (ADR 0057 §정합)**: 본 ADR 모든 호출은 **prod credential 단일 채널** (`AccountType.REAL`). 모의/실전 분기는 주문 라우터(거래 액션)에만. 사용자 메모리 `feedback_kis_first_policy.md`·`feedback_kis_key_realonly_for_data.md` 정합.

---

## 1. 컨텍스트

### 1-1. 사용자 결정 배경 (2026-05-11)

ADR 0057 발효 후 5m·15m·30m·90m·1h은 "1m 합산 가공 예정 (backlog §31 트랙)" 표시 상태. 사용자 명시 결정으로 합산 방향은 확정되었으나 **위치(백엔드 vs 프론트) + source 시간 범위(당일 vs 과거 포함)**는 본 ADR로 결정.

### 1-2. 현재 main 상태 (사전 조건)

- 1m 데이터: 9~10종목 watchlist에 적재 완료. 종목당 평균 ~10,000건 (직전 30 거래일 × ~380건/일 — KIS REST `FHKST03010230` prod credential)
- 5m·15m·30m·90m·1h: 미적재 (현재 응답 빈 array + `meta.aggregationPending=true`, ADR 0057 §2-2 정합)
- `CandleInterval` enum 5종 보존 (ADR 0019 §2-6 CHECK constraint — `'1m','2m','5m','15m','30m','90m','1h','1d','1wk','1mo','1y'`)
- ADR 0019 §2-5 "합성 폐기" 결정은 **부분 revoke** 상태 (ADR 0019 헤더 노트)
- frontend `IntervalToggle`은 현재 8종(1m·5m·30m·1h·1d·1wk·1mo·1y) — 15m·90m 빠진 상태. **본 ADR 채택 시 5m·15m·30m·90m·1h 5종 모두 활성화 의제 포함**

### 1-3. 사용자 비전 정합 (`project_system_vision.md` lens)

magicJar는 **룰 기반 quant trading 본체 + MCP 서버 노출**. 본 ADR의 합산 위치 결정은 자동매매 본체 정합에 직접 영향:

- **MCP 외부 client** (Claude·Cursor·Cline 등) 5m·15m·30m·90m·1h interval 요청 가능해야 → **프론트 합산으로는 cover 불가**
- **룰 엔진·DSL** (T-DSL·T-SB) 5m·30m 인터벌 조건식 사용 → **백엔드 합산 정합**
- **백테스트 엔진** (T-BT, ADR 0056) 5m·15m 데이터 호출 시 in-memory 매번 합산 비효율 → **precomputed 정석**
- **시그널 엔진** (T-SIG) 실시간 5m·30m 시그널 생성 시 합산 fresh 필요

---

## 2. 결정 의제 (사용자 결정 대기)

### 2-1. 의제 A — 합산 위치

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **A-1 백엔드 precomputed** ⭐ | cold-start 1m 적재 직후 5m~1h 합산 → DB INSERT. 조회는 단순 SELECT | 모든 클라이언트 동일 source · MCP·룰엔진·백테스트 정합 · DB ROW 작음(38K total) · 조회 latency 작음 | cold-start 시 합산 비용 단발 + DB INSERT(미미) |
| A-2 백엔드 on-the-fly | API 호출 시 1m fetch + in-memory 합산 + Redis 캐시 | DB ROW 절약 · 데이터 정합 자동 (1m이 진실원) | 매 호출마다 합산 + Redis miss 시 비효율 · 백테스트 호출 시 부담 |
| A-3 백엔드 하이브리드 | Redis = 당일 진행 중 / DB = 마감된 기간 precomputed | 정합 + 효율 균형 | Redis ↔ DB flush 코드 복잡도 |
| B 프론트 합산 | LWC v4 클라이언트에서 1m fetch 후 client-side 합산 | 백엔드 부담 0 | **MCP·룰엔진·백테스트 모두 cover 불가 — 사용자 비전 위반** |

**architect 권장 = A-1 백엔드 precomputed** (사용자 비전·룰엔진·MCP·백테스트 정합 모두 충족).

### 2-2. 의제 B — 합산 source 시간 범위

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| 1 당일 한정 | 1m이 당일분만 cover (`FHKST03010200`) — 5m~1h도 당일분만 | KIS rate limit 영향 최소 | 장기 차트 불가 · 백테스트 본질 불가 |
| **2 과거 포함** ⭐ | 1m이 30 거래일 cover (`FHKST03010230` prod credential, 현재 main 상태) — 5m~1h도 30 거래일 cover | 5m~1h 장기 차트 + 백테스트 정합 | KIS rate limit 영향(현재 1m 적재 완료 — 추가 호출 0) · DB 사이즈 증가(미미, 38K rows) |

**architect 권장 = 옵션 2 과거 포함** (현재 main에 1m이 30 거래일 이미 적재됨 — 추가 KIS 호출 0건 + 합산 1회만 + 백테스트 본질 충족).

### 2-3. 의제 C — frontend IntervalToggle 매트릭스 갱신

현재 IntervalToggle 8종(1m·5m·30m·1h·1d·1wk·1mo·1y) → 본 ADR 채택 시 **10종 활성화** (1m·5m·15m·30m·90m·1h·1d·1wk·1mo·1y). 15m·90m 추가 의제 포함.

### 2-4. 사용자 채택 결과 (2026-05-11 채팅 명시)

> "A-1 백엔드 precomputed + 옵션 2 과거 30 거래일 + 5종 활성화 — 2026-05-11 사용자 채팅 명시"

- 의제 A → **A-1 백엔드 precomputed** 채택
- 의제 B → **옵션 2 과거 30 거래일** 채택
- 의제 C → **5종 활성화** (5m·15m·30m·90m·1h) 채택

본 ADR Status `Accepted` 갱신 (헤더). 후속 sub-agent 디스패치는 §7-1 매트릭스 + §9 NXT 결합 영향 반영 의무.

---

## 3. 권장안 (architect 분석)

> **종합 권장: A-1(백엔드 precomputed) + 옵션 2(과거 포함) + IntervalToggle 5종 5m·15m·30m·90m·1h 활성화**

사유:

1. **사용자 비전 정합** — magicJar = MCP 서버 + 룰 기반 quant trading 본체. 백엔드 합산만 MCP 외부 client·룰엔진·백테스트 cover.
2. **현재 main 상태 활용** — 1m 30 거래일 적재 완료. 추가 KIS 호출 0건 + 합산 1회만으로 5m~1h cover.
3. **DB ROW 부담 미미** — 종목 10개 × 30 거래일 합산 매트릭스 총 ~38K rows (5m 22.8K + 15m 7.5K + 30m 3.9K + 90m 1.5K + 1h 2.1K).
4. **조회 latency 작음** — precomputed 영속 → 단순 SELECT (on-the-fly 합산 매번 회피).
5. **Redis 정합 자연** — ADR 0029 §2-1 매트릭스 확장만으로 당일 진행 중 5m~1h Redis 키 추가 가능.

---

## 4. 채택 시 구현 plan (사용자 결정 후 sub-agent 디스패치)

### 4-1. 데이터 영속 매트릭스 (채택 시 ADR 0057 §2-1 정정 / ADR 0019 §2 재통합)

| Interval | Source | KIS endpoint / tr_id | Cache | DB 영속 |
|---|---|---|---|---|
| **1m (1분봉)** | KIS REST | `FHKST03010230` (과거 30 거래일, prod credential) | Redis (당일) | **DB 영속 (30 거래일, 종목당 ~10K rows)** |
| **5m / 15m / 30m / 90m / 1h** | **1m 합산 가공 (백엔드 precomputed)** | — (1m 소비) | Redis (당일 진행 중 boundary 마감 시) | **DB 영속 (30 거래일, 종목당 ~76+25+13+5+7 = 126 bar/일 × 30 = 3.78K rows)** |
| **1d / 1wk / 1mo / 1y** | KIS REST | `FHKST03010100` (prod credential) | Redis cache (옵션) | DB 영속 |

### 4-2. 합산 로직 명세 (CandleAggregator 도메인 컴포넌트 부활)

**위치**: `domain/.../market/CandleAggregator.kt` — Pure Kotlin, Spring/JPA 의존 0 (헥사고날 정합).

**입력**: `List<Candle1m>` (정렬 보장 — KIS REST 응답 시간 오름차순 정렬 가정)
**출력**: `List<CandleNm>` (target interval = 5m·15m·30m·90m·1h)

**기준 timestamp 정렬**:
- 5m: 09:00, 09:05, 09:10, ..., 15:25 (개장 09:00 KST, 마감 15:30 KST — 정규장 6.5시간 = 390분)
- 15m: 09:00, 09:15, 09:30, ..., 15:15
- 30m: 09:00, 09:30, 10:00, ..., 15:00
- 90m: 09:00, 10:30, 12:00, 13:30, 15:00 (5 bar/일 — 마지막 bar는 15:00~15:30 closing 포함)
- 1h: 09:00, 10:00, 11:00, ..., 14:00, 15:00 (7 bar/일 — 마지막 bar는 15:00~15:30 closing 포함)

**합산 공식**: `OHLCV = (first.open, max(highs), min(lows), last.close, sum(volumes))`

**경계 케이스**:
- 점심 휴장: 한국 정규장 점심 휴장 없음(09:00~15:30 연속) — 미해당
- 거래정지/거래중단일: 1m 데이터 자체가 빈 일자는 5m~1h 빈 array (mock 합성 금지 — `feedback_no_mock_data.md`)
- 시간외/장 마감 후 (15:30+): 본 ADR 5m~1h은 정규장 한정. 시간외 단일가 / 거래량은 별도 의제
- 동시호가(09:00 시초가): KIS 1m 응답의 9:00 bar가 동시호가 거래량 포함하는지 raw 출처 검증 의무 (heuristic: open=시가/high=시초가 max/low=시초가 min/close=9:00 last → 그대로 합산)

**raw 출처 인용 의무 (헌법 §3)**: 합산 코드 inline 주석에 `FHKST03010230` raw URL + KIS 응답 필드(`stck_bsop_date`, `stck_cntg_hour`, `stck_oprc`, `stck_hgpr`, `stck_lwpr`, `stck_prpr`, `cntg_vol`) 명시.

### 4-3. 트리거 흐름

```
cold-start (CandleBootstrapRunner / WatchlistIngestionWorker)
    ↓
1m 적재 (FHKST03010230, 30 거래일, prod credential) → DB INSERT
    ↓
**즉시 5m·15m·30m·90m·1h 합산** (CandleAggregator)
    ├─ 5m 합산 → DB INSERT
    ├─ 15m 합산 → DB INSERT
    ├─ 30m 합산 → DB INSERT
    ├─ 90m 합산 → DB INSERT
    └─ 1h 합산 → DB INSERT

장중 (KIS WS tick 도달)
    ↓
candle:current:{code}:1m 갱신 (ADR 0029 기존)
    ↓
1m closing (분 단위)
    ├─ candle table INSERT (interval='1m')
    └─ **boundary 마감 검증** (CandleAggregator)
         ├─ 5m boundary (09:05, 09:10, ...) → 직전 5건 1m 합산 → Redis candle:current:{code}:5m 갱신
         ├─ 15m boundary (09:15, 09:30, ...) → 직전 15건 1m 합산 → Redis 갱신
         ├─ ... (동일 패턴)
         └─ 마감 시 candle table INSERT

장 마감 (DailyCloseFinalizer 16:00 KST)
    └─ Redis 5m~1h 당일 행 → DB INSERT + Redis evict
```

### 4-4. Redis 키 매트릭스 (ADR 0029 §2-1 확장)

| 캐시 키 | 데이터 | 갱신 주기 | TTL |
|---|---|---|---|
| `candle:current:{code}:1m` | 당일 진행 중 1분봉 (기존) | KIS WS tick | 1분 closing 시 evict |
| `candle:current:{code}:5m` | 당일 진행 중 5분봉 | 5분 boundary 마감 시 합산 | 5분 closing 시 evict |
| `candle:current:{code}:15m` | 당일 진행 중 15분봉 | 15분 boundary 마감 시 합산 | 15분 closing 시 evict |
| `candle:current:{code}:30m` | 당일 진행 중 30분봉 | 30분 boundary 마감 시 합산 | 30분 closing 시 evict |
| `candle:current:{code}:90m` | 당일 진행 중 90분봉 | 90분 boundary 마감 시 합산 | 90분 closing 시 evict |
| `candle:current:{code}:1h` | 당일 진행 중 60분봉 | 60분 boundary 마감 시 합산 | 1시간 closing 시 evict |

### 4-5. migration plan (1m 이미 적재된 종목 backfill)

현재 main에 10종목 × 30 거래일 1m 적재 완료 상태 — 본 ADR 채택 시 **일회성 backfill**:

```sql
-- migration script (Flyway 또는 일회성 SQL, market-data sub-agent 영역)
-- 모든 watchlist 종목의 5m·15m·30m·90m·1h 합산 backfill
-- 의사 코드 (실제 구현은 Kotlin Spring 컴포넌트):
FOR code IN (SELECT DISTINCT code FROM candle WHERE interval='1m'):
    FOR target IN ('5m','15m','30m','90m','1h'):
        rows := SELECT * FROM candle WHERE code=:code AND interval='1m' ORDER BY ts ASC
        aggregated := CandleAggregator.aggregate(rows, target)
        INSERT INTO candle (code, interval, ts, ohlcv, source) VALUES (...)  -- ON CONFLICT DO NOTHING
```

**호스트 trigger**: `BackfillAggregateRunner` (`@Component @Conditional` — 일회성 flag) 또는 별도 admin endpoint `POST /api/admin/backfill-aggregate?code=...`.

### 4-6. UI 영향 (frontend)

- `IntervalToggle` — **5m·15m·30m·90m·1h 5종 모두 활성화** (현재 5m·30m·1h만 노출 → 15m·90m 추가)
- API 응답 — `200 OK + meta.aggregationPending=false` (현재 `true` 정합 정정)
- LWC v4 차트 `setData()` 정상 동작

### 4-7. CHECK constraint (DB)

ADR 0019 §2-6 CHECK constraint 그대로 유지 (`'1m','2m','5m','15m','30m','90m','1h','1d','1wk','1mo','1y'`). DDL 변경 0건.

---

## 5. 결과 (채택 시)

- **5m·15m·30m·90m·1h 합산 가공 활성화** — 사용자 비전(MCP·룰엔진·백테스트) 정합
- **DB ROW 38K 추가** — 현재 ROW 대비 미미 (1m 100K+ 대비 38K 추가)
- **추가 KIS 호출 0건** — 1m 적재 완료 상태 활용
- **LWC v4 차트 즉시 응답** — precomputed SELECT
- **자동매매 본체 정합** — 룰 엔진·DSL·백테스트 모두 동일 합산 source 참조
- **MCP 서버 노출 정합** — 외부 client(Claude·Cursor 등) 5m~1h interval 요청 가능
- **credential 단일 채널** — prod credential 단일 (ADR 0057 정합)

---

## 6. 절충 + Rollback 시나리오

### 6-1. 절충

- **백엔드 합산 비용 (cold-start 시 단발)** — 30 거래일 × 5 interval 합산 = 종목당 ~5초 추정. cold-start 후 0.
- **합산 결과 staleness risk** — 1m이 진실원, 합산은 derived. 1m UPDATE/DELETE 시 합산 재계산 의무. 본 ADR은 1m append-only 가정 → staleness risk 작음.
- **boundary 마감 latency** — 5분 boundary 마감 후 합산 1m 5건 → Redis 갱신 latency ~10ms 추정. STOMP 사용자 체감 1초 이내 정합.

### 6-2. Rollback 시나리오

본 ADR을 supersede 또는 revoke 결정이 나는 시나리오:

1. **합산 비용이 cold-start 30초 한도 초과 (10종목 이상 추가 시)** — on-the-fly 옵션 A-2로 전환 검토
2. **MCP 외부 client 합산 정합 요구 변경** — 본 ADR 핵심 사유 흔들림 → 재검토
3. **1m UPDATE 시 합산 재계산 코드 부담 과도** — 옵션 A-2 on-the-fly로 전환

### 6-3. Rollback 절차

1. 본 ADR 상태를 `Superseded by ADR XXXX, YYYY-MM-DD` 헤더 갱신
2. `domain/.../market/CandleAggregator.kt` 제거 + on-the-fly 변환 (옵션 A-2 Kotlin Spring 컴포넌트로 이동)
3. `candle` 테이블 5m·15m·30m·90m·1h row 일회성 DELETE (선택 — 영속 vs 휘발 결정)
4. ADR 0057 §2-1 + ADR 0019 §2 매트릭스 갱신
5. CHANGELOG row 추가 + master-architecture §10 ADR 인덱스 갱신

---

## 7. 채택 시 후속 작업 (별도 PR — 본 ADR `Accepted` 갱신 후 sub-agent 디스패치)

본 plan PR은 **architect 단독 — ADR/문서만**. 실무 코드 변경은 별도 sub-agent PR 매트릭스.

### 7-1. sub-agent 디스패치 매트릭스

| sub-agent | 영역 | 산출물 |
|---|---|---|
| **strategy-engine** (또는 backend-core) | `domain/.../market/CandleAggregator.kt` 도메인 본체 (Pure Kotlin, Spring 의존 0) | 합산 알고리즘 + 단위 테스트(JVM 의존 0) + raw URL 주석 |
| **market-data** | `consumer/.../market/AggregateBackfillRunner.kt` 또는 `WatchlistIngestionWorker` 확장 (cold-start 시 1m 적재 직후 합산 트리거) + `TickToMinuteAggregator` 확장 (boundary 마감 시 Redis 5m~1h 갱신) + `DailyCloseFinalizer` 확장 (장 마감 시 5m~1h DB INSERT + Redis evict) + 일회성 backfill admin endpoint `POST /api/admin/backfill-aggregate` | KIS REST 추가 호출 0건 + Redis 키 매트릭스 확장 + STOMP fanout 토픽 추가 (`/topic/market.candle.5m.{code}` 등) |
| **frontend** | `frontend/src/pages/Symbol/IntervalToggle.tsx` — 15m·90m 추가 (5종 5m·15m·30m·90m·1h 모두 활성화) + `useSymbolCandles.ts` 정합 갱신 (interval 8종 → 10종) + LWC v4 차트 검증 dogfooding evidence | UI 매트릭스 갱신 + interval 5종 실측 evidence |
| (선택) **qa** | 합산 결과 검증 — 5m 데이터 = 1m 5건 합산 결과 정합 확인 (sample 10일 × 10종목 × 5 interval = 5,000건 검증) | spec test 또는 dogfooding evidence 보고 |

### 7-2. CHANGELOG 슬롯 사전 발행 (헌법 §4)

본 plan PR diff에 sub-agent별 CHANGELOG 슬롯 row + anchor 사전 prepend (CHANGELOG.md):

```html
<!-- pr-N-slot-strategy -->
| 2026-05-XX | **STRATEGY: PR-N strategy-engine — TBD (작성: strategy-engine)** | ... |
<!-- pr-N-slot-market-data -->
| 2026-05-XX | **MARKET-DATA: PR-N market-data — TBD (작성: market-data)** | ... |
<!-- pr-N-slot-frontend -->
| 2026-05-XX | **FRONTEND: PR-N frontend — TBD (작성: frontend)** | ... |
<!-- pr-N-slot-qa -->
| 2026-05-XX | **QA: PR-N qa — TBD (작성: qa)** | ... |
<!-- pr-N-slot-end -->
```

(슬롯 row PR 번호는 다음 사이클 진입 시 orchestrator가 PR-N 채번 — 본 plan PR에서는 slot template만 명시)

### 7-3. 영향 ADR/문서 매트릭스 (정합 갱신 지시)

| 문서 | 갱신 내용 | 갱신 시점 |
|---|---|---|
| ADR 0019 (캔들 저장 정책) | 헤더 노트 — "5m~1h 합산 가공 트랙 완료, §2 매트릭스 재통합". §2 매트릭스 5m~1h 행 추가 (`source = 1m aggregation`, DB 영속) + §2-5 supersede 제거 (CandleAggregator 부활) | 본 ADR `Accepted` 갱신 시 동시 |
| ADR 0029 (당일 진행 중 캔들 Redis 캐시) | §2-1 Redis 키 매트릭스 — 5m·15m·30m·90m·1h boundary 캐시 행 5건 추가 (§4-4 인용) | 본 ADR `Accepted` 갱신 시 동시 |
| ADR 0057 (KIS-only data source) | §2-1 매트릭스 5m~1h 행 — "1m 합산 가공 (백엔드 precomputed)" 명시 / §2-2 UI 노출 — "데이터 없음" → "활성화" / §2-3 cold-start 흐름 — 1m 적재 직후 합산 INSERT 단계 추가 / §2-5 "방향 확정 — 합산 위치·source 결정 완료, ADR 0062 참조" 갱신 / §3 결과 — "5m~1h 활성화 완료" | 본 ADR `Accepted` 갱신 시 동시 |
| ADR 0030 (master-architecture SoT) | §10 ADR 인덱스 — ADR 0062 add | 본 ADR `Accepted` 갱신 시 동시 |
| `docs/architecture/master-architecture.md` | §3 데이터 흐름 — 합산 트리거 추가 / §10 ADR 인덱스 — ADR 0062 add | 본 ADR `Accepted` 갱신 시 동시 |
| `docs/architecture/candle-data-policy-v2.md` | 매트릭스 5m~1h 행 — "1m 합산 가공 (백엔드 precomputed)" 갱신 (현재 "1m 합산 가공 예정 — backlog §31") | 본 ADR `Accepted` 갱신 시 동시 |
| `_workspace/arch/adr/README.md` | ADR 0062 index row 추가 + 후속 인덱스 §10-X에서 0062 시프트 | 본 plan PR + `Accepted` 갱신 시 동시 |
| `_workspace/backlog.md` §31 | "방향 확정 + 위치·source 결정 대기" → "ADR 0062 plan 발행 + 사용자 결정 대기" 갱신. `Accepted` 갱신 시 §31 처리 완료 archive | 본 plan PR (갱신) + 다음 사이클 (archive) |

### 7-4. external-api-policy 영향

본 ADR 채택 시 외부 API 정책 영향 0 (KIS 단일 출처 유지, 합산은 1m 원천 기반).

---

## 9. NXT 결합 영향 (ADR 0063 Accepted 정합 — 2026-05-11 추가)

### 9-1. 배경

ADR 0063 (NXT/통합거래 매트릭스 cover) 사용자 Accepted (2026-05-11) — UN 통합 + 08:00~20:00 + threshold 700 채택. 결과로 **1m 베이스 데이터가 390 bar/일 → 720 bar/일로 확장** (KRX 정규장 6.5시간 + NXT pre/after-hour 5.5시간 = 12시간 cover, threshold 350 → 700).

본 ADR(0062)의 합산 매트릭스가 NXT 시간대(08:00~20:00 KST)를 cover하도록 boundary 정렬·DB ROW 예측·트리거 시각 모두 확장 의무. sub-agent 디스패치 시 ADR 0063 cover **선행 또는 영역 분리 직렬** 진행.

### 9-2. boundary 정렬 매트릭스 확장 (§4-2 갱신)

| Interval | KRX 정규장만 (기존) | **NXT 통합 (08:00~20:00 KST)** |
|---|---|---|
| 5m | 09:00·09:05·…·15:25 (78 bar/일) | **08:00·08:05·…·19:55 (144 bar/일)** |
| 15m | 09:00·09:15·…·15:15 (26 bar/일) | **08:00·08:15·…·19:45 (48 bar/일)** |
| 30m | 09:00·09:30·…·15:00 (13 bar/일) | **08:00·08:30·…·19:30 (24 bar/일)** |
| 90m | 09:00·10:30·12:00·13:30·15:00 (5 bar/일) | **08:00·09:30·11:00·12:30·14:00·15:30·17:00·18:30 (8 bar/일)** |
| 1h | 09:00·10:00·…·15:00 (7 bar/일) | **08:00·09:00·…·19:00 (12 bar/일)** |

### 9-3. DB ROW 예측 갱신 (§5 결과 정합)

| Interval | KRX 정규장만 (기존) | **NXT 통합 (10종목 × 30 거래일)** |
|---|---|---|
| 5m | 22,800 (78×30×10) | **43,200 (144×30×10)** |
| 15m | 7,500 (26×30×10) | **14,400 (48×30×10)** |
| 30m | 3,900 (13×30×10) | **7,200 (24×30×10)** |
| 90m | 1,500 (5×30×10) | **2,400 (8×30×10)** |
| 1h | 2,100 (7×30×10) | **3,600 (12×30×10)** |
| **합** | **~38K rows** | **~71K rows (1.85배 확장)** |

DB 부담 영향 미미 (1m 베이스 720/일 × 30 × 10 = ~216K rows 대비 33% 추가).

### 9-4. boundary 마감 트리거 시각 확장 (ADR 0029 정합)

기존 ADR 0029 boundary 마감 트리거 = KRX 마감 16:00 KST. **NXT 채택 후 20:00 KST(NXT after-hour 마감)까지 확장 의무**.

- `TickToMinuteAggregator` boundary 검증 — 08:00~20:00 cover
- `DailyCloseFinalizer` 트리거 시각 — 16:00 → **20:00 KST**
- Redis `candle:current:{code}:5m~1h` 키 — 08:00 시작 / 20:00 evict

### 9-5. sub-agent 작업 분리 권장 (§7-1 정합)

ADR 0062 + 0063 둘 다 Accepted — sub-agent 디스패치 시 영역 분리·직렬 순서 권장:

| 순서 | sub-agent | 작업 | 의존 |
|---|---|---|---|
| 1 (선행) | **market-data** | ADR 0063 NXT cover — `KisRestCandleAdapter` UN 통합 + 08:00~20:00 + threshold 700 + 재수집 (10종목 × 30일) | — |
| 2 (1m DB 적재 확인 후) | **strategy-engine** (또는 backend-core) | ADR 0062 `CandleAggregator.kt` 도메인 본체 + 단위 테스트 | 의존 없음 (병렬 가능) |
| 3 (1m + Aggregator GREEN) | **market-data** | ADR 0062 `AggregateBackfillRunner` + boundary 마감 trigger + `DailyCloseFinalizer` 20:00 KST 확장 + Redis 키 + STOMP fanout + admin endpoint | 순서 1+2 GREEN |
| 4 (병렬 가능) | **frontend** | ADR 0062 `IntervalToggle` 5종 + LWC v4 dogfooding | 순서 3 응답 GREEN |
| 5 (선택) | **qa** | 합산 정합 sample 검증 (NXT cover 후 5m·15m·30m·90m·1h 일치성) | 순서 3 GREEN |

순서 1(NXT)+2(CandleAggregator 도메인)는 영역 분리로 병렬 가능. 순서 3(market-data 합산 영속)은 1+2 GREEN 의존.

### 9-6. cross-ref

- ADR 0063 — NXT/통합거래 매트릭스 cover (본 ADR 결합 baseline)
- 영향 ADR/문서 매트릭스 (§7-3) — NXT 시각 확장 동반 갱신 (별도 사이클, modify-shared 회피)

---

## 8. 참고

- ADR 0019 — 캔들 저장 정책 (Watchlist 기반)
- ADR 0028 — Watchlist Ingestion Worker
- ADR 0029 — 당일 진행 중 캔들 Redis 캐시
- ADR 0030 — master-architecture SoT
- ADR 0037 — KIS Rate Limit
- ADR 0056 — 백테스트 엔진 아키텍처
- ADR 0057 — KIS OpenAPI 단일 데이터 출처 (본 ADR baseline)
- `docs/architecture/candle-data-policy-v2.md` — 운영 가이드
- `docs/operations/kis-api-reference.md` §5-A — FHKST03010230 (1분봉 과거 데이터)
- 사용자 메모리 `feedback_v3_skepticism_no_regression.md` (fitness gap lens) · `feedback_kis_first_policy.md` (KIS-only) · `feedback_kis_key_realonly_for_data.md` (prod 단일 채널) · `feedback_no_mock_data.md` (mock 합성 금지) · `feedback_3tier_hierarchy.md` (architect plan → sub-agent 병렬) · `project_system_vision.md` (MCP 서버 노출 비전)
- `_workspace/backlog.md` §31 (방향 확정 baseline)
