# PR-11 T-IM1 충돌 행렬 (병렬 디스패치 결정 근거)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `_workspace/specs/pr-11-im1-design.md` v1 + `_workspace/specs/pr-11-dispatch-spec.md` v1 + ADR 0017·0039·0037·0050
- 사용자 메모리 정합:
  - `feedback_orchestrator_active_parallel.md` — **architect "직렬 권장" 답습 금지**. add-only 충돌은 진짜 병렬 가능
  - `feedback_3tier_hierarchy.md` — 3단 hierarchy: 오케스트라(지휘) → architect(설계) → 실무 sub-agents (병렬 실행)
  - `feedback_autonomy.md` — Claude 자율 실행
  - `feedback_kis_first_policy.md` — KIS endpoint·메시지 형식은 open-trading-api 출처 의무
  - `feedback_no_mock_data.md` — 사용자 흐름에서 mock 합성 폐기 (정적 시드는 시스템 전제 — `feedback_no_mock_data.md` 정합 — 사용자 path mock 0)

---

## §1. 결론 (한 줄)

> **PR-11a (market-data) + PR-11b (backend-core) 완전 병렬 권장 — 양쪽 영역이 batch+consumer+domain(add-only)+api(Flyway V4 단독) vs api+domain(modify-own)+frontend로 완전 분리. modify-shared 후보 1건(CHANGELOG.md)은 ADR 0050 슬롯 채번룰 세 번째 적용 + HTML 주석 anchor (PR-10 효과 입증)로 충돌 0 보장. PR-9 add-add 충돌 결함은 본 PR-11 dispatch §5-1 영역 분배 LOCK 강화로 0% 사전 검증.**

---

## §2. 인터페이스 동결 시점

본 spec 4종(`pr-11-im1-design.md` + `pr-11-dispatch-spec.md` + 본 문서 + `_workspace/backlog.md` 갱신)이 PR로 머지되는 순간 = 인터페이스 동결.

동결되는 계약:
- `pr-11-im1-design.md` §3 (DB 스키마 LOCK — V4 신설)
- `pr-11-im1-design.md` §4 (REST API 시그니처 LOCK)
- `pr-11-im1-design.md` §5 (Cron sync LOCK + 정적 시드)
- `pr-11-im1-design.md` §6 (Redis 검색 인덱스 LOCK)
- `pr-11-im1-design.md` §7 (frontend 자동완성 통합 LOCK)
- `pr-11-im1-design.md` §8 (상장폐지 보존 LOCK)
- `pr-11-dispatch-spec.md` §2 (룰 2 단일 책임 사유)
- `pr-11-dispatch-spec.md` §5-1 (영역 분배 LOCK ★)
- `pr-11-dispatch-spec.md` §5-2~§5-7 (domain port·REST·Kafka·Redis·Flyway·payload 시그니처)
- `pr-11-dispatch-spec.md` §6 (CHANGELOG 슬롯 row 위치·형식 + anchor)
- ADR 0017·0039·0037·0050 (모두 본 PR 정합)

**인터페이스 동결 즉시** market-data·backend-core 양쪽이 spec만 보고 동시에 자기 worktree에서 작업 시작 가능.

---

## §3. 파일 단위 충돌 행렬

### §3-1. PR-11a (market-data) 변경 파일 vs PR-11b (backend-core) 변경 파일

