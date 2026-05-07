# PR-21 충돌 행렬 (병렬 디스패치 결정 근거)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `pr-21-instrument-kis-first-design.md` v1 + `pr-21-dispatch-spec.md` v1 + `pr-21-kis-header-raw-verification.md` v1 + ADR 0017·0039·0050
- 사용자 메모리 정합:
  - `feedback_orchestrator_active_parallel.md` — architect "직렬 권장" 답습 금지. add-only 충돌은 진짜 병렬 가능
  - `feedback_3tier_hierarchy.md` — 오케스트라 → architect → 실무 sub-agents (병렬)
  - `feedback_kis_first_policy.md` — KIS endpoint·메시지 형식은 open-trading-api raw fetch 의무 (위반 정정 사이클)
  - `feedback_v3_skepticism_no_regression.md` — fitness gap 의무
  - `feedback_autonomy.md` — Claude 자율 실행

---

## §1. 결론 (한 줄)

> **PR-21a (market-data) + PR-21b (backend-core) 완전 병렬 권장 (시나리오 A — PR-21a 먼저 머지). 양쪽 영역이 batch+api(Flyway 단독)+consumer(1차 비범위)+domain(InstrumentType enum add-only) vs api(JPA·Repository·Service·DTO)+domain(InstrumentRepository port modify-own)로 완전 분리. modify-shared 1건(CHANGELOG.md)은 ADR 0050 슬롯 채번룰 네 번째 적용 + HTML 주석 anchor (PR-10·PR-11 효과 입증)로 충돌 0 보장. PR-9 add-add 결함은 본 PR-21 dispatch §5-1 영역 분배 LOCK 강화로 0% 사전 검증.**

---

## §2. 인터페이스 동결 시점

본 spec 5종(`pr-21-instrument-kis-first-design.md` + `pr-21-kis-header-raw-verification.md` + `pr-21-dispatch-spec.md` + 본 문서 + `_workspace/backlog.md` 갱신)이 PR로 머지되는 순간 = 인터페이스 동결.

동결되는 계약:
- `pr-21-instrument-kis-first-design.md` §3 (DB 스키마 LOCK — 70 컬럼 add-only)
- `pr-21-instrument-kis-first-design.md` §4 (Flyway V5 SQL LOCK)
- `pr-21-instrument-kis-first-design.md` §5 (InstrumentJpaEntity 83 필드 LOCK)
- `pr-21-instrument-kis-first-design.md` §6 (KisInstrumentMasterAdapter 파싱 LOCK + widths 배열 KOSPI 70 + KOSDAQ 64)
- `pr-21-instrument-kis-first-design.md` §7 (InstrumentType enum 'DR' add)
- `pr-21-instrument-kis-first-design.md` §9 (Watchlist DTO 보강 6 필드 LOCK)
- `pr-21-dispatch-spec.md` §3 (PR-21a 작업 범위 LOCK)
- `pr-21-dispatch-spec.md` §4 (PR-21b 작업 범위 LOCK)
- `pr-21-dispatch-spec.md` §5-1 (영역 분배 LOCK ★)
- `pr-21-dispatch-spec.md` §6 (KIS-first 강화 + raw 검증 보고서 인용 의무)
- `pr-21-dispatch-spec.md` §7 (CHANGELOG 슬롯 row 4 anchor + 3 row LOCK)
- ADR 0017·0039·0050 (모두 본 PR 정합)

**인터페이스 동결 즉시** market-data·backend-core 양쪽이 spec만 보고 동시에 자기 worktree에서 작업 시작 가능.

---

## §3. 파일 단위 충돌 행렬

### §3-1. PR-21a (market-data) vs PR-21b (backend-core) 변경 파일

