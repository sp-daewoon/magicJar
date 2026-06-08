# ADR 0116 — KIS Rate Limit Phase 2 — priority별 별도 버킷 + KIS 실제 한도 정합

- 상태: **Proposed (2026-06-08, 별도 브랜치 전용 · 자본경로/인프라 — 설계 검증 후 구현 · canary 전 급반영 금지)**
- 작성일: 2026-06-08 KST
- 결정자: architect (사용자 결재 대기)
- 관계: **ADR 0037 amend** (KIS Rate Limit 강제 — 단일 bucket LOCK → 본 ADR이 priority별 별도 버킷으로 supersede), ADR 0044(Notification — alert path), ADR 0083·0088·0092(공유 모듈 패턴), ADR 0094·0095(운영 단계 SLO S-4), ADR 0100(T-MARKET cron INGESTION_LOW caller), ADR 0105(1m backfill TPS budget), ADR 0112(AI 종목발굴 — **번호 충돌 정정 대상**, 본 ADR과 무관)
- 참조 보고서: `_workspace/reports/2026-05-22-kis-tps-exceeded-root-cause.md` · `_workspace/reports/2026-06-04-api-tps-exceeded-investigation.md`
- 참조 정책: `docs/architecture/external-api-policy.md` §2-1·§2-2 (SoT 정합 갱신 동반)

---

## 1. 컨텍스트

### 1-1. 현 상태 — 코드가 SoT보다 앞서 있음 (orphan decision)

본 ADR 발행 전 코드 audit 결과 (2026-06-08 architect):

- **commit `2837b2cf` (2026-06-05, backend-core)** 가 단일 bucket → **2-bucket reserve 모델**로 이미 진화시킴 (api·batch·consumer 3 어댑터 + application.yml 3건 + external-api-policy.md §2-2 동시 갱신).
  - shared pool (capacity = `bucketCapacity − userPathReserveTokens` = 7): 모든 priority 1차 소비
  - USER_PATH 전용 reserve (`{bucketKey}:user-reserve`, capacity = `userPathReserveTokens` = 3): shared 소진 시 USER_PATH fallback
  - refill 방식도 **`refillGreedy` → `refillIntervally`** 전환 (한 초 burst 억제)
- 그러나 그 commit은 **"ADR 신설 0 — orchestrator 후속 ADR 0112 의제"** 라고 명시. 그런데 **ADR 0112는 실제로 "AI 종목발굴 Claude CLI bridge"** 로 별도 채번됨 → **코드·정책·docker 17개 파일의 `ADR 0112 의제` 주석이 orphan 참조** (정식 ADR 부재 + 잘못된 번호).
- **ADR 0037 본문은 여전히 "단일 bucket LOCK"** — 코드는 2-bucket으로 진화했는데 SoT(ADR 0037 §2-1·§2-3 share 분할표)가 따라오지 않은 **drift**.

본 ADR은 (a) 그 orphan decision을 **정식 승인**하고, (b) 작업 지시가 요구한 **priority별 별도 버킷 + KIS 실제 한도 정합 + 스케줄러 분산 + 관측**의 Phase 2 설계를 LOCK한다.

### 1-2. 확정된 문제 (보고서 2건 + audit evidence)

**문제 1 — 단일 shared bucket + priority 라우팅이 reserve fallback 1단뿐**:
현 2-bucket reserve는 **USER_PATH만** 보호한다. BACKFILL(스케줄러 캔들)·INGESTION_LOW(지수·섹터 cron)·INGESTION(watchlist·approval)은 **모두 동일 shared pool 7토큰을 경쟁**한다. `MarketIndexScheduler` 1분 cron(지수·섹터 26 req/min — ADR 0100 §2-3)·`LiveDailyCandleScheduler` 5분 cron이 shared를 독점하면 INGESTION(approval·watchlist)도 막힌다. **priority가 USER_PATH 보호 외에는 여전히 정보표시용**.

**문제 2 — 정신모델 불일치 (mental model mismatch)**:
2026-05-22·2026-06-04 보고서 evidence — Bucket4j `acquire` timeout 0건("통과")인데도 KIS server-side EGW00201 실제 차단 109건(이후 incremental fix로 ~0.2/분까지 감소). `refillGreedy(10, 1s)`는 idle 후 **0.0초에 10토큰 즉시 burst** 허용 → KIS가 더 엄격한 알고리즘(sub-second 분해 추정)이면 위반. **commit 2837b2cf가 `refillIntervally`로 이미 전환**했으나 본 ADR이 그 전환의 근거를 정식 LOCK하고 KIS 실제 한도 raw 출처 정합을 검증한다.