| 파일/디렉토리 | PR-11a | PR-11b | 충돌 유형 | 결정 |
|---|---|---|---|---|
| `domain/.../instrument/Instrument.kt` | **변경 0** | **변경 0** | (변경 0) | **격리** (architect 결정 — domain simplicity) |
| `domain/.../instrument/InstrumentAlias.kt` | **신규** | 무관 | add-only | **격리** |
| `domain/.../instrument/InstrumentSearchResult.kt` | **신규** | 무관 | add-only | **격리** |
| `domain/.../instrument/AliasSource.kt` (또는 InstrumentAlias.kt 안) | **신규** | 무관 | add-only | **격리** |
| `domain/.../instrument/port/KisInstrumentMasterPort.kt` | **신규** | 무관 | add-only | **격리** |
| `domain/.../instrument/port/InstrumentSearchIndex.kt` | **신규** | 무관 | add-only | **격리** |
| `domain/.../instrument/port/InstrumentRepository.kt` | 무관 | **변경** (4 메서드 추가) | modify | **격리** (PR-11b 단독 — backend-core 본인 영역, PR-7 발행 파일 modify-own) |
| `api/src/main/resources/db/migration/V4__instrument_master_search.sql` | **신규** | 무관 | add-only | **격리** |
| `api/.../persistence/InstrumentJpaEntity.kt` | 무관 | **변경** (name_norm·theme 컬럼 추가) | modify | **격리** (PR-7 발행 파일 — backend-core 본인 영역) |
| `api/.../persistence/InstrumentJpaRepository.kt` | 무관 | **변경** (메서드 추가) | modify | **격리** (PR-7 발행 — backend-core 본인 영역) |
| `api/.../persistence/InstrumentRepositoryAdapter.kt` | 무관 | **변경** (메서드 implement 추가) | modify | **격리** (backend-core 본인 영역) |
| `api/.../persistence/InstrumentAliasJpaEntity.kt` | 무관 | **신규** | add-only | **격리** |
| `api/.../persistence/InstrumentAliasJpaRepository.kt` | 무관 | **신규** | add-only | **격리** |
| `api/.../controller/InstrumentController.kt` | 무관 | **신규** | add-only | **격리** |
| `api/.../controller/InstrumentDto.kt` (또는 동일 패키지 inline) | 무관 | **신규** | add-only | **격리** |
| `api/.../service/InstrumentSearchService.kt` | 무관 | **신규** | add-only | **격리** |
| `api/.../service/SymbolMetaService.kt` | 무관 | **변경** (instrument 1순위 read 추가) | modify | **격리** (PR-10b 발행 — backend-core 본인 영역, PR-11a 미진입) |
| `api/.../infrastructure/redis/InstrumentSearchIndexAdapter.kt` | 무관 | **신규** | add-only | **격리** |
| `consumer/.../infrastructure/kis/KisInstrumentMasterAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../infrastructure/kis/KisInstrumentRaw.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../kafka/InstrumentUpdatedKafkaPublisher.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../config/KafkaTopicConfig.kt` | **변경** (instrument.updated.v1 topic 추가) | 무관 | modify | **격리** (PR-9b·PR-10b 만든 파일 — market-data 본인 영역 보강) |
| `batch/.../scheduler/InstrumentMasterSyncScheduler.kt` | **신규** | 무관 | add-only | **격리** |
| `batch/.../job/instrument/InstrumentSyncJob.kt` | **신규** | 무관 | add-only | **격리** |
| `batch/.../job/instrument/InstrumentSyncResult.kt` | **신규** | 무관 | add-only | **격리** |
| `batch/.../bootstrap/InstrumentSeedRunner.kt` | **신규** | 무관 | add-only | **격리** |
| `batch/src/main/resources/instrument-seed.json` | **신규** | 무관 | add-only | **격리** |
| `batch/src/main/resources/application.yml` | (보강 권장 — 시드 path 또는 sync at boot 옵션) | 무관 | modify | **격리** (market-data 단독 — batch 모듈 application.yml 단독 변경) |
| `batch/src/test/kotlin/.../InstrumentSyncJobTest.kt` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/components/InstrumentSearchInput.tsx` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/components/__tests__/InstrumentSearchInput.test.tsx` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/api/instrument.ts` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/api/hooks/useInstrumentSearch.ts` | 무관 | **신규** | add-only | **격리** |
| `frontend/src/pages/Watchlist/WatchlistAddForm.tsx` | 무관 | **변경** (자동완성 전환) | modify | **격리** (PR-7b 발행 — backend-core 본인 영역, frontend sub-agent 미디스패치) |
| `frontend/src/api/watchlist.ts` (PR-7 발행) | 무관 | (변경 0 권고 — 시그니처 그대로) | (변경 0) | **격리** |
| `frontend/package.json` | 무관 | (변경 0 — 추가 의존 없음) | (변경 0) | **격리** |
| `api/src/test/kotlin/.../InstrumentSearchServiceTest.kt` | 무관 | **신규** | add-only | **격리** |
| `api/src/test/kotlin/.../InstrumentControllerTest.kt` | 무관 | **신규** | add-only | **격리** |
| `consumer/src/main/resources/application.yml` | (보강 0 권고) | 무관 | (변경 0) | **격리** |
| `api/src/main/resources/application.yml` | 무관 | (보강 0 권고) | (변경 0) | **격리** |
| `docker/**` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `scripts/**` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `.github/workflows/*` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `.env.example` | (보강 권장 — `MAGICJAR_INSTRUMENT_SYNC_AT_BOOT` 변수 추가) | (변경 0) | modify | **격리** (market-data 단독) |
| `CHANGELOG.md` | **변경** (PR-11a slot row in-place edit — 1줄) | **변경** (PR-11b slot row in-place edit — 1줄) | **modify-shared** | **ADR 0050 슬롯 채번룰 세 번째 적용 + HTML 주석 anchor (PR-10 효과 입증) — 충돌 0 보장** |
| `docs/architecture/*` | **변경 0** | **변경 0** | (변경 0) | **격리** (architect 단독) |
| `_workspace/arch/adr/*` | **변경 0** | **변경 0** | (변경 0) | **격리** (architect 단독) |
| `_workspace/specs/pr-11-*.md` | **변경 0** | **변경 0** | (변경 0) | **격리** (architect 단독) |
| `_workspace/backlog.md` | **변경 0** | **변경 0** | (변경 0) | **격리** (architect 단독 — 본 PR-11 spec에 갱신) |
| `_workspace/tasks/T-*-pr-11-im1-market-data.md` | **신규** (자기 task) | 무관 | add-only | **격리** |
| `_workspace/tasks/T-*-pr-11-im1-backend-core.md` | 무관 | **신규** (자기 task) | add-only | **격리** |
| `README.md` (루트) | **변경 0** | **변경 0** | (변경 0) | **격리** |