| 파일/디렉토리 | PR-21a | PR-21b | 충돌 유형 | 결정 |
|---|---|---|---|---|
| `api/src/main/resources/db/migration/V5__instrument_kis_first_expansion.sql` | **신규** | 무관 | add-only | **격리** (PR-21a 단독) |
| `api/.../persistence/InstrumentJpaEntity.kt` | 무관 | **변경** (70 필드 add) | modify-own | **격리** (backend-core 본인 영역 — PR-7·PR-11 발행 파일) |
| `api/.../persistence/InstrumentJpaRepository.kt` | 무관 | **변경** (메서드 add) | modify-own | **격리** (backend-core 본인 영역) |
| `api/.../persistence/InstrumentRepositoryAdapter.kt` | 무관 | **변경** (implement add) | modify-own | **격리** (backend-core 본인 영역) |
| `api/.../persistence/InstrumentAliasJpaEntity.kt` | 무관 | (변경 0) | (변경 0) | **격리** |
| `api/.../persistence/InstrumentAliasJpaRepository.kt` | 무관 | (변경 0) | (변경 0) | **격리** |
| `api/.../service/InstrumentSearchService.kt` | 무관 | **변경** (응답 보강) | modify-own | **격리** (PR-11b 발행 — backend-core 본인 영역) |
| `api/.../service/SymbolMetaService.kt` | 무관 | **변경** (응답 보강) | modify-own | **격리** (PR-10b·PR-11b 발행 — backend-core 본인 영역) |
| `api/.../service/WatchlistService.kt` | 무관 | **변경** (응답 보강) | modify-own | **격리** (PR-7b·PR-20 발행 — backend-core 본인 영역) |
| `api/.../controller/InstrumentDto.kt` | 무관 | **변경** (DTO 필드 add) | modify-own | **격리** (PR-11b 발행 — backend-core 본인 영역) |
| `api/.../controller/WatchlistDto.kt` | 무관 | **변경** (DTO 필드 add) | modify-own | **격리** (PR-7b·PR-20 발행 — backend-core 본인 영역) |
| `api/.../controller/InstrumentController.kt` | 무관 | (변경 0 권고) | (변경 0) | **격리** |
| `api/.../controller/WatchlistController.kt` | 무관 | (변경 0 권고 — 시그니처 그대로) | (변경 0) | **격리** |
| `domain/.../instrument/Instrument.kt` (InstrumentType enum 'DR' add) | **변경** (enum value 1줄 add-only) | (변경 0) | **modify-own** | **격리** (market-data 단독 — enum value add는 add-only 효과) |
| `domain/.../instrument/port/InstrumentRepository.kt` | (변경 0) | **변경** (4 메서드 add) | modify-own | **격리** (PR-7 발행 — backend-core 본인 영역) |
| `domain/.../instrument/port/KisInstrumentMasterPort.kt` | (변경 0 권고 — 시그니처 그대로) | 무관 | (변경 0) | **격리** |
| `domain/.../instrument/InstrumentSearchResult.kt` | (변경 0) | (변경 0 또는 필드 add 권고 — type 노출) | modify-own (옵션) | **격리** (backend-core 단독) |
| `batch/.../infrastructure/kis/KisInstrumentMasterAdapter.kt` | **변경** (파싱 본격 보강) | 무관 | modify-own | **격리** (PR-11a·PR-20 발행 — market-data 본인 영역) |
| `batch/.../infrastructure/kis/KisInstrumentRaw.kt` | **신규** | 무관 | add-only | **격리** |
| `batch/.../job/instrument/InstrumentSyncJob.kt` | **변경** (upsert 보강) | 무관 | modify-own | **격리** (PR-11a 발행 — market-data 본인 영역) |
| `batch/.../job/instrument/InstrumentSyncResult.kt` | (변경 0 권고) | 무관 | (변경 0) | **격리** |
| `batch/.../persistence/BatchInstrumentJpaEntity.kt` | **변경** (sync write 필요 필드 add) | 무관 | modify-own | **격리** (PR-11a 발행 — market-data 본인 영역) |
| `batch/.../persistence/BatchInstrumentJpaRepository.kt` | (변경 0 권고) | 무관 | (변경 0) | **격리** |
| `batch/.../scheduler/InstrumentMasterSyncScheduler.kt` | (변경 0 권고) | 무관 | (변경 0) | **격리** |
| `batch/.../bootstrap/InstrumentSeedRunner.kt` | (변경 0 권고) | 무관 | (변경 0) | **격리** (시드는 Tier 1만 채움 — design §12-6) |
| `batch/src/main/resources/instrument-seed.json` | (변경 0 권고 — 시드 내용은 Tier 1 그대로) | 무관 | (변경 0) | **격리** |
| `batch/src/test/.../KisInstrumentMasterAdapterTest.kt` | **신규 또는 보강** | 무관 | add-only/modify-own | **격리** |
| `consumer/.../infrastructure/kis/KisInstrumentRaw.kt` (기존 PR-11a 파일) | (변경 0 권고 — batch로 이동했음) | 무관 | (변경 0) | **격리** |
| `consumer/.../persistence/ConsumerInstrumentJpaEntity.kt` | (1차 비범위) | 무관 | (변경 0) | **격리** |
| `consumer/.../kafka/InstrumentUpdatedKafkaPublisher.kt` | (변경 0 권고) | 무관 | (변경 0) | **격리** |
| `frontend/**` | (변경 0) | (변경 0 — UI 변경은 별도 사이클) | (변경 0) | **격리** (룰 2 단일 책임) |
| `docker/**` | (변경 0) | (변경 0) | (변경 0) | **격리** |
| `scripts/**` | (변경 0) | (변경 0) | (변경 0) | **격리** |
| `.github/workflows/*` | (변경 0) | (변경 0) | (변경 0) | **격리** |
| `.env.example` | (변경 0 권고 — 본 PR-21 새 env 0) | (변경 0) | (변경 0) | **격리** |
| `CHANGELOG.md` | **변경** (PR-21a slot row in-place edit — 1줄) | **변경** (PR-21b slot row in-place edit — 1줄) | **modify-shared** | **ADR 0050 슬롯 채번룰 네 번째 적용 + HTML 주석 anchor (PR-10·PR-11 효과 입증) — 충돌 0 보장** |
| `docs/architecture/*` | (변경 0) | (변경 0) | (변경 0) | **격리** (architect 단독 — 본 spec PR에 포함 가능) |
| `_workspace/arch/adr/*` | (변경 0) | (변경 0) | (변경 0) | **격리** (architect 단독) |
| `_workspace/specs/pr-21-*.md` | (변경 0) | (변경 0) | (변경 0) | **격리** (architect 단독) |
| `_workspace/backlog.md` | (변경 0) | (변경 0) | (변경 0) | **격리** (architect 단독 — 본 PR-21 spec PR에 갱신) |
| `_workspace/tasks/T-*-pr-21-instrument-kis-first.md` (architect) | 무관 | 무관 | (architect 단독) | **격리** |
| `_workspace/tasks/T-*-pr-21-market-data.md` | **신규** (자기 task) | 무관 | add-only | **격리** |
| `_workspace/tasks/T-*-pr-21-backend-core.md` | 무관 | **신규** (자기 task) | add-only | **격리** |
| `.claude/skills/magicjar-*/SKILL.md` (KIS-first 강화) | (변경 0) | (변경 0) | (변경 0) | **격리** (architect 단독 — 본 spec PR에 포함) |
| `.claude/agents/architect.md` (KIS-first raw fetch 의무 강화) | (변경 0) | (변경 0) | (변경 0) | **격리** (architect 단독) |

