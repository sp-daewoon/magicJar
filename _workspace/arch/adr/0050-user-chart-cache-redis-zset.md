# ADR 0050: 사용자 차트 캐시 — Redis ZSet first + LRU 10 + 봉단위 자유 입력

- 상태: Accepted
- 작성일: 2026-05-05
- 결정자: architect (opus 4.7 1M)
- master §위치: `docs/architecture/master-architecture.md` v2 §A.2 (시세 흐름) · §3-5 (캔들)
- 트랙: T2 (Market Data Pipeline) — 정책 정정
- 선행: ADR 0048 (1m-only candle policy + application aggregation), ADR 0049 (mount-only bootstrap), ADR 0043 (multi-tenant)
- Partial supersede: ADR 0048 — **사용자 차트 화면 한정**으로 DB candle → Redis ZSet 전환. 백테스트·룰 엔진은 DB 영속 그대로 유지.

---

## 1. 맥락

### 1-1. 사용자 진단·결정 (2026-05-05)

> "데이터 저장을 DB에 종목별로 하고 있잖아. 그걸 그냥 아예 하지말고 API로 KIS 일봉을 ZSet 기반으로 Redis에 넣어서 그거 그냥 실시간 사용자가 보는 값만 차트에 그리는건 어떨까."
>
> "사용자(계정기준 5개) 정도 하니깐 아이디:종목:차트 정보 쭉 넣어서. 분봉은 클릭할때마다 1분봉 기준으로 1000개씩 가져오는거지 야후를 통해서. 5분 봉 누르면 1000/5를 가져오는거고."
>
> "최근 10개 종목 중 마지막 값이랑 오늘 값만 비교해서. 11개 종목 누르면 가장 마지막에 접근한 종목 redis key 자체를 지우는거지."
>
> "룰 엔진은 DB historical을 할거야. 단 이 메뉴랑은 별개고 종목을 선정해서 데이터를 가져와서 할꺼고 종목 발굴 이런 느낌의 기술이야."

### 1-2. 현행 진단 (ADR 0048 한계)

| 영역 | 현재 | 사용자 보고 한계 |
|---|---|---|
| candle 영속 | DB candle 영구 (M1·D1) | 단일 종목 차트만 보는데 영구 영속 불요 |
| 데이터 모델 | 모든 사용자 공유 단일 source | 멀티 사용자 5명 한정 — 격리 OK |
| 봉단위 UI | 고정 버튼 (1m/5m/30m/60m/90m/120m + D/W/MO/Y) | 자유 입력 (10m·45m 등) 불가 |
| 합성 | application 메모리 합성 | 좌동 (변경 없음) |
| 백테스트 | DB 영속 query | 좌동 (변경 없음) — 별도 메뉴, 종목 발굴 시 데이터 별도 적재 |

### 1-3. 정책 컨텍스트

- **ADR 0049 mount-only** — 사용자가 진입한 종목만 시세 구독. 본 ADR이 candle도 같은 정책 (사용자 진입 시만 cache).
- **ADR 0043 multi-tenant** — `userId` 키 분리. 본 ADR이 Redis 키에 `userId` 명시.
- 사용자 정책 (memory `project_kis_key_management.md`) — 사용자 5명 한정, MCP 서버 본체.

---

## 2. 결정

### 2-A. 데이터 source 분리

| 영역 | Source | 영속 | 영향 ADR |
|---|---|---|---|
| **사용자 차트 화면** | Redis ZSet | LRU 10 종목 (TTL 없음) | 본 ADR 0050 |
| **룰 엔진·백테스트·종목 발굴** | DB candle | 영구 (D1·M1 only) | ADR 0048 유지 |

= **사용자 화면용**(Redis)과 **historical analysis용**(DB)이 별도 파이프라인. 마이그·배포 시 둘 다 작동.

### 2-B. Redis 키 구조

```
candle:{userId}:{code}:{interval}
  Type: ZSet (sorted set)
  Score: openTime epoch second
  Value: JSON {o, h, l, c, v}
  TTL: 없음  ← LRU 정책으로만 cleanup

candle:recent:{userId}
  Type: ZSet (LRU 추적)
  Score: 마지막 access epoch ms
  Value: code (종목)
  LIMIT: 10
  
candle:lastfetch:{userId}:{code}:{interval}
  Type: String
  Value: lastFetched epoch second
  사용: 재진입 시 갱신 인터벌 결정 (마지막 봉 < today 또는 < lastBusinessDate면 새 fetch)
```