### §3-2. modify-shared 후보 분석

총 1건 식별:

#### (1) `CHANGELOG.md`

- 변경 내용 — 양쪽 sub-agent가 본인 슬롯 row 1줄 in-place edit (ADR 0050 슬롯 채번룰)
- **충돌 0 보장**: 
  - ADR 0050 §3-2 — git diff가 다른 row 변경으로 인식 → 양쪽 머지 시 conflict marker 발생 없음
  - 본 PR-11에서 **HTML 주석 anchor line** 4건 (architect spec + market-data + backend-core + end) 적용 — PR-10 효과 입증된 패턴
  - hunk-based merge가 anchor + row를 1 hunk로 묶음 → 더 명확한 분리
- **본 PR-11이 ADR 0050 세 번째 적용 사이클** — PR-10 효과 입증 후 정합 적용 (일탈 0)

#### 그 외 (modify-shared 0건 추가)

- application.yml — batch 모듈 vs api·consumer 모듈 다른 파일 (PR-11a는 batch/src/main/resources, PR-11b는 api/src/main/resources 단독 변경 가능 — 실제 보강 권고 0이라 변경 0 가능)
- domain — 본 PR-11a는 add-only port·data class만, PR-11b는 InstrumentRepository port modify-own. 다른 파일이라 충돌 0
- api 모듈 PR-7 발행 파일(InstrumentJpaEntity·InstrumentJpaRepository·InstrumentRepositoryAdapter) — PR-11b backend-core 본인 영역. PR-11a 미진입
- api 모듈 PR-10b 발행 파일(SymbolMetaService) — PR-11b backend-core 본인 영역. PR-11a 미진입
- frontend 모듈 PR-7b 발행 파일(WatchlistAddForm) — PR-11b backend-core 본인 영역 (룰 2 단일 책임 사유로 frontend sub-agent 미디스패치)
- consumer 모듈 PR-9b·PR-10b 발행 파일(KafkaTopicConfig) — PR-11a market-data 본인 영역 보강

### §3-3. 격리 검증 — 양 sub-agent 사전 add-add 가능 파일 비교 ★

**PR-9 결함 정정 — 양 sub-agent의 add 파일 목록 사전 비교**:

| PR-11a (market-data) add 파일 | PR-11b (backend-core) add 파일 | 동명 add 가능성 |
|---|---|---|
| `domain/.../instrument/InstrumentAlias.kt` 외 4건 | (domain add 0건) | **0%** |
| `consumer/.../infrastructure/kis/KisInstrumentMasterAdapter.kt` 외 1건 | (consumer add 0건) | **0%** |
| `consumer/.../kafka/InstrumentUpdatedKafkaPublisher.kt` | (consumer add 0건) | **0%** |
| `batch/.../scheduler/InstrumentMasterSyncScheduler.kt` 외 4건 | (batch add 0건) | **0%** |
| `api/src/main/resources/db/migration/V4__instrument_master_search.sql` | (api migration add 0건) | **0%** |
| `batch/src/main/resources/instrument-seed.json` | (frontend asset add 0건) | **0%** (다른 모듈) |
| (api add 0건 — Controller/Service는 backend-core) | `api/.../controller/InstrumentController.kt` 외 5건 | **0%** |
| (frontend add 0건) | `frontend/src/components/InstrumentSearchInput.tsx` 외 3건 | **0%** |
| `_workspace/tasks/T-*-pr-11-im1-market-data.md` | `_workspace/tasks/T-*-pr-11-im1-backend-core.md` | **0%** (다른 파일명) |