### §3-2. modify-shared 후보 분석

총 **1건** 식별:

#### `CHANGELOG.md`

- 변경 내용 — 양쪽 sub-agent가 본인 슬롯 row 1줄 in-place edit (ADR 0050 슬롯 채번룰)
- **충돌 0 보장**:
  - ADR 0050 §3-2 — git diff가 다른 row 변경으로 인식 → 양쪽 머지 시 conflict marker 발생 없음
  - 본 PR-21에서 **HTML 주석 anchor line** 4건 (architect spec + market-data + backend-core + end) 적용 — PR-10·PR-11 효과 입증된 패턴
  - hunk-based merge가 anchor + row를 1 hunk로 묶음 → 더 명확한 분리
- **본 PR-21이 ADR 0050 네 번째 적용 사이클** — PR-9·PR-10·PR-11 정합 누적 후 적용

#### 그 외 (modify-shared 0건 추가)

- domain `Instrument.kt` — market-data가 InstrumentType enum value 'DR' 1줄만 add-only. backend-core는 본 파일 변경 0. 같은 파일이지만 modify-own/단독 (충돌 없음). 만약 backend-core가 도메인 모델 변경 시도 시 영역 침범으로 reject (본 spec 정합).
- `application.yml` — batch·api·consumer 모두 변경 0 권고 (본 PR-21은 새 env 0)
- domain — 본 PR-21a는 enum 1 value add-only, PR-21b는 InstrumentRepository port modify-own. 다른 파일이라 충돌 0
- api 모듈 PR-7·PR-10b·PR-11b 발행 파일 — 모두 backend-core 본인 영역 (영역 침범 0)
- batch 모듈 PR-11a·PR-19·PR-20 발행 파일 — 모두 market-data 본인 영역
- consumer 모듈 — 1차 비범위 (변경 0)
- frontend 모듈 — 1차 비범위 (변경 0)

