# Phase E-P1 — P0 hotfix: REST 컨트롤러 + 각 앱 자체 JpaAdapter 영구 구현

> **Sub-skill:** `superpowers:subagent-driven-development`. backend-core 에이전트(sonnet)에 위임. 1회 opus 에스컬레이션 가능(ADR 0014).

**Goal:** Phase D 종료 시 OPEN issue P0 2건 해소.
- (a) **REST 컨트롤러 미구현** — `/api/backtest`, `/api/journal/{id}/snapshots`, `/api/instruments`, 기타 smoke 시나리오용 엔드포인트
- (b) **batch/consumer 자체 JpaAdapter** — 영속성 정책 B 확정(사용자 선택, 2026-04-27): 각 앱이 자체 entity·repository 정의, MSA 정석

**Source:** Phase D 종료 main(`2470af8` 또는 그 이후), spec §2 §6 §15-2, ADR 0021·0025, P4 OPEN issues 카탈로그(`_workspace/_harness-state.json`).

**Branch:** `agent/backend-core/e1-p0-hotfix`
**Author:** `bot+backend-core@magicjar.local`
**Model:** sonnet (ADR 0014)

---

## 0. Pre-flight

- [ ] `git checkout main && git pull` (main = `2470af8` 또는 그 이후)
- [ ] `./gradlew clean build` 통과 (4 모듈)
- [ ] `_workspace/_harness-state.json`의 openIssues P0 2건 정확히 인지

---

## 1. REST 컨트롤러 (api 모듈)

### 1-A. `/api/backtest` — POST 트리거 + GET 상태 조회

`api/src/main/kotlin/com/mj/api/controller/BacktestController.kt`:
- `POST /api/backtest` body `{strategyId, symbol, from, to}` → `BacktestService` 호출 → `runId` 응답
- `GET /api/backtest/{runId}` → 상태 + 결과 (RUNNING/COMPLETED/FAILED)

`api/src/main/kotlin/com/mj/api/application/BacktestService.kt`:
- batch-app HTTP 호출 또는 Kafka publish로 batch 잡 트리거 (어느 쪽인지 backend-core 자율 결정 — 권장: batch-app `/jobs/backtest` HTTP)
- 결과는 `journal.entry.v1` 토픽 consume 또는 직접 DB 조회

### 1-B. `/api/journal` — 매매일지 + 스크린샷 첨부

`api/src/main/kotlin/com/mj/api/controller/JournalController.kt`:
- `GET /api/journal` 페이징 (월·전략별 필터)
- `GET /api/journal/{tradeId}` 상세
- `POST /api/journal/{tradeId}/snapshots` (multipart) — 차트 캡처 PNG 업로드 → MinIO 저장 → `ChartSnapshot` 도메인 객체 영속 + Kafka `screenshot.captured.v1` publish

### 1-C. `/api/instruments` — 종목 카탈로그

`api/src/main/kotlin/com/mj/api/controller/InstrumentController.kt`:
- `GET /api/instruments` 종목 목록 페이징
- `GET /api/instruments/{code}` 상세 + 최근 캔들·뉴스 요약
- `GET /api/instruments/search?q=` 검색

### 1-D. `/api/symbols/search` — 차트 심볼 자동완성

frontend에서 사용. spec §11-2 "심볼 검색은 차트 외부".

`api/src/main/kotlin/com/mj/api/controller/SymbolSearchController.kt`:
- `GET /api/symbols/search?q=` instrument 테이블 like 검색

### 1-E. WebMvc · Security 최소 구성

`api/src/main/kotlin/com/mj/api/config/`:
- `WebSecurityConfig.kt` — Phase E1은 모든 `/api/**` `permitAll()` (실제 인증은 Phase F+)
- `WebMvcConfig.kt` — CORS allow `http://localhost:3000`(frontend dev), `http://frontend:80`(compose)
- `JacksonConfig.kt` — Java time module, ISO-8601, snake_case는 NO (camelCase Jackson default 유지, spec §4)

### 1-F. 컨트롤러 테스트 (api/src/test)

`@WebMvcTest` 또는 `@SpringBootTest(webEnvironment = MOCK)` 슬라이스로:
- 각 컨트롤러 happy-path 1건씩
- 4xx/5xx 1건씩 (선택)
- 최소 8건 PASS

---

## 2. 각 앱 자체 JpaAdapter (영속성 정책 B)

### 2-A. api/infrastructure/persistence (가장 큰 영역)