**결론**: 양 sub-agent의 add-add 가능성 **0% 사전 검증 완료**. PR-9 결함(consumer 모듈에 양쪽 add 4건 충돌) + PR-10 결함 회피 정합 본 PR-11에서 이어짐.

### §3-4. modify-shared 검증 — 같은 파일을 양 sub-agent가 수정하는지

**PR-11a market-data가 modify하는 파일**:
- `consumer/.../config/KafkaTopicConfig.kt` (PR-9b·PR-10b 만든 — market-data 본인 영역 보강)
- `batch/src/main/resources/application.yml` (보강 권장 — market-data 단독)
- `.env.example` (보강 권장 — `MAGICJAR_INSTRUMENT_SYNC_AT_BOOT` 등)
- `CHANGELOG.md`

**PR-11b backend-core가 modify하는 파일**:
- `domain/.../instrument/port/InstrumentRepository.kt` (PR-7 발행 — backend-core 본인 영역)
- `api/.../persistence/InstrumentJpaEntity.kt` (PR-7 발행 — backend-core 본인 영역)
- `api/.../persistence/InstrumentJpaRepository.kt` (PR-7 발행 — backend-core 본인 영역)
- `api/.../persistence/InstrumentRepositoryAdapter.kt` (backend-core 본인 영역)
- `api/.../service/SymbolMetaService.kt` (PR-10b 발행 — backend-core 본인 영역)
- `frontend/src/pages/Watchlist/WatchlistAddForm.tsx` (PR-7b 발행 — 룰 2 단일 책임 사유)
- `CHANGELOG.md`

**동시 modify 파일**: `CHANGELOG.md` 1건 — ADR 0050 in-place edit + anchor 정합으로 충돌 0 보장.

**결론**: 양 sub-agent는 modify-shared 1건만 — ADR 0050으로 충돌 0 보장.

---

## §4. pre-push hook 종속성 분석

| 이유 | 분석 |
|---|---|
| `./gradlew check` (pre-push 자동) | 양쪽 PR 모두 main 위에서 GREEN 가능. PR-11a는 batch·consumer·domain·api(Flyway만). PR-11b는 api·domain·frontend |
| Flyway V4 적용 의존 | PR-11b가 InstrumentJpaEntity 컬럼 추가(name_norm·theme) — V4 적용 후 컴파일 OK. 단 Flyway는 부팅 시점 적용이라 컴파일 단계는 무관. **테스트 실행 단계에서 Flyway 적용 필요** |
| PR-11a가 main 위에서 KIS 의존 | mock 응답 (사용자 키 없음 환경) — InstrumentSyncJob은 fetch 실패 시 graceful (시드 fallback). 부팅 가능 |
| PR-11b가 main 위에서 backend 의존 | mock 응답으로 컴파일 GREEN. 통합 검증은 PR-11a 머지 후 |
| consumer 모듈 추가 의존 | 본 PR-11a는 consumer 모듈에 추가 의존 0 (Spring KafkaListener·KisRateLimiter는 PR-9b·PR-10b 정합 가동 중) |
| api 모듈 신규 의존 | `api/build.gradle.kts`에 추가 의존 0 (PostgreSQL JDBC·Spring Data JPA·Redis 기존 사용 중) |
| batch 모듈 신규 의존 | `batch/build.gradle.kts`에 Spring Scheduling 사용 — `@EnableScheduling` 구성 의무 (PR-9b 가동 중인 consumer 정합 — batch 모듈 1차 도입). sub-agent 검증 의무 |

**결론**: pre-push hook 양쪽 GREEN 가능. **권장 머지 순서**:
- **시나리오 A — PR-11a 먼저 머지 → PR-11b 합류** (권장):
  - PR-11a가 V4 + 시드 + Cron 본격 → PR-11b는 V4 컬럼 사용 검증 후 머지
  - PR-11b의 InstrumentJpaEntity 컬럼 추가가 V4 적용 검증 가능