### §3-3. 격리 검증 — 양 sub-agent 사전 add-add 가능 파일 비교 ★

**PR-9 결함 정정 패턴 그대로 — 양 sub-agent의 add 파일 목록 사전 비교**:

| PR-21a (market-data) add 파일 | PR-21b (backend-core) add 파일 | 동명 add 가능성 |
|---|---|---|
| `api/src/main/resources/db/migration/V5__instrument_kis_first_expansion.sql` | (api migration add 0건) | **0%** |
| `batch/.../infrastructure/kis/KisInstrumentRaw.kt` | (batch add 0건) | **0%** |
| `batch/src/test/.../KisInstrumentMasterAdapterTest.kt` (신규/보강) | (batch test add 0건) | **0%** |
| `_workspace/tasks/T-*-pr-21-market-data.md` | `_workspace/tasks/T-*-pr-21-backend-core.md` | **0%** (다른 파일명) |
| (api 신규 add 0건 — Service/Controller/DTO는 backend-core 보강) | `api/.../service/...` (모두 보강 — 신규 add 0건) | **0%** |
| (frontend add 0건 — 1차 비범위) | (frontend add 0건 — 1차 비범위) | **0%** |
| (consumer add 0건) | (consumer add 0건) | **0%** |

**결론**: 양 sub-agent의 add-add 가능성 **0% 사전 검증 완료**. PR-9·PR-10·PR-11 결함 정정 패턴 본 PR-21에서 이어짐.

### §3-4. modify-shared 검증 — 같은 파일을 양 sub-agent가 수정하는지

**PR-21a market-data가 modify하는 파일**:
- `domain/.../instrument/Instrument.kt` (InstrumentType enum 1줄 add-only)
- `batch/.../infrastructure/kis/KisInstrumentMasterAdapter.kt` (파싱 본격 보강)
- `batch/.../job/instrument/InstrumentSyncJob.kt` (upsert 보강)
- `batch/.../persistence/BatchInstrumentJpaEntity.kt` (sync write 필요 필드 add)
- `CHANGELOG.md` (slot row in-place edit)

**PR-21b backend-core가 modify하는 파일**:
- `domain/.../instrument/port/InstrumentRepository.kt` (4 메서드 add)
- `api/.../persistence/InstrumentJpaEntity.kt` (70 필드 add)
- `api/.../persistence/InstrumentJpaRepository.kt` (메서드 add)
- `api/.../persistence/InstrumentRepositoryAdapter.kt` (implement add)
- `api/.../service/InstrumentSearchService.kt` (응답 보강)
- `api/.../service/SymbolMetaService.kt` (응답 보강)
- `api/.../service/WatchlistService.kt` (응답 보강)
- `api/.../controller/InstrumentDto.kt` (DTO 필드 add)
- `api/.../controller/WatchlistDto.kt` (DTO 필드 add)
- `CHANGELOG.md` (slot row in-place edit)

**동시 modify 파일**: `CHANGELOG.md` 1건 — ADR 0050 in-place edit + anchor 정합으로 충돌 0 보장.

**결론**: 양 sub-agent는 modify-shared 1건만 — ADR 0050으로 충돌 0 보장.

### §3-5. domain `Instrument.kt` 잠재 충돌 분석 ★

같은 파일이지만 양쪽 변경 0 또는 add-only 분석:

- **market-data (PR-21a)**: `InstrumentType` enum value 'DR' 1줄 add-only (변경 line ~70-80 enum 정의 영역)
- **backend-core (PR-21b)**: 변경 0 (architect 결정 — domain 모델 simplicity 유지)

**결론**: 같은 파일이지만 양쪽 hunk 분리 (PR-21a만 enum 1줄 add). PR-21b가 domain 변경 미진입 — 영역 침범 0 + 충돌 0.