이미 P2에서 일부 이전됨(`market-persistence` → api/infra). 보강·완결:
- `CandleJpa` + `CandleJpaRepository` + `CandleRepositoryAdapter`(domain port `CandleRepository` 구현)
- `InstrumentJpa` + `InstrumentJpaRepository` + `InstrumentCatalogAdapter`(domain port `InstrumentCatalog` 구현)
- `JournalJpa` + `JournalJpaRepository` + `TradeJournalRepositoryAdapter`(domain port `TradeJournalRepository` 구현)
- `NewsJpa` + `NewsJpaRepository` + `NewsRepositoryAdapter`(domain port `NewsRepository` 구현)
- `ChartSnapshotJpa` + `ChartSnapshotJpaRepository` + `ChartSnapshotAdapter`(신규 — `ChartSnapshot` 도메인)
- Flyway 마이그레이션 `V4__chart_snapshot.sql` 추가 (snapshot 테이블)

### 2-B. batch/infrastructure/persistence (자체 entity, 작게)

batch가 필요로 하는 영역:
- `CandleJpa`(읽기 — 백테스트 대상 캔들), `InstrumentJpa`(읽기) — api와 동일 entity 정의 복제 (코드 중복 OK, 영속성 정책 B)
- `JournalJpa`(쓰기 — 백테스트 결과 매매일지), `BacktestRunJpa`(신규 — batch 잡 메타데이터: runId, status, startedAt, finishedAt, summary)
- Flyway는 api 단독 owner — batch는 `flyway.enabled=false` 유지

### 2-C. consumer/infrastructure/persistence (실시간 처리 영역)

consumer가 필요로 하는 영역:
- `CandleJpa`(쓰기 — TickAggregator → 1m 캔들 영속)
- `JournalJpa`(쓰기 — JournalRecorderListener → order.executed → journal entry)
- `NewsJpa`(쓰기 — NewsAnalyzerListener → news.analyzed)
- `OrderJpa` + `PositionJpa`(신규 — 주문·포지션)
- Flyway `flyway.enabled=false` 유지

### 2-D. 같은 테이블 entity 중복 처리 가이드

세 앱이 같은 테이블에 entity 정의를 가지지만:
- 컬럼 정의는 일치해야 함 (Flyway 마이그레이션이 단일 진실 소스)
- 각 앱이 **읽기/쓰기 권한 범위**만 자체 entity에 반영 (예: api는 모든 컬럼, batch는 일부 컬럼만)
- 향후 코드 중복 부담 커지면 ADR 0028(미래)로 shared-infra 도입 재검토

### 2-E. Adapter 테스트

각 앱 `@DataJpaTest` 또는 `Testcontainers PostgreSQL`로:
- 핵심 adapter 1건씩 (CandleRepositoryAdapter, JournalRepositoryAdapter 등)
- save → find roundtrip
- 최소 6건 PASS (api 4 + batch 1 + consumer 1)

---

## 3. smoke.sh 시나리오 1·4 SKIP 해소

P4에서 `/api/backtest` + `/api/journal/{id}/snapshots` 미구현으로 SKIP된 시나리오를 PASS로 전환:

- [ ] `./scripts/smoke.sh` S1·S4 모두 PASS
- [ ] (S5는 호스트 디스크 이슈 — Phase F+에서)

---

## 4. 종료 게이트

- [ ] `./gradlew clean build` SUCCESSFUL (4 모듈)
- [ ] `./gradlew :api:test :batch:test :consumer:test` 모두 PASS — 신규 테스트 14+ 건 추가
- [ ] `:api:bootRun` (infra mode) `/actuator/health` UP + 신규 컨트롤러 `/api/backtest`·`/api/instruments` curl 200
- [ ] `docker compose up -d && scripts/smoke.sh` → S1·S2·S3·S4 4건 PASS, S5 SKIP 유지(disk 이슈)
- [ ] domain port 신규 추가(`ChartSnapshotRepository`) 도메인 모듈 빌드 + 테스트 통과
- [ ] PR `agent/backend-core/e1-p0-hotfix` → main 발행

---

## 5. 통보 경계 (자율 vs 사용자)

다음만 사용자 통보, 그 외 자율:
1. spec §6 매핑표 벗어나는 영역 결정 (예: BacktestService 위치를 api가 아닌 batch에 두는 게 자연스러운 경우)
2. 신규 도메인 port 추가 시 (예: `BacktestRepository`, `OrderRepository` — port 신설은 도메인 정의 변경)
3. 외부 자원(신규 DB 테이블 V4·V5 등은 자율, 단 .env.example 변경은 통보)

---

## 6. 후속 단계 (Phase E-P2~)

- E-P2: KIS 모의 키 연동 + 1종목 1전략 e2e 검증 (market-data 에이전트)
- E-P3: CI/CD GitHub Actions matrix build·test·docker push to ghcr.io (architect)
- E-P4: k8s/ECS manifest + 부하 테스트 (architect)
- E-P5: 실서비스 진입 검토 + ADR 0027 트리거 모니터링
