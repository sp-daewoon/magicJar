# ADR 0039 — 종목 마스터 갱신 Cron (KIS 갱신 시각 +5분)

- 상태: Accepted
- 작성일: TBD
- 결정자: architect
- 관계: ADR 0017(Instrument Master 1급 시민), ADR 0037(KIS Rate Limit)

## 1. 컨텍스트

KIS 종목 마스터 데이터(상장종목·ETF·산업분류·테마·관리종목 지정 등)는 KIS 측에서 **하루 13개 시각**에 갱신:

```
06:00 · 06:55 · 07:35 · 07:55 · 08:45 · 09:46 · 10:55
17:10 · 17:30 · 17:55 · 18:10 · 18:30 · 18:55
```

너무 일찍 호출하면 stale 데이터 — KIS 갱신이 아직 반영 안 된 상태로 fetch. magicJar는 갱신 직후 **+5분 마진** 후 sync 호출.

## 2. 결정

### 2-1. magicJar Sync Cron (13시각)

KIS 갱신 시각 + 5분:

```
06:05 · 07:00 · 07:40 · 08:00 · 08:50 · 09:51 · 11:00
17:15 · 17:35 · 18:00 · 18:15 · 18:35 · 19:00
```

### 2-2. 구현

| 항목 | 정책 |
|---|---|
| **위치** | `batch/.../scheduler/InstrumentMasterSyncScheduler.kt` |
| **메커니즘** | Spring `@Scheduled(cron)` 13개 |
| **timezone** | KST (`@Scheduled(cron = "0 5 6 * * MON-FRI", zone = "Asia/Seoul")`) |
| **영업일 게이트** | KRX 캘린더 정합 (ADR 0013 Market Session FSM) — 휴장일 skip |
| **sync 대상** | 상장종목 마스터 (KOSPI·KOSDAQ·KONEX) + ETF·ETN |
| **fetch endpoint** | KIS 종목 마스터 API (Rate Limiter 통과, ADR 0037) |
| **결과 publish** | Kafka `instrument.updated.v1` → consumer 캐시 invalidate + Redis 검색 인덱스 갱신 |

### 2-3. 배치 진행 상태

```
06:05 cron 시작
    ↓
KisInstrumentMasterAdapter.fetchAll() (Rate Limiter 통과)
    ↓
domain.Instrument 모델로 매핑 + 차이 비교 (이전 sync vs 현재)
    ├─ 신규 상장 → INSERT
    ├─ 기존 변경 (상태·산업·테마) → UPDATE
    └─ 상장 폐지 → status='DELISTED' (DELETE 금지, ADR 0017 정합)
    ↓
Kafka instrument.updated.v1 publish (changed list)
    ↓
consumer
    ├─ Redis 검색 인덱스 갱신
    └─ 캐시 invalidate (instrument:* 키)
    ↓
api STOMP /topic/instrument.updated → frontend 자동완성 인덱스 갱신
```

### 2-4. 실패 처리

| 상황 | 처리 |
|---|---|
| KIS API 응답 5xx | exponential backoff 3회 (1s/2s/4s) → 그래도 실패 시 다음 cron까지 대기 |
| EGW00201 (Rate Limit) | 1초 대기 + 1회 재시도 (ADR 0037) |
| 부분 실패 (n/m row 실패) | 성공한 row만 INSERT/UPDATE + WARN 로그 + 다음 cron에서 재시도 |
| 컨테이너 재기동 시 | 마지막 sync 시각 Redis 영속 → 다음 cron에서 재개 (멱등) |

### 2-5. 모니터링

- 로그: `[instrument-sync] cron=06:05 fetched=2845 inserted=3 updated=27 delisted=1 elapsed=4.2s`
- Prometheus metric (post-MVP): `magicjar_instrument_sync_total`, `magicjar_instrument_sync_duration_seconds`
- Slack alert: 3회 연속 cron 실패 시 (post-MVP)

## 3. 결과

- **stale 종목 데이터 0** — KIS 갱신 직후 +5분 마진으로 항상 최신
- **사용자 검색·자동완성 정확** — 신규 상장·상장 폐지·산업 변경 즉시 반영
- **상장 폐지 보존** — 생존자 편향 차단 (ADR 0017 정합)
- **Rate Limit 안전** — 13 cron 분산이라 burst 부하 0

## 4. 참고

- ADR 0017 — Instrument Master 1급 시민
- ADR 0013 — Market Session FSM (영업일 캘린더)
- ADR 0037 — KIS Rate Limit
- KIS 공식 문서 — 종목 마스터 갱신 시각
- `batch/.../scheduler/InstrumentMasterSyncScheduler.kt` — 본 ADR 구현