**위반 시 처리**: PR-21b가 domain `Instrument` 변경 시 architect spec 위반 → reject. dispatch §5-1 영역 분배 LOCK 강제.

---

## §4. pre-push hook 종속성 분석

| 이유 | 분석 |
|---|---|
| `./gradlew check` (pre-push 자동) | 양쪽 PR 모두 main 위에서 GREEN 가능. PR-21a는 batch·api(Flyway 단독)·domain(enum)·consumer(미진입). PR-21b는 api·domain |
| Flyway V5 적용 의존 | **PR-21b가 InstrumentJpaEntity 70 컬럼 add → V5 적용 후 부팅 검증 가능**. 컴파일은 무관(JPA validation은 부팅 시점) — 단 테스트 실행 단계에서 Flyway 자동 적용 (test profile + H2/PostgreSQL testcontainers) → 테스트도 컬럼 부재 시 fail. **PR-21a 먼저 머지 권장** |
| PR-21a가 main 위에서 KIS 의존 | KIS 미가용 환경 graceful — InstrumentSyncJob fetch 실패 시 시드 fallback 유지 (PR-11a 정합). 부팅 가능 |
| PR-21b가 main 위에서 backend 의존 | mock 응답으로 컴파일 GREEN. 통합 검증은 PR-21a 머지 후 |
| domain 모듈 의존 | PR-21a InstrumentType 'DR' add → backend-core가 본 enum value 사용 시 동기화 필요. 본 PR-21b는 'DR' 사용 0건 — 의존 분리. PR-21a 단독 add 가능 |
| consumer 모듈 추가 의존 | 본 PR-21a는 consumer 모듈 변경 0 (KisInstrumentMasterAdapter는 batch 모듈로 이미 이동) — 의존 0 |
| api 모듈 신규 의존 | `api/build.gradle.kts`에 추가 의존 0 (PostgreSQL JDBC·Spring Data JPA·Redis 기존 사용 중) |
| batch 모듈 신규 의존 | `batch/build.gradle.kts`에 추가 의존 0 (KIS WebClient·Spring Scheduling·Spring Data JPA 기존 사용 중) |

**결론**: pre-push hook 양쪽 GREEN 가능. **권장 머지 순서**: 시나리오 A (PR-21a 먼저 → PR-21b 합류).

---

## §5. 인터페이스 동결로 인한 병렬 가능 메커니즘

market-data·backend-core가 동시 작업하더라도 다음이 양쪽이 같은 spec을 바라보고 진행되도록 보장:

### §5-1. DB 컬럼 LOCK (design §3·§4)

V5 SQL 70 컬럼 + 8 CHECK + 8 INDEX는 design §4-2에 LOCK. PR-21a는 그대로 SQL 발행, PR-21b는 그 컬럼명·타입·NOT NULL 그대로 entity 매핑.

### §5-2. InstrumentJpaEntity 83 필드 LOCK (design §5)

design §5-2 entity 골격 LOCK. PR-21b는 그 골격 그대로 발행. PR-21a는 V5만 발행 (entity 미관여 — read-write 분리는 영역 LOCK 정합).

### §5-3. KisInstrumentMasterAdapter widths LOCK (design §6)

KOSPI 70 widths + KOSDAQ 64 widths는 design §6-3 LOCK. PR-21a는 그대로 코드 발행. PR-21b는 미관여.

### §5-4. InstrumentType enum 'DR' LOCK (design §7)

design §7-3 LOCK — PR-21a 단독 발행. PR-21b는 enum 'DR' 사용 0 (사용은 후속 트랙).

### §5-5. WatchlistEntryDto 6 필드 LOCK (design §9-2)

design §9-2 LOCK — PR-21b 단독 발행. PR-21a 미관여.

### §5-6. KIS-first 출처 LOCK (KIS-first 정책 강화)

`pr-21-kis-header-raw-verification.md` baseline. PR-21a 코드 inline 주석에 raw URL 직접 인용 의무. PR-21b는 design §3·§5·§9 인용 (자체 추론 0건).

### §5-7. 영역 분배 LOCK ★ (PR-9·PR-10·PR-11 결함 정정 패턴 정합)