### 2-C. LRU 10 종목 정책

- 사용자가 종목 진입 시 `candle:recent:{userId}` ZADD score=now
- 11번째 신규 종목 진입 시:
  - `ZRANGE candle:recent:{userId} 0 0 WITHSCORES` → 가장 오래된 종목 X
  - `DEL candle:{userId}:X:*` (모든 interval)
  - `ZREM candle:recent:{userId} X`
- 재진입 시 score 갱신 (LRU 갱신) + `lastfetch` 검증 후 필요하면 새 fetch

### 2-D. 봉단위 fetch·합성 정책

| Interval | default 봉 수 | 시간 범위 | Source | 합성 |
|---|---|---|---|---|
| **1m** | 1000 | 약 2.5 영업일 | yfinance | direct |
| **3m / 5m / 10m / 15m / 30m** | 1000 | 봉단위 × 1000분 | yfinance 1m | application 합성 |
| **60m / 90m / 120m** | 1000 | 봉단위 × 1000분 | yfinance 1m → 합성 | application |
| **1d** | 500 | 약 2년 (영업일) | KIS `inquire-daily-itemchartprice` (D, 5 페이지) | direct |
| **1w** | 200 | 약 4년 | KIS (W, 2 페이지) | direct |
| **1mo** | 60 | 5년 | KIS (M, 1 페이지) | direct |
| **1y** | 20 | 20년 | KIS D1 → 합성 | application |

**자유 입력**: 분 단위만 (`12m`·`45m` 등). 일 이상은 버튼만.

**fetch 절감 (재진입)**:
- 분봉: `candle:lastfetch` ≥ 5분 전이면 cache hit, 그 이전이면 last 봉만 추가 fetch
- 일봉: `candle:lastfetch` >= today 또는 `lastBusinessDate`면 cache hit

### 2-E. 합성 위치 — backend

사용자 결정: 합성은 backend가 수행. frontend에 raw 1000봉 보내고 frontend가 합성하면 데이터 양 ↑. backend가 1000/N 합성 후 응답 → frontend payload 절감.

application service `UserChartCandleService.fetchOrCompose(userId, code, interval, limit, before)`:
1. interval 분류 (raw vs synthesis)
2. raw → Redis ZSet 조회 (cache hit / miss → fetch)
3. synthesis → 1m raw fetch 후 `CandleAggregator` 적용

### 2-F. fetch 흐름 — Kafka 경유 유지

사용자 결정: yfinance·KIS REST 결과를 **Kafka publish → consumer가 Redis ZADD**. 현재 구조 호환:
- pyworker yfinance fetch → `candle.minute.aggregated.v1` (기존)
- batch KIS REST fetch → `candle.daily.v1` (신규) 또는 동일 토픽
- consumer 신규 listener `UserCandleCacheListener` → Redis ZADD

이유: 기존 Kafka 인프라 그대로. consumer가 단일 책임 (Redis 갱신). `candle:lastfetch` 갱신도 listener가 처리.

### 2-G. 사용자 식별 — users 테이블

- `users.user_id` (UUID) 기반
- 현재 `DEFAULT_USER_ID = 00000000-0000-0000-0000-000000000001` 단일 시드 사용자
- 5명 진입 시 SettingsModal 정책 (memory) 따라 각자 user_id 생성 (인증 도입은 후속 ADR)
- api `InstrumentController.candles` request에 `userId` 파라미터 (default = DEFAULT_USER_ID)

### 2-H. KIS WS 실시간 tick — Redis 미저장

- KIS WS tick은 **STOMP 1초 fanout만** (현재 흐름 유지)
- Redis ZSet에 last 봉 update 안 함 — 사용자 차트는 historical + STOMP 마지막 봉 별도 표시 (frontend 책임)

---

## 3. 영향 범위

### 3-1. 코드 변경 (3 PR 단계)