- **시나리오 B — 동시 머지**: 양쪽 영역 분리라 가능. 단 사용자 dogfooding은 양쪽 머지 후

---

## §5. 인터페이스 동결로 인한 병렬 가능 메커니즘

market-data·backend-core가 동시 작업하더라도 다음이 양쪽이 같은 spec을 바라보고 진행되도록 보장:

### §5-1. REST endpoint 시그니처 LOCK (dispatch §5-3)

```
GET  /api/instruments/search?q=&limit=&market=  → InstrumentSearchResponseDto
GET  /api/instruments/{code}                     → InstrumentDetailDto
GET  /api/instruments?market=&active=&...        → InstrumentUniverseResponseDto
POST /api/instruments/sync                       → {jobId, triggered}
```

**누가 만드는지 사전 결정** — dispatch §5-3 명시. PR-11b api 단독 producer / frontend(자체) consumer.

### §5-2. Kafka topic 시그니처 LOCK (dispatch §5-4)

```
instrument.updated.v1   (본 PR-11a 신규 — batch/consumer publish + 1차 consumer 부재)
```

PR-11a producer / 1차는 consumer 부재 (STOMP fanout는 후속).

### §5-3. Redis 키 매트릭스 LOCK (dispatch §5-5)

```
instrument:by-code:{code}                  (PR-11b 작성·읽기)
instrument:list:{market}                   (PR-11b 작성·읽기)
instrument:search:prefix:{2자prefix}       (PR-11b 작성·읽기)
instrument:sync:running                    (PR-11a 작성·자체)
instrument:sync:lastAt                     (PR-11a 작성·읽기 양쪽)
```

### §5-4. Flyway V4 schema LOCK (dispatch §5-6)

PR-11a가 V4 발행. PR-11b는 V4 적용 후 InstrumentJpaEntity 컬럼 보강 + Repository 활용.

### §5-5. KIS 종목 마스터 endpoint LOCK (KIS-first 정책)

KIS open-trading-api repo `genfile_*.py` 출처 (또는 KIS REST 종목 마스터 endpoint MCP 검증). frontend는 KIS 직접 의존 0 (PR-11b는 backend api만).

### §5-6. 영역 분배 LOCK ★ (PR-9 결함 정정 + PR-10 정합)

dispatch §5-1 정합:
- market-data 영역: `batch/**`·`consumer/**`(KIS·Kafka·Topic config)·`domain/**`(add-only port·data class)·`api/src/main/resources/db/migration/V4*` (단독)
- backend-core 영역: `api/**`(Controller·Service·persistence·infrastructure)·`domain/**`(InstrumentRepository modify-own)·`frontend/**`(InstrumentSearchInput·WatchlistAddForm 정정·axios·hooks) (단독)
- 양 sub-agent 사전 비교 검증 완료 — add-add 0%, modify-shared 1건(CHANGELOG)만

---

## §6. 병렬 가능한 시나리오별 검증

### §6-1. 시나리오 A — PR-11a 먼저 머지 → PR-11b 합류 (권장)

```
T0: spec 4종 머지 (architect)
T0+5분: market-data 작업 시작 (worktree A)
T0+5분: backend-core 작업 시작 (worktree B)
T+3시간: market-data PR-11a 푸시 → CI green → 사용자 머지
T+3.5시간: backend-core rebase → PR-11b 푸시 → CI green → 통합 검증 → 머지
```

**왜 권장**:
- PR-11a Flyway V4가 먼저 적용되면 PR-11b InstrumentJpaEntity 컬럼 보강 검증 가능
- PR-11b가 KIS sync 동작 검증 후 통합
- 사용자 dogfooding은 양쪽 머지 후 즉시

**충돌 가능성**:
- CHANGELOG.md (ADR 0050 in-place edit + anchor) — 충돌 0
- 그 외 — 모두 격리

### §6-2. 시나리오 B — 동시 머지 (병렬 push)

```
T0: spec 머지
T0+5분: 양쪽 동시 시작
T+3시간: 양쪽 동시 push
T+3.5시간: CI green 양쪽
T+4시간: 사용자가 둘 머지 (어느쪽이든 무관)
```

**검증**: 사용자 dogfooding은 양쪽 머지 후 가능. 통합 검증은 후행 머지자가 rebase 후 자체 검증.

### §6-3. 시나리오 C — backend-core 먼저 머지 (권장하지 않음)