dispatch §5-1 정합:
- market-data 영역: `batch/**`(KIS·Sync logic·BatchEntity)·`api/src/main/resources/db/migration/V5*` (단독)·`domain/.../instrument/Instrument.kt`(enum value 1줄 add) (단독)
- backend-core 영역: `api/**`(JPA·Repository·Service·Controller·DTO)·`domain/.../instrument/port/InstrumentRepository.kt`(modify-own) (단독)
- 양 sub-agent 사전 비교 검증 완료 — add-add 0%, modify-shared 1건(CHANGELOG)만

---

## §6. 병렬 가능한 시나리오별 검증

### §6-1. 시나리오 A — PR-21a 먼저 머지 → PR-21b 합류 (권장 ★)

```
T0: spec 5종 머지 (architect)
T0+5분: market-data 작업 시작 (worktree A)
T0+5분: backend-core 작업 시작 (worktree B)
T+3시간: market-data PR-21a 푸시 → CI green → 사용자 머지
T+3.5시간: backend-core rebase → PR-21b 푸시 → CI green → 통합 검증 → 머지
```

**왜 권장**:
- PR-21a Flyway V5 먼저 적용 → PR-21b InstrumentJpaEntity 70 컬럼 매핑이 V5 위에서 정합 검증 가능
- PR-21b 부팅 시 JPA `ddl-auto=validate` (또는 entity vs schema 검증) 통과 보장
- KIS sync 결과 (Tier 1·2·3·4 채워진 row) → PR-21b 응답 보강 통합 검증 가능

**충돌 가능성**:
- CHANGELOG.md (ADR 0050 in-place edit + anchor) — 충돌 0
- domain `Instrument.kt` (PR-21a enum 1줄 add) — PR-21b 미진입 — 충돌 0
- 그 외 — 모두 격리

### §6-2. 시나리오 B — 동시 머지 (병렬 push)

```
T0: spec 머지
T0+5분: 양쪽 동시 시작
T+3시간: 양쪽 동시 push
T+3.5시간: CI green 양쪽
T+4시간: 사용자가 둘 머지 (PR-21a 먼저 권장)
```

**위험**: PR-21b가 먼저 머지되면 V5 미적용 상태 → JPA 부팅 fail risk. **시나리오 A 강제 권장** (사용자 직접 머지 순서 제어).

### §6-3. 시나리오 C — backend-core 먼저 머지 (권장하지 않음)

V5 미적용 상태 → InstrumentJpaEntity 70 컬럼 매핑 vs DB 14 컬럼 mismatch → 부팅 fail.

**위반 시**: 사용자가 PR-21a 즉시 머지 (V5 적용으로 정정). 또는 PR-21b 일시 revert.

### §6-4. 시나리오 D — 직렬 (권장하지 않음)

`feedback_orchestrator_active_parallel.md` 정합 — modify-shared 1건(CHANGELOG)만 + ADR 0050 정합 → 병렬 권장 유지.

---

## §7. 사용자 메모리 정합 검증 (필수)