| Phase | 영역 | 작업 | 사이즈 |
|---|---|---|---|
| **R3-1** | domain | `UserCandleCachePort` (interface) | S |
| | consumer | `RedisUserCandleCacheAdapter` (ZSet impl + LRU) | M |
| | consumer | `UserCandleCacheListener` (Kafka → ZADD) | S |
| | api | `UserChartCandleService` (fetch or cache hit 분기) | M |
| | api | `InstrumentController.candles` 라우팅 — Redis-first | S |
| | 테스트 | 단위·통합 (mockk·embedded redis) | M |
| | **Total** | | **1일** |
| **R3-2** | batch | KIS W/MO endpoint (FID_PERIOD_DIV_CODE 분기) | M |
| | api | 봉단위 합성 정책 — `CandleAggregator` 확장 (10m·임의 분봉) | M |
| | frontend | `IntervalSelector` 직접 입력 input 추가 (10m 버튼 신설 + 자유 입력 input) | S |
| | **Total** | | **1일** |
| **R3-3** | api·consumer | scroll cursor 정합 + lastfetch 마지막값 비교 절감 | M |
| | 라이브 검증 | 다종목 진입 + LRU 11번째 진입 evict 검증 + 재진입 갱신 | S |
| | **Total** | | **0.5일** |

### 3-2. 회귀 영역 (검증 필수)

- DB candle 영속은 그대로 유지 (백테스트·룰 엔진 영향 0)
- KIS WS 실시간 tick (`market.tick.v1`) 그대로 유지
- 분봉 backfill Kafka 흐름 (`candle.minute.aggregated.v1`) 그대로 유지

### 3-3. 모니터링

- Redis 키 수: `candle:*` 사용자 5명 × 종목 10 × interval 평균 4개 = **200 key 이내**
- 메모리: 종목당 1000봉 × 80 byte × 200 = **약 16MB**
- KIS API 호출: 사용자 진입당 1d (5 페이지) + 1m (yfinance 1회) — 절감 정책으로 재진입 시 API 호출 0

---

## 4. 수용 기준

- 사용자 5명 동시 진입 시 격리 정상
- 11번째 종목 진입 시 `candle:recent:{userId}` 가장 오래된 종목 + `candle:{userId}:*` 모든 interval DEL
- 재진입 시 `candle:lastfetch` 검증 → cache hit 즉시 응답 (ms)
- 자유 입력 봉단위 (`12m`·`45m`) backend 합성 정상
- DB candle 테이블 영속 회귀 0 (백테스트 정상)

---

## 5. 비범위 (후속 ADR)

| 항목 | 사유 |
|---|---|
| 백테스트 데이터 마이그 | 사용자: "데이터를 별도로 마이그레이션할테니깐 그때 얘기하면됨" — 별도 사이클 |
| 사용자 인증 (5명 → 더 많이) | ADR 0049 SettingsModal + 향후 인증 ADR |
| KIS REST polling fallback (T13) | R3 별도 — 시세 끊김 fallback |
| frontend cold-start auto-retry | 본 ADR로 흡수 — Redis cache hit이면 즉시 응답이라 retry 불요 |

---

## 6. 변경 이력

| 날짜 | 변경 | 사유 |
|---|---|---|
| 2026-05-05 | 발행 | 사용자 결정 — 차트 화면 Redis-first, 백테스트는 DB 유지 |
| 2026-05-05 | R3-4 — `market.candle.user.v1` Kafka 토픽 + dual-write (api 직접 ZADD + consumer ZADD) | KIS W/MO endpoint + IntervalSelector 자유 입력과 함께 한 사이클 (PRs #161·#163·#162) |
| 2026-05-05 | **R3-5a — 사용자 직접 D1 요청을 KIS direct로 전환 (DB fallback 제거) + STOMP fanout 추가** | 사용자 지적 §2-A 정합 위반 — 사용자 차트 path에서 DB 거치고 있었음. 정정: D1·W·MO 모두 KIS direct + Kafka publish → consumer 단일 ZADD writer + api STOMP `/topic/market.candle.user.{userId}.{code}` fanout으로 실시간 차트 갱신 (페이지 새로고침 불요). M1·internal D1 base는 R3-5b에서 yfinance/외부 API direct로 통합 예정. 부수 hotfix 2건 흡수: frontend `DEFAULT_USER_ID` UUID `00000000-...-0001` → `74683223` (V21 정합), `index.html` favicon `/vite.svg` 404 차단 (data: URI). |