```
T0: spec 머지
T+3시간: backend-core PR-11b 머지 (Flyway V4 미적용 — InstrumentJpaEntity 컬럼 add는 mismatch)
```

**왜 권장하지 않는가**:
- V4 미적용 시 PR-11b InstrumentJpaEntity의 `name_norm`·`theme` 컬럼이 DB에 없음 → 부팅 실패
- 단 **PR-11b가 V4도 함께 발행하면** OK — 단 영역 침범 (V4는 PR-11a 영역). architect 권장 = 시나리오 A

### §6-4. 시나리오 D — 직렬 (권장하지 않음)

PR-9·PR-10과 동일 사유 — `feedback_orchestrator_active_parallel.md` 정합으로 직렬 권장 답습 금지.

---

## §7. 사용자 메모리 정합 검증 (필수)

| 사용자 메모리 | 본 결정 정합 |
|---|---|
| `feedback_orchestrator_active_parallel.md` — architect "직렬 권장" 답습 금지 + add-only 충돌은 진짜 병렬 | ✅ — 본 행렬에서 modify-shared 1건(CHANGELOG) 사전 정책으로 해소 + 영역 분배 LOCK 강화 + 병렬 권장 결론 |
| `feedback_3tier_hierarchy.md` — 오케스트라 → architect(설계) → 실무 sub-agents(병렬 실행) | ✅ — architect는 본 spec 4종 + backlog 갱신 발행, 코드 0건. market-data·backend-core 병렬 코드 작성 |
| `feedback_autonomy.md` — Claude 자율 실행, 사용자 확인은 아키텍처·외부자원·블로킹상충 3가지만 | ✅ — 본 spec에 사용자 결정 필요 사항 0건 (V4 신설·KIS-first·룰 2 사유 모두 architect 자율 결정) |
| `feedback_v3_skepticism_no_regression.md` — 자동매매 적합성 의심 lens | 자동매매 적합성: ✅ T-IM1은 자동매매 본체의 universe 정의·검증·검색 — T-IND·T-DSL·T-SC·T-BT 모두 의존. v1/v2 잔재 0건 (PR-9 발행 코드 그대로 + ADR 0017 정합). fitness gap: 0 — universe 부재 시 후속 모든 트랙 차단 |
| `feedback_kis_first_policy.md` — KIS MCP·open-trading-api 베이스 | ✅ — KIS 종목 마스터 endpoint·메시지 형식은 spec에 미명시 (sub-agent KIS-first MCP 검증 의무 명시) + 코드 인라인 출처 명시 의무 |
| `feedback_no_mock_data.md` — 사용자 흐름에서 mock 합성 폐기 | ✅ — 정적 시드 fallback은 시스템 전제(KIS 미가용 환경 동작 보장 — 사용자 path가 아닌 시스템 부팅 path) + 사용자가 보는 결과는 정확한 KOSPI 200·KOSDAQ 100 종목 (mock이 아닌 실 종목 데이터). 검색 결과 0건 시 graceful "검색 결과 없음" 표시 (mock 합성 0) |
| `feedback_secret_handling.md` — 키 평문 응답 reflect 금지 | ✅ — 본 PR-11의 응답 schema에 키 평문 reflect 0건 |
| `project_kis_key_management.md` — 개발 중 ⚙️ SettingsModal 입력 + DB AES | ✅ — 본 PR-11은 PR-7·PR-9·PR-10 등록된 AES BYTEA 키 그대로 복호화 사용 (KIS sync에서) |
| `feedback_qa_on_demand.md` — MVP 단계 QA 게이트 미적용 | ✅ — 본 PR-11 양쪽 smoke 1~3건 + 사용자 dogfooding 수동 검증 + R3 일괄은 별도 묶음 |
| `feedback_spec_plan_scope.md` — spec/plan 발행 범위 | ✅ — T-IM1 신규 트랙 + 다중 sub-agent + ADR 다중 정합 → spec/plan 발행 정합 |
| `project_track1_dogfooding_paused.md` — 첫 dogfooding 보류 + 다음 사이클 첫 액션 | ✅ — 본 PR-11이 W1 잔여 진입 + 자동완성 가시성 ↑로 dogfooding 재개 가능 트리거 (backlog §6 갱신 정합) |
| `feedback_spec_plan_scope.md` — spec/plan 발행 범위 (신규 트랙·다중 ADR일 때만) | ✅ — T-IM1 신규 트랙 진입 + ADR 0017·0039 정합 |
| `feedback_orchestrator_nickname.md` — 오케스트라 호칭 | ✅ |