| 사용자 메모리 | 본 결정 정합 |
|---|---|
| `feedback_orchestrator_active_parallel.md` — architect "직렬 권장" 답습 금지 + add-only 충돌은 진짜 병렬 | ✅ — 본 행렬에서 modify-shared 1건(CHANGELOG) 사전 정책으로 해소 + 영역 분배 LOCK 강화 + 병렬 권장 결론 |
| `feedback_3tier_hierarchy.md` — 오케스트라 → architect(설계) → 실무 sub-agents(병렬 실행) | ✅ — architect는 본 spec 5종 + backlog 갱신 + SKILL 강화 발행, 코드 0건. market-data·backend-core 병렬 코드 작성 |
| `feedback_autonomy.md` — Claude 자율 실행, 사용자 확인은 아키텍처·외부자원·블로킹상충 3가지만 | ✅ — 본 spec에 사용자 결정 필요 사항 0건 (Tier 분류·V5 SQL·widths 배열·트레이드오프 9건 모두 architect 자율 결정) |
| `feedback_v3_skepticism_no_regression.md` — 자동매매 적합성 의심 lens | 자동매매 적합성: ✅ Tier 1·2(매매 가드 17 컬럼) 핵심 — fitness gap 91.7% → 13% 축소. v1/v2 잔재 0건 (PR-11a 발행 코드 그대로 보강 + ADR 0017·0039 정합). |
| `feedback_kis_first_policy.md` — KIS MCP·open-trading-api 베이스 ★ | ✅ — 본 PR-21이 위반 정정 사이클. KIS 헤더 raw 검증 보고서 baseline + sub-agent inline 주석 의무 + KIS MCP 사용 권장. 자체 추론 0건 입증 |
| `feedback_no_mock_data.md` — 사용자 흐름에서 mock 합성 폐기 | ✅ — 정적 시드는 시스템 부팅 path만 (Tier 1만 채움). 사용자가 보는 결과는 KIS sync 결과 기준 (Tier 1·2·3·4 정확 채움). |
| `feedback_secret_handling.md` — 키 평문 응답 reflect 금지 | ✅ — 본 PR-21의 응답 schema에 키 평문 reflect 0건 |
| `project_kis_key_management.md` — 개발 중 ⚙️ SettingsModal 입력 + DB AES | ✅ — 본 PR-21은 PR-7·PR-9·PR-10·PR-11 등록된 AES BYTEA 키 그대로 복호화 사용 |
| `feedback_qa_on_demand.md` — MVP 단계 QA 게이트 미적용 | ✅ — 본 PR-21 양쪽 smoke 1+1건 + 사용자 dogfooding 수동 검증 + R3 일괄은 별도 묶음 |
| `feedback_spec_plan_scope.md` — spec/plan 발행 범위 | ✅ — KIS-first 정책 위반 정정 + 70 필드 보강 + ADR/SKILL 강화 다중 → spec/plan 발행 정합 |
| `project_track1_dogfooding_paused.md` — 첫 dogfooding 보류 + 다음 사이클 첫 액션 | ✅ — 본 PR-21이 PR-20 dogfooding 결과 흡수 후속 (instrument fitness gap 정정) |
| `feedback_orchestrator_nickname.md` — 오케스트라 호칭 | ✅ |

---

## §8. 결론 — 권장 디스패치 모드

### §8-1. 1차 권장 — **시나리오 A** (PR-21a 먼저 + PR-21b 합류) ★

오케스트라가 spec 5종 머지 후:

```bash
# Step 1: 양쪽 동시 시작
git worktree add .claude/worktrees/agent-pr21-market-data agent/market-data/pr-21-instrument-kis-first
git worktree add .claude/worktrees/agent-pr21-backend-core agent/backend-core/pr-21-instrument-jpa-expansion

# Terminal 1 (market-data)
cd .claude/worktrees/agent-pr21-market-data
node scripts/task.mjs start --agent market-data --branch agent/market-data/pr-21-instrument-kis-first ...

# Terminal 2 (backend-core)
cd .claude/worktrees/agent-pr21-backend-core
node scripts/task.mjs start --agent backend-core --branch agent/backend-core/pr-21-instrument-jpa-expansion ...

# Step 2: PR-21a 먼저 머지 → PR-21b rebase + 머지
```

### §8-2. 2차 권장 — **시나리오 B** (완전 동시 머지)

영역 분리라 양쪽 동시 머지 가능. 단 **PR-21a 먼저 머지 권장**(V5 의존성).

### §8-3. 직렬은 권장하지 않음

직렬 권장 시 사유 입증 의무:
- modify-shared 1건(CHANGELOG) — ADR 0050 + anchor (PR-10·PR-11 효과 입증)로 해소
- 인터페이스 동결 미달 — spec 5종이 동결 보장
- pre-push hook 종속 → 시나리오 A로 해소

**위 3건 모두 해소 — 직렬 권장 사유 부재**.

---

## §9. 운영 체크리스트 (오케스트라가 머지 시점 점검)

dispatch §9 정합. 핵심 추가:

### §9-1. PR-21a (market-data) 머지 시점

- [ ] PR-21b가 main rebase 가능한 상태 (충돌 없음 — backend-core 영역 미진입 검증)
- [ ] PR-21a CI green
- [ ] V5 SQL 70 컬럼 정합 (design §4-2)
- [ ] widths 배열 정합 (design §6-3 KOSPI 70 + KOSDAQ 64)
- [ ] InstrumentType enum 'DR' add-only
- [ ] CHANGELOG `(작성: market-data)` row 채워짐