### 1-3. KIS 실제 한도 (raw 출처 — 헌법 §3)

| 항목 | 값 | 출처 |
|------|-----|------|
| REST 호출 한도 | **초당 10건 (TPS) — 계정·앱키당** | `docs/architecture/external-api-policy.md` §2-1 (raw 출처: https://github.com/koreainvestment/open-trading-api + apiportal.koreainvestment.com "API 호출 유량 안내(REST, 웹소켓) 2026.04.20 기준") |
| 윈도우 분해 (1s vs sub-second) | **공식 미명시 (추정)** | KIS는 "초당 10건"만 표기. sub-second 강제 여부는 공식 문서에 없음 → 보고서 §4-5 가설(sliding window 또는 100ms당 1건). **본 ADR은 "추정"으로 명시하고 보수적으로 균등 간격 refill을 채택** |
| 위반 시그널 | `EGW00201` 또는 HTTP 429 | external-api-policy.md §2-1 |
| 토큰 발급 | 1분 1회 (EGW00133 — **TPS bucket 별도**) | ADR 0036 |
| WS 동시 구독 | 41건 (별도 — ADR 0048) | ADR 0048 |

> **raw fetch 한계 보고 (헌법 §3 정직성)**: 2026-06-08 본 ADR 작성 중 KIS apiportal 유량 공지 원문 raw fetch는 404/403/504로 직접 미확보. 대신 **레포 내 이미 KIS-first 검증된 권위 자료**(external-api-policy.md §2 — 헌법 §3 절차로 이전 수집)를 인용. **sub-second 분해 여부는 KIS 공식에 명시된 바 없음**을 명확히 한다 — 우리 토큰 모델을 KIS 실제보다 보수적으로 잡는 근거가 이 불확실성이다.

---

## 2. 결정

### 2-A. orphan decision 정식화 (commit 2837b2cf 승인)

현 운영 코드의 2-bucket reserve 모델 + `refillIntervally` 전환 + `MAGICJAR_KIS_USER_PATH_RESERVE` ENV를 **정식 승인**한다. 동시에:

1. 코드·정책·docker **17개 파일**의 `ADR 0112 의제` 주석을 **`ADR 0116`** 로 정정 (R1 일괄 — add-only 정정, 로직 변경 0).
2. ADR 0037 §2-1 "단일 bucket LOCK" + §2-3 "share 분할(api 5/batch 3/consumer 2)" 를 본 ADR이 **supersede** (amend 마커 — ADR 0037 본문은 historical 보존, 본 ADR이 현행 권위).
3. external-api-policy.md §2-2 "ADR 0112 의제" → "ADR 0116" 정정 + Phase 2 3-tier 버킷 매트릭스 갱신.

### 2-B. priority별 별도 버킷 (3-tier) — Phase 2 핵심

현 2-bucket(shared + USER_PATH reserve)을 **priority별 명시적 capacity 배분**으로 확장한다. KIS 전체 한도 10/s 안에서 배분:

| 버킷 | priority | capacity | refill | Redis key | 의도 |
|------|----------|----------|--------|-----------|------|
| **USER 버킷** | USER_PATH | **4** | 4 / 1s interval | `kis-rate-limit:user` | 주문(hashkey+order-cash+rvsecncl)·사용자 현재가·차트·잔고 — **절대 안 막힘** |
| **SHARED 버킷** | INGESTION | **3** | 3 / 1s interval | `kis-rate-limit:shared` | watchlist Ingestion·`/oauth2/Approval` — 사용자 인접(준실시간) |
| **BG 버킷** | BACKFILL · INGESTION_LOW | **3** | 3 / 1s interval | `kis-rate-limit:bg` | 캔들 backfill·reconcile·지수/섹터 cron — 백그라운드(latency 허용) |
| 합 | — | **10** | — | — | **KIS 초당 10건 정확 — 미초과·증설 0** |

**라우팅 알고리즘** (capacity 1차 소비 + 상위 priority fallback — 굶음 방지):

1. 각 priority는 **자기 전용 버킷 1차 소비** (`tryConsume(1)`).
2. 자기 버킷 소진 시 **상위 priority 버킷으로 fallback 금지** (역방향만 허용): USER_PATH는 SHARED·BG로 fallback **가능** (주문은 절대 안 막힘이 최우선), INGESTION은 BG로 fallback **가능**, BACKFILL/INGESTION_LOW는 fallback **불가**(자기 버킷 소진 시 polling 대기 → timeout). 즉 **상위 priority일수록 더 많은 버킷에 접근** → 하위 priority burst가 상위를 굶기지 못함.
3. 모든 접근 가능 버킷 소진 → polling(50ms) sleep 후 재시도 → `timeoutMillis` 초과 시 `KisRateLimitTimeoutException`.

> **현 2-bucket 대비 진화**: 현 모델은 reserve를 USER_PATH 전용 1단으로만 보호. 본 3-tier는 **INGESTION(approval·watchlist)도 BG burst로부터 보호** + USER_PATH는 3개 버킷 모두 접근하여 최우선 보장. `userPathReserveTokens` 단일 ENV → priority별 capacity 3 ENV로 일반화.

**capacity 배분 근거 (자율 조정 — R2 부하 검증으로 재조정 가능)**:
- USER 4: 주문 1건 = hashkey + order-cash 2토큰 묶음 + 동시 현재가/잔고 여유 2.
- SHARED 3: approval(부팅 시 1회) + watchlist Ingestion polling 여유.
- BG 3: 캔들 incremental(종목당 ~1페이지 — 2026-06-04 fix 후) + 지수 cron 분산 후 충분.
- **rollback path**: `MAGICJAR_KIS_PRIORITY_BUCKETS_ENABLED=false` 시 단일 bucket(capacity 10) 회귀 — 현 2-bucket reserve로 되돌리지 않고 Phase 1 단일로 안전 회귀.

### 2-C. KIS 실제 한도 정합 토큰 모델

| 항목 | 결정 |
|------|------|
| **refill 알고리즘** | **`refillIntervally` LOCK** (commit 2837b2cf 전환 정식 승인). `refillGreedy` 금지 — idle 후 burst가 KIS sub-second 한도(추정) 위반. `refillIntervally(N, 1s)`는 1초 경계마다 N토큰 일괄 충전 → 한 초 안 N건 균등 발사 강제 |
| **capacity = refill** | 각 버킷 capacity = refill (idle 축적 burst 0). KIS 한도보다 **보수적** — KIS 10/s 정확히 맞추되 burst window 마진 0 |
| **sub-second 추가 보수화 (선택)** | R2에서 KIS EGW00201 잔존 시 `bucketRefillPeriodSeconds`를 1s 유지하되 caller-side **페이지 간 sleep**(이미 H1 fix 150ms)으로 sub-second 균등 분산. 토큰 모델을 100ms 분해로 쪼개는 것은 over-engineering — caller 분산이 본질 |
| **한도 출처 LOCK** | external-api-policy.md §2-1 "초당 10건" 단일 권위. sub-second 분해는 "추정"으로 명시 — 검증되면 본 ADR amend |

### 2-D. 스케줄러 호출 분산

| caller | 현 패턴 | 분산 결정 |
|--------|---------|-----------|
| `MarketIndexScheduler` (batch, 1분 cron) | 지수·섹터 26 req burst (ADR 0100 §2-3) | Job 내부 종목/지수 루프에 **inter-call jitter**(예: 종목 간 50~100ms gap) 추가 → 1분 안 균등 분산. BG 버킷 3/s로도 26 req가 ~9초에 분산되어 충분 |
| `LiveDailyCandleScheduler` (api, 5분 cron) | 종목당 incremental ~1페이지(2026-06-04 fix) | **이미 분산됨** — incremental 경계로 burst 제거 완료. 추가 변경 불요(유지) |
| 캔들 페이지네이션 | H1 fix 페이지 간 150ms sleep | **유지** (refillIntervally와 정합) |

**원칙**: 토큰 버킷은 순간 차단만 할 뿐, **순간 버스트를 시간 분산하는 것은 caller 책임**. 버킷 분리 + caller jitter 양쪽이 정합되어야 EGW00201 0 수렴.

### 2-E. 관측 (운영 단계 §9-5 정합)

| metric | 구현 | 임계 |
|--------|------|------|
| priority별 버킷 사용률 | `kis_ratelimit_bucket_consumed_total{bucket=user\|shared\|bg}` Micrometer counter | bucket별 소진율 추이 |
| priority별 timeout | `kis_ratelimit_acquire_timeout_total{priority}` counter | 어느 priority가 굶는지 식별 |
| EGW00201 발생 시 소진 priority 추적 | `onTpsExceeded()` 시 **마지막 소진 버킷 라벨** detailJson 첨부 | 어느 버킷이 EGW00201 직전 소진됐는지 |
| EGW00201 rate (rolling window) | 기존 SystemEvent 5분 aggregator + rolling rate (보고서 §9-2 권고 — 단조 누적 카운터 오인 방지) | 5분 윈도 rate |

---

## 3. 대안 비교

| 대안 | 장점 | 단점 | 판정 |
|------|------|------|------|
| **A. 현 2-bucket reserve 유지 (Phase 1.5)** | 구현 0 (이미 머지) · USER_PATH 보호 검증됨 | INGESTION(approval·watchlist)이 BG burst로부터 무방비 · priority가 2단뿐 · orphan ADR 미해결 | **PARTIAL — 정식화는 채택(2-A), 그러나 INGESTION 보호 미달로 3-tier 확장 필요** |
| **B. priority별 별도 버킷 3-tier (capacity 4/3/3) + 역방향 fallback** | priority 전 계층 격리 · 상위 priority가 하위 burst에 안 굶음 · USER_PATH 3버킷 접근 최우선 보장 · KIS 합 10 정확 | 버킷 3개 → Redis key 3 · 어댑터 라우팅 복잡도 ↑ · capacity 배분 부하검증 필요 | **★ ACCEPT ★** |
| **C. 동적 capacity 조정 (Redis pub/sub 런타임 재배분)** | 트래픽 패턴 적응 | MVP 과대설계 · 운영 복잡도 · 검증 부담 | **REJECT (post-MVP)** |
| **D. KIS 한도를 10→8로 보수적 하향** | EGW00201 마진 확보 | 처리량 20% 손실 · 근본 원인(burst·priority)이 아닌 회피책 · 사용자 path 처리량 저하 | **REJECT — caller 분산 + interval refill이 정공법** |
| **E. capacity 증설 (10→15) ENV burst** | 일시 처리량 ↑ | **KIS 차단 직격 risk** · 한도 초과 = 계정 차단 catastrophic | **REJECT (rollback path로만 존재 — 사용자 알림 의무)** |

**트레이드오프 요약**: B는 Redis key 3개·라우팅 복잡도를 감수하는 대신 **priority 전 계층 격리 + 합 10 정확**을 얻는다. 현 2-bucket(A)은 USER_PATH만 보호하므로, INGESTION(approval은 부팅·재연결 시 자본 path 직결)이 BG cron burst에 막히는 잔존 risk가 있다. B가 이 갭을 닫는다. C·D·E는 근본 원인(burst·priority 격리 부재)이 아닌 회피책이라 거부.

---

## 4. 자원 audit (ADR 0102 — 5 자원 main 점유 검증)

| 자원 | 현 main 점유 | 본 ADR 신설 | 정합 |
|------|-------------|-------------|------|
| **Flyway** | V71까지 점유 (ADR 0114 audit) | **0 신설** — rate limit은 Redis bucket only, DB 스키마 무관 | GREEN |
| **Kafka** | 기존 토픽 | **0 신설** | GREEN |
| **Redis** | `kis-rate-limit:global` + `:user-reserve` (현 2-bucket) | **3 key**: `kis-rate-limit:user`·`:shared`·`:bg` (기존 `:global`·`:user-reserve` 대체 — rollback 시 `:global` 단일 회귀). Bucket4j TTL 10s 자동 만료 — leak 0 | GREEN (key naming만, infra 신설 0) |
| **STOMP** | 기존 | **0 신설** | GREEN |
| **REST** | 기존 | **0 신설** (관측 metric은 Micrometer `/actuator/prometheus` 기존 endpoint reuse) | GREEN |
| **ENV** | `MAGICJAR_KIS_USER_PATH_RESERVE`·`MAGICJAR_KIS_RATE_LIMIT_BURST` | **신규 add-only**: `MAGICJAR_KIS_PRIORITY_BUCKETS_ENABLED`(default true) + `MAGICJAR_KIS_BUCKET_USER`(4)·`_SHARED`(3)·`_BG`(3). 기존 `USER_PATH_RESERVE`는 deprecated 마커(rollback 회귀 시 단일 bucket path) | GREEN (add-only) |

**ENV 합 검증**: USER(4) + SHARED(3) + BG(3) = 10 = `bucketCapacity`. R2 부팅 시 합 ≠ capacity면 WARN 로그 + 단일 bucket fallback(fail-safe).

---

## 5. SLO 영향 · rollback · 관측 (운영 단계 ADR 양식 — 헌법 §9-5)

### 5-1. SLO 영향
- **S-4 (KIS rate limit)** — EGW00201 발생률 현 ~0.2/분 → **0 수렴 목표** (priority 격리 + interval refill + caller 분산).
- **S-1 (시그널→주문 지연 P95 < 5초)** — **개선**: USER 버킷 4토큰 + 3버킷 fallback으로 주문 acquire 대기 단축. BG burst가 주문을 굶기지 못함.
- **S-2 (KIS WS 연결률 > 99%)** — approval(INGESTION → SHARED 버킷)이 BG burst로부터 보호되어 WS 재연결 acquire 지연 감소(간접 개선).

### 5-2. rollback plan
1. **즉시 toggle**: `MAGICJAR_KIS_PRIORITY_BUCKETS_ENABLED=false` → 단일 bucket(capacity 10) 회귀. 재배포 불요(ENV 재주입 + 컨테이너 재시작).
2. **코드 revert**: 3-tier 라우팅 PR revert 시 현 2-bucket reserve(commit 2837b2cf)로 복귀 — Redis key `:global`·`:user-reserve` 자동 재발행(TTL 10s).
3. **DB/Flyway/Kafka 변경 0** → rollback 시 마이그레이션 down 불요.
4. **capacity 오배분 발견 시**: ENV 3건만 재조정(코드 변경 0) — `MAGICJAR_KIS_BUCKET_USER/SHARED/BG` 합 = 10 유지.

### 5-3. 관측 매트릭스
- Prometheus: `kis_ratelimit_bucket_consumed_total{bucket}` · `kis_ratelimit_acquire_timeout_total{priority}` · `kis_ratelimit_tps_exceeded_total{last_bucket}`
- Grafana panel: priority별 버킷 소진율 스택 + EGW00201 rolling rate(5분 윈도)
- Slack alert: EGW00201 5분 윈도 rate > 임계 시 P2 (ADR 0095 routing) + 어느 priority 버킷이 소진됐는지 detailJson
- **Visual 회귀**: frontend 영향 0 (rate limit은 backend 인프라) → qa Visual 시나리오 불요

---

## 6. 라운드 분할 (개발 룰 §3 — 한 PR 한 라운드)

### R1 — orphan 정식화 + 버킷 분리 구현 (1순위)
| 의제 | 범위 | 영역 |
|------|------|------|
| `ADR 0112 의제` → `ADR 0116` 주석 정정 | 17개 파일 (kt·yml·md) add-only replace_all | backend-core |
| 3-tier 버킷 라우팅 — `RedisKisRateLimiterAdapter` (+batch·consumer 3 어댑터) | 2-bucket → 3-bucket(user/shared/bg) + 역방향 fallback 라우팅 (`tryConsumeRouted` 확장) | backend-core |
| `KisRateLimitProperties` 확장 | `priorityBucketsEnabled` + `bucketUser`·`bucketShared`·`bucketBg` + 합 검증 fail-safe (api·batch·consumer 3 Properties) | backend-core |
| application.yml 3건 + ENV 4 add-only | `MAGICJAR_KIS_PRIORITY_BUCKETS_ENABLED`·`_BUCKET_USER`·`_SHARED`·`_BG` | backend-core |
| ADR 0037 amend 마커 + external-api-policy.md §2-2 정정 + master §10 정합 | SoT drift 해소 | architect (본 PR) |
| **테스트 0건 OK** (R3 일괄 — 개발 룰 §4) | — | — |

**R1 1순위 지침 (backend-core)**: ① 먼저 17파일 `ADR 0112 의제` → `ADR 0116 (Phase 2)` 일괄 정정(로직 0 변경, replace_all). ② `KisCallPriority` → 버킷 매핑을 `when` exhaustive로: USER_PATH→[user,shared,bg], INGESTION→[shared,bg], BACKFILL/INGESTION_LOW→[bg]. 상위 priority가 더 많은 버킷 접근(역방향 fallback). ③ 현 `tryConsumeRouted`의 reserve 2단 로직을 버킷 리스트 순회로 일반화 — `reserveEnabled` 플래그는 `priorityBucketsEnabled`로 대체. ④ `priorityBucketsEnabled=false` 시 단일 `:global` 버킷(capacity 10) 회귀(현 2-bucket이 아닌 Phase 1 단일 — 안전 최소). ⑤ 3 모듈(api·batch·consumer) 어댑터 동일 패턴 — 공유 로직 중복이나 모듈 분리(ADR 0088 패턴) 하지 말 것(현 구조 보존, 보수적). ⑥ Repository에 body fun 추가 금지(헌법 §6).

### R2 — refill 모델 검증 + 스케줄러 분산 (R1 머지 후)
| 의제 | 범위 | 영역 |
|------|------|------|
| `refillIntervally` LOCK 검증 + 주석 근거 명시 | 3 어댑터 — refillGreedy 잔재 0 확인 | backend-core |
| `MarketIndexScheduler` inter-call jitter | Job 내부 지수/섹터 루프 종목 간 50~100ms gap (26 req 1분 분산) | market-data |
| 관측 metric wiring | Micrometer counter 3종 (bucket consumed·timeout·tps_exceeded last_bucket) | backend-core |
| capacity 부하 검증 (사용자 측 LIVE) | 장중 3 cycle EGW00201 0 확인 + 버킷별 소진율 evidence | qa + 사용자 |

### R3 — 부하 검증 + 시나리오 테스트 일괄 (R2 머지 후)
| 의제 | 범위 | 영역 |
|------|------|------|
| 단위 테스트 — 버킷 분리 라우팅 (priority별 버킷 소진 + fallback + 단일 회귀) | api·batch·consumer 3 어댑터 회귀 | backend-core + qa |
| 통합 — 주문 path(USER_PATH)가 BG burst 중에도 acquire 성공 시나리오 | mock burst injection | qa |
| LIVE 부하 evidence 매트릭스 (헌법 검증 의무) | docker logs EGW00201 rate + 버킷 소진율 + 주문 지연 P95 | qa + 사용자 |

---

## 7. 결과

- **priority 전 계층 격리** — USER_PATH(주문) 절대 안 막힘 + INGESTION(approval·watchlist)도 BG burst로부터 보호
- **KIS 한도 정확 정합** — 합 10 + interval refill(burst 억제) + caller 분산
- **SoT drift 해소** — orphan decision 정식화 + 잘못된 ADR 0112 참조 17파일 정정 + ADR 0037 amend
- **관측 가능** — EGW00201 발생 시 어느 priority가 소진했는지 추적
- **자본 path 안전** — 주문·approval이 백그라운드 cron burst에 굶지 않음

## 8. 참고

- ADR 0037 — KIS Rate Limit 강제 (본 ADR이 단일 bucket → 3-tier supersede)
- 보고서 `2026-05-22-kis-tps-exceeded-root-cause.md` §4-5(KIS 알고리즘 가설)·§9(H1·H4 fix)
- 보고서 `2026-06-04-api-tps-exceeded-investigation.md` §5(incremental fix)·§9(rolling window 권고)
- `docs/architecture/external-api-policy.md` §2 (KIS 한도 SoT)
- commit `2837b2cf` — 2-bucket reserve orphan decision (본 ADR이 정식화)
- KIS 공식 — REST TPS "초당 10건" (raw 출처: https://github.com/koreainvestment/open-trading-api · apiportal.koreainvestment.com 유량 공지 2026.04.20)

## 9. 변경 이력

| 일자 | 변경 | 작성 |
|------|------|------|
| 2026-06-08 | ADR 0116 신설 — Phase 2 priority별 별도 버킷(3-tier) + KIS 실제 한도 정합 + orphan(commit 2837b2cf 2-bucket reserve) 정식화 + 잘못된 ADR 0112 참조 17파일 정정 의제 + ADR 0037 amend(단일 bucket supersede) + 스케줄러 분산 + 관측. 별도 브랜치 전용 Proposed(canary 전 급반영 금지). 자원 audit(Redis 3 key·ENV 4 add-only·Flyway/Kafka/STOMP/REST 0). 운영 ADR 3슬롯(SLO 영향·rollback·관측). R1(버킷 분리+orphan 정정)/R2(refill 검증+스케줄러 분산+metric)/R3(부하 검증) 분할. 운영 코드 0 touch(md only). 자본 영향 0 + KIS 호출 0 + AI 호출 0 + mock 합성 0. (architect, 사용자 결재 대기) | architect (opus 4.8 1M) |