---

## §8. 결론 — 권장 디스패치 모드

### §8-1. 1차 권장 — **시나리오 A** (PR-11a 먼저 + PR-11b 합류)

오케스트라가 spec 4종 머지 후:

```bash
# Step 1: 양쪽 동시 시작
git worktree add .claude/worktrees/agent-pr11-market-data agent/market-data/pr-11-im1-market-data
git worktree add .claude/worktrees/agent-pr11-backend-core agent/backend-core/pr-11-im1-backend

# Terminal 1 (market-data)
cd .claude/worktrees/agent-pr11-market-data
node scripts/task.mjs start --agent market-data --branch agent/market-data/pr-11-im1-market-data ...

# Terminal 2 (backend-core)
cd .claude/worktrees/agent-pr11-backend-core
node scripts/task.mjs start --agent backend-core --branch agent/backend-core/pr-11-im1-backend ...

# Step 2: PR-11a 먼저 머지 → PR-11b rebase + 머지
```

### §8-2. 2차 권장 — **시나리오 B** (완전 동시 머지)

영역 분리라 양쪽 동시 머지 가능. 단 사용자 dogfooding은 양쪽 머지 후.

### §8-3. 직렬은 권장하지 않음

직렬 권장 시 사유 입증 의무:
- modify-shared 1건(CHANGELOG) — ADR 0050 + anchor (PR-10 효과 입증)로 해소
- 인터페이스 동결 미달 — spec 4종이 동결 보장
- pre-push hook 종속 → 시나리오 A로 해소

**위 3건 모두 해소 — 직렬 권장 사유 부재**.

---

## §9. 운영 체크리스트 (오케스트라가 머지 시점 점검)

### §9-1. PR-11a (market-data) 머지 시점 (1차 권장 — 시나리오 A)

- [ ] PR-11b가 main rebase 가능한 상태 (충돌 없음 — backend-core 영역 미진입 검증)
- [ ] PR-11a CI green
- [ ] DoD 1·4·5·6·7·9·15·16 backend(batch·consumer) 영역 ✅
- [ ] PR-11a CHANGELOG slot row가 채워졌는지 확인 (`grep "(작성: market-data)" CHANGELOG.md` → TBD 아닌 실제 사유)
- [ ] PR-11a가 `(작성: backend-core)` row 미수정 + `(작성: architect)` row 미수정 확인 (영역 침범 0 검증)
- [ ] PR-11b 작업자에게 "main rebase + force push" 안내

### §9-2. PR-11b (backend-core) 머지 시점

- [ ] PR-11b CI green
- [ ] DoD 1·2·3·8·10·11·12·13·14 backend·frontend 영역 ✅
- [ ] PR-11b CHANGELOG slot row 채워짐 확인
- [ ] PR-11b가 `(작성: market-data)` row 미수정 + `(작성: architect)` row 미수정 확인
- [ ] 양쪽 머지 후 통합 게이트 (DoD 모든 항목) — 사용자 dogfooding 수동 검증 의무

### §9-3. 사용자 dogfooding 가이드 (양쪽 머지 후 — design §13 정합)

- [ ] `MAGICJAR_INSTRUMENT_SYNC_AT_BOOT=true scripts/build-and-deploy.sh` 통합 기동
- [ ] http://localhost:3000 진입 → 셋업 완료 확인
- [ ] `/watchlist` → "삼성" 입력 → dropdown 검증
- [ ] 클릭 → 등록 → row 표시 확인
- [ ] row 클릭 → SymbolDetailPage 헤더 정확 표시 확인
- [ ] design §13-2 사용자 검증 11 항목 통과 확인
- [ ] 발견 결함 list → backlog.md §6 추가 (다음 사이클 의제)

### §9-4. 머지 후 정리 (ADR 0033 §결과)

- [ ] worktree 2개 unlock + remove
- [ ] 로컬 브랜치 2개 -d
- [ ] task.mjs archive 2건
- [ ] CHANGELOG.md 정합 확인 (양쪽 row 모두 채워짐, TBD 잔재 0건, anchor 정상 잔존)

---

## §10. 발견된 함정·위험 (사용자 알아야 할 것)

본 PR-11 진행 중 식별된 함정 — 솔직 보고 (design §11 cross-ref):