### §9-2. PR-21b (backend-core) 머지 시점

- [ ] PR-21b CI green
- [ ] InstrumentJpaEntity 70 필드 정합 (design §5-2)
- [ ] InstrumentRepository port 4 메서드 add-only
- [ ] WatchlistEntryDto·InstrumentDetailDto 응답 보강
- [ ] CHANGELOG `(작성: backend-core)` row 채워짐
- [ ] 통합 검증 (V5 적용 후 entity 매핑 + Repository 조회)

### §9-3. 머지 후 정리 (ADR 0033 §결과)

- [ ] worktree 2개 unlock + remove
- [ ] 로컬 브랜치 2개 -d
- [ ] task.mjs archive 2건
- [ ] CHANGELOG.md 정합 확인 (4 row 모두 채워짐, TBD 잔재 0건, anchor 정상 잔존)

---

## §10. 발견된 함정·위험 (사용자 알아야 할 것)

본 PR-21 진행 중 식별된 함정 — 솔직 보고 (design §12 cross-ref):

### §10-1. KOSPI/KOSDAQ widths 배열 mismatch ★ (design §12-1)

**처리**: dispatch §3-2-B에 widths 배열 LOCK 명시. smoke test 1건 의무.

### §10-2. Part1 한글명 가변 (design §12-2)

**처리**: PR-20 정합 — `lineBytes.size - part2Length - 21`.

### §10-3. yyyymmdd "00000000" (design §12-3)

**처리**: parseYyyymmdd 헬퍼 + null 반환.

### §10-4. roe 음수 (design §12-4)

**처리**: BigDecimal(s) 변환.

### §10-5. V5 번호 충돌 (design §12-5)

**처리**: 현재 V1·V2·V3·V4 — V5 OK.

### §10-6. 정적 시드 부족 (design §12-6)

**처리**: 시드 Tier 1만 (mock 합성 0).

### §10-7. KIS 응답 변경 risk (design §12-7)

**처리**: smoke test에 005930 알려진 값 검증.

### §10-8. PR-11 spec 누락 흔적 (design §12-8)

**처리**: architect SKILL 강화 의무 (§16) — 본 PR-21 spec PR에 포함.

### §10-9. entity 3 모듈 정합 (design §12-9)

**처리**: api(전체 보강) + batch(write 필요 보강) + consumer(1차 비범위).

### §10-10. ADR 0050 슬롯 채번룰 네 번째 적용 — anchor 정합

PR-10·PR-11 anchor 효과 입증 → PR-21도 동일 패턴. 추가 변경 0.

### §10-11. domain `Instrument.kt` 양쪽 변경 가능 함정

**risk**: market-data가 enum 'DR' add 시 같은 파일에 backend-core가 다른 변경 시도 가능성.

**처리**: 영역 분배 LOCK 강제 — backend-core domain modify-own 영역은 `port/InstrumentRepository.kt` 단독. `Instrument.kt` 본 PR-21에서 변경 0 강제 (architect 결정).

### §10-12. JPA `ddl-auto=validate` 부팅 검증

**risk**: PR-21b가 PR-21a보다 먼저 머지되면 V5 미적용 → entity 70 컬럼이 DB에 없음 → 부팅 fail.

**처리**: 시나리오 A 강제 권장 (PR-21a 먼저).

---

## §11. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-21 충돌 행렬 v1 발행. 결론: **PR-21a + PR-21b 완전 병렬 권장 (시나리오 A 1차)**. modify-shared 1건(CHANGELOG)만 — ADR 0050 슬롯 채번룰 네 번째 적용 + HTML 주석 anchor (PR-10·PR-11 효과 입증)로 충돌 0 보장. PR-9·PR-10·PR-11 결함 정정 패턴 정합 — 영역 분배 LOCK 강화로 add-add 0% 사전 검증. 발견된 함정 12건 명시 (특히 §10-1 widths 배열 mismatch, §10-11 domain Instrument.kt 영역 침범 risk, §10-12 V5 머지 순서 의존). 사용자 비판(KIS-first 정책 위반 — 14 컬럼 자체 추론) 응답 사이클. (architect, opus 4.7 1M) |