### §10-1. KIS 종목 마스터 endpoint 자체 추론 risk ★ (design §11-1)

KIS-first 정책 정합. sub-agent가 자체 추론 시:
- 잘못된 endpoint → sync 실패 → instrument 빈 상태 + dogfooding 차단 (시드만 fallback)
- zip 파싱 fixed-width 인덱스 오류 → 종목명 깨짐

**처리**: dispatch §3-2-C에 KIS open-trading-api `genfile_*.py` 출처 의무 + `kis-code-assistant` MCP 사용 의무 + 코드 인라인 출처 명시 의무.

### §10-2. PostgreSQL pg_trgm extension 부재 risk (design §11-2·§11-11)

V4 마이그레이션 시 `CREATE EXTENSION pg_trgm` 권한 미보유 가능성.

**처리**: `IF NOT EXISTS` 명시 + 실패 시 fallback (gin 인덱스 미생성 — ILIKE만 사용). dispatch §3-2-A 검증 의무.

### §10-3. instrument_alias 비어있을 때 검색 fallback (design §11-3)

1차 sync는 alias_source='KRX'만 1건/종목. 영문/한글 cross 검색 미지원.

**처리**: `name` ILIKE 매칭이 1차 — alias 부재여도 정확. 영문 검색·typo는 post-MVP.

### §10-4. PR-7 옵션 C stub UPDATE race (design §11-4)

sync 진행 중 사용자가 새 stub INSERT — 다음 sync에서 자연 정합. race window 5~10분 OK.

### §10-5. ADR 0050 슬롯 채번룰 세 번째 적용 — anchor 정합 (design §11-5)

PR-10 anchor 효과 입증 → PR-11도 동일 패턴. 추가 변경 0.

### §10-6. 자동완성 한글 IME composition 함정 (design §11-6)

IME 조합 중 axios 호출 → 빈 결과.

**처리**: `compositionstart`·`compositionend` 핸들러로 차단 — dispatch §C·§5-1 LOCK 명시.

### §10-7. universe endpoint 부담 (design §11-7)

3000 row 조회 — limit=100 default 권장. universe browser UI는 후속 트랙.

### §10-8. SymbolMetaService 정정 의무 (design §11-8)

PR-10b 발행 SymbolMetaService에 instrument 1순위 read 추가 — backend-core 본인 영역 modify-own. dispatch §4-2-E LOCK.

### §10-9. 영업일 정밀도 부족 (design §11-9)

SimpleMarketSessionResolver 시간 stub — 휴장일 정밀 분기 부재. T-MS1 진입 시 정합.

### §10-10. KIS 종목 마스터 latency (design §11-10)

KIS zip 파싱 5~15초. 13 cron 분산이라 충돌 risk ≤ 0. 1차 동기 OK.

### §10-11. JPA secondary constructor 정합 (design §11-12)

InstrumentJpaEntity 보강 시 protected constructor의 default 값 정합 의무.

### §10-12. 사용자 dogfooding 보류 — 본 PR-11 머지 후 재개 트리거 (design §11-13)

사용자 메모리 `project_track1_dogfooding_paused.md` — backlog §6 갱신 정합.

### §10-13. consumer 영역 침범 패턴 (PR-9 결함 — backlog §5)

PR-11에서:
- consumer 모듈 모든 변경 = market-data 단독 (backend-core 미진입)
- ✅ 영역 침범 0 보장

### §10-14. frontend 영역도 backend-core 묶음 (룰 2)

dispatch §2 정합 — 사유 명시 의무. PR 본문에 명시.

**위험**: backend-core가 frontend 변경에 익숙하지 않을 가능성 → 디스패치 시점에 SKILL 정합 검증 의무.

---

## §11. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-11 충돌 행렬 v1 발행. 결론: **PR-11a + PR-11b 완전 병렬 권장 (시나리오 A 1차)**. modify-shared 1건(CHANGELOG)만 — ADR 0050 슬롯 채번룰 세 번째 적용 + HTML 주석 anchor (PR-10 효과 입증)로 충돌 0 보장. PR-9 결함 정정 — 영역 분배 LOCK 강화로 add-add 0% 사전 검증 (PR-10 정합 패턴 그대로). 발견된 함정 14건 명시 (특히 §10-1 KIS-first 추론 risk, §10-2 pg_trgm 권한, §10-12 dogfooding 재개 트리거, §10-14 룰 2 frontend 묶음 사유). (architect, opus 4.7 1M) |
