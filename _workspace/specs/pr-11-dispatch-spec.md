# PR-11 sub-agent 분배 명세

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `_workspace/specs/pr-11-im1-design.md` v1 + ADR 0017·0039·0037·0013·0050
- 본 문서: orchestrator가 market-data·backend-core 디스패치 시 사용하는 작업 명세 + 작업 author/branch/task goal 초안.
- 충돌 행렬은 별도 문서 `_workspace/specs/pr-11-conflict-matrix.md` 참조.
- **본 PR-11은 ADR 0050 (CHANGELOG 슬롯 채번룰) 세 번째 적용 사이클** — PR-9 첫 적용 + PR-10 anchor 효과 입증 후 정합 적용.

---

## §1. PR 시리즈 개요

| PR | 담당 | 분량 | 브랜치 | 우선순위 |
|---|---|---|---|---|
| **PR-11a** | market-data | M | `agent/market-data/pr-11-im1-market-data` | **병렬 1** |
| **PR-11b** | backend-core | M~L | `agent/backend-core/pr-11-im1-backend` | **병렬 1** |

**디스패치 모드**: **병렬** (충돌 행렬 정합 — `_workspace/specs/pr-11-conflict-matrix.md` §8 결론).

**인터페이스 동결 시점**: 본 spec 4종(`pr-11-im1-design.md` + 본 dispatch + 충돌 행렬 + backlog 갱신)이 머지되는 즉시 — market-data·backend-core 양쪽이 §3~§7(설계 spec) + 본 dispatch §3 (REST/Kafka/Redis/시드 시그니처) + §4 (인터페이스 LOCK) + §5 (CHANGELOG 슬롯) 보고 동시 시작 가능.

**최고 관리자 메모리 정합 — `feedback_orchestrator_active_parallel.md`**: architect "직렬 권장" 답습 금지. add-only 충돌은 진짜 병렬 가능. 본 PR-11은 충돌 행렬에서 modify-shared 후보 3건 (CHANGELOG·SymbolMetaService·InstrumentJpaEntity) 식별 → §4-1 영역 분배 LOCK 강화로 add-add 0% 사전 검증 + ADR 0050 anchor로 CHANGELOG 충돌 0 → **완전 병렬 권장**.

---

## §2. 룰 2 정합 사유 (frontend 영역도 backend-core 묶음)

본 PR-11b는 backend(api·domain) + frontend 영역 동시 — 룰 2 (단일 책임) 위반 가능성. **사유**:

| 요인 | 평가 |
|---|---|
| frontend 변경 범위 | ≤ 4 파일 (WatchlistAddForm 정정 + InstrumentSearchInput 신규 + axios 클라이언트 + hooks) — 소규모 |
| frontend sub-agent 호출 비용 | dispatch 비용 + worktree 비용 + CHANGELOG 슬롯 추가 |
| 인터페이스 결합도 | InstrumentSearchInput는 backend `/api/instruments/search` 응답 schema 직접 의존 — 한 sub-agent가 양쪽 동시 발행이 정합 검증 빠름 |
| 룰 2 PR 본문 사유 | "T-IM1 단일 트랙 — backend(검색 service) + frontend(자동완성 input)이 강하게 결합. 분리 시 양쪽 round-trip 비용 > 가치." (PR 본문에 명시 의무) |

**결정**: PR-11b backend-core가 frontend 영역도 함께. PR 본문에 사유 명시. **별도 frontend sub-agent 호출하지 않음**.

대안 검토 (rejected):
- (A) frontend 분리 (PR-11c 추가) — 3 sub-agent 디스패치. 비용 > 가치
- (B) frontend 정정을 별도 후속 PR — 본 PR-11에서는 backend만. 사용자 dogfooding이 자동완성 미동작으로 차단됨 → 1 사이클 지연 부담

---

## §3. market-data 위임 (PR-11a)

### 3-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | market-data (sonnet) |
| 브랜치 | `agent/market-data/pr-11-im1-market-data` |
| base | `main` (PR-10 머지된 상태 + 본 spec PR 머지 후) |
| 작업 author | `bot+market-data@magicjar.local` |
| 작업 author name | `magicjar-market-data` |
| Task 파일 goal | `T-IM1 market-data — KIS 종목 마스터 fetcher(KIS open-trading-api genfile_*.py KOSPI·KOSDAQ Kotlin port — KIS-first MCP 검증 의무) + InstrumentMasterSyncScheduler(13 cron @Scheduled KST + KRX 영업일 게이트 SimpleMarketSessionResolver) + InstrumentSyncJob(diff logic — INSERT/UPDATE/DELIST + Kafka instrument.updated.v1 publish + Redis invalidate) + 정적 시드(KOSPI 200·KOSDAQ 100·주요 ETF 20 JSON) + 시드 로더(@PostConstruct row=0 자동 INSERT) + KIS 실패 graceful + 동시 실행 락 + smoke 1건` |
| Task 파일 deliverables | `consumer/.../infrastructure/kis/KisInstrumentMasterAdapter.kt + KisInstrumentRaw.kt, batch/.../scheduler/InstrumentMasterSyncScheduler.kt + job/instrument/{InstrumentSyncJob,InstrumentSyncResult}.kt + bootstrap/InstrumentSeedRunner.kt + resources/instrument-seed.json + resources/db/migration/V4__instrument_master_search.sql, domain/.../instrument/{InstrumentAlias,InstrumentSearchResult,AliasSource}.kt + port/{KisInstrumentMasterPort, InstrumentSearchIndex}.kt, consumer/.../kafka/InstrumentUpdatedKafkaPublisher.kt, smoke test 1건` |
| 머지 정책 | rebase |
| 분량 | M |

### 3-2. 작업 범위 (Done 정의)

#### A. Flyway V4 마이그레이션 (api 모듈)

- [ ] `api/src/main/resources/db/migration/V4__instrument_master_search.sql` — 신규
  - `CREATE EXTENSION IF NOT EXISTS pg_trgm` (실패 시 fallback OK — 인덱스 생성 skip)
  - `ALTER TABLE instrument ADD COLUMN IF NOT EXISTS name_norm VARCHAR(80)`
  - `ALTER TABLE instrument ADD COLUMN IF NOT EXISTS theme VARCHAR(200)`
  - `CREATE INDEX IF NOT EXISTS idx_instrument_name_trgm ON instrument USING gin(name gin_trgm_ops)` (extension 미가용 시 본 인덱스 skip — sub-agent 검증)
  - `CREATE INDEX IF NOT EXISTS idx_instrument_code_prefix ON instrument (code text_pattern_ops)`
  - `CREATE TABLE IF NOT EXISTS instrument_alias (...)` — design §3-2 정합
  - `CREATE INDEX IF NOT EXISTS idx_instrument_alias_alias ON instrument_alias (alias text_pattern_ops)`
  - **함정 §11-2·§11-11 인지 의무** — pg_trgm extension 권한 검증
  - **modify-shared 위험 0**: V4는 신규 파일 (PR-11a 단독)

#### B. 도메인 신규 (domain 모듈 — add-only)

- [ ] `domain/.../instrument/InstrumentAlias.kt` — add-only
  ```kotlin
  data class InstrumentAlias(
      val code: String,
      val alias: String,
      val source: AliasSource,
  )
  enum class AliasSource { KRX, NAVER, FNGUIDE }
  ```

- [ ] `domain/.../instrument/InstrumentSearchResult.kt` — add-only
  ```kotlin
  data class InstrumentSearchResult(
      val code: String,
      val name: String,
      val market: InstrumentMarket,
      val sector: String?,
      val score: Double,
  )
  ```

- [ ] `domain/.../instrument/port/KisInstrumentMasterPort.kt` — add-only
  ```kotlin
  interface KisInstrumentMasterPort {
      /** KIS 종목 마스터 fetch — KOSPI + KOSDAQ + KONEX + ETF·ETN. 실패 시 throw. */
      fun fetchAll(): List<Instrument>
  }
  ```

- [ ] `domain/.../instrument/port/InstrumentSearchIndex.kt` — add-only (Redis 검색 인덱스 port)
  ```kotlin
  interface InstrumentSearchIndex {
      fun rebuild(instruments: List<Instrument>)
      fun invalidate()
      fun search(query: String, limit: Int, market: InstrumentMarket?): List<InstrumentSearchResult>
  }
  ```

#### C. KIS 종목 마스터 어댑터 (consumer 모듈)

- [ ] `consumer/.../infrastructure/kis/KisInstrumentMasterAdapter.kt` — 신규
  - `KisInstrumentMasterPort` implement
  - **KIS-first 정책 의무 ★** — endpoint·메시지 형식·필드 인덱스는 KIS open-trading-api repo 출처
    - `mcp__kis-code-assistant__search_domestic_stock_api(query="종목 마스터")` MCP 호출 의무
    - 후보: `genfile_kospi.py`·`genfile_kosdaq.py` Kotlin port (zip 다운 + fixed-width 파싱)
    - 또는 KIS REST `inquire-search-info` endpoint (TR_ID 미정 — MCP 검증)
  - 코드 인라인 주석에 **출처 URL/path 명시 의무** (예: `// out-trading-api: examples_llm/.../genfile_kospi.py`)
  - 자체 추론 0건
  - KisRateLimiter 통과 (PR-9b 가동 중 bean 의존)
  - 5xx → exponential backoff 3회 (1s/2s/4s), EGW00201 → 1초 + 1회 재시도
  - 실패 시 throw → InstrumentSyncJob이 catch + log WARN + 시드 fallback 유지

- [ ] `consumer/.../infrastructure/kis/KisInstrumentRaw.kt` (또는 inline) — KIS raw 응답 dto. domain `Instrument`로 매핑

#### D. Sync Scheduler + Job (batch 모듈)

- [ ] `batch/.../scheduler/InstrumentMasterSyncScheduler.kt` — 신규
  - 13 `@Scheduled(cron, zone="Asia/Seoul")` 메서드 — design §5-2 정합
  - 모두 `runIfBusinessDay(cronTag)` 호출
  - `MarketSessionResolver` 의존 (PR-9b가 만든 `SimpleMarketSessionResolver` 재사용) — 휴장일 skip + log
  - `MAGICJAR_INSTRUMENT_SYNC_AT_BOOT=true`이면 `ApplicationRunner`가 부팅 직후 1회 sync (옵션 — design §5-6)

- [ ] `batch/.../job/instrument/InstrumentSyncJob.kt` — 신규
  - `KisInstrumentMasterPort.fetchAll()` 호출
  - existing active codes (DB SELECT)
  - diff: NEW · UPDATED · DELISTED
  - DB transaction:
    - INSERT NEW (active=true)
    - UPDATE UPDATED (name·sector·theme·name_norm)
    - UPDATE DELISTED (delisted_at=today, active=false) — DELETE 금지 ★
    - INSERT instrument_alias (KRX source — 종목명 default 1건/종목)
  - Kafka `instrument.updated.v1` publish (changes list)
  - Redis invalidate (`instrument:list:*`·`instrument:search:*`·변경 코드별 `instrument:by-code:{code}`)
  - Redis `instrument:sync:lastAt` SET = 현재 시각
  - 동시 실행 lock — Redis `SET instrument:sync:running true NX EX 600` (10분)
  - log: `[instrument-sync] cron=... fetched=... inserted=... updated=... delisted=... elapsed=...s`

- [ ] `batch/.../job/instrument/InstrumentSyncResult.kt` — sync 결과 dto

- [ ] `batch/.../bootstrap/InstrumentSeedRunner.kt` — `@PostConstruct` 또는 `ApplicationRunner`
  - DB `instrument` row count == 0 확인
  - 0이면 `instrument-seed.json` 로드 + JPA upsertAll
  - 로그: `[instrument-seed] 시드 INSERT 완료 — KOSPI {n}, KOSDAQ {n}, ETF {n}`

#### E. 정적 시드 (batch 모듈 resources)

- [ ] `batch/src/main/resources/instrument-seed.json` — KOSPI 200 + KOSDAQ 100 + 주요 ETF 20
  - 형식: design §5-4 정합
  - 시드 source 신뢰 가능 (KRX 공시 KOSPI 200·KOSDAQ 100 구성종목 — 기준일 명시)
  - 매번 갱신 의무 없음 — 첫 부팅 fallback 전용
  - 인라인 주석 또는 별도 README에 시드 source·기준일 명시

#### F. Kafka publisher (consumer 모듈)

- [ ] `consumer/.../kafka/InstrumentUpdatedKafkaPublisher.kt` — `instrument.updated.v1` topic publisher
  - payload: `{changes: [{code, action: "INSERT"|"UPDATE"|"DELIST", market, name}, ...], syncedAt: ISO8601}`
  - partition key = market

- [ ] `consumer/.../config/KafkaTopicConfig.kt` 보강 — `instrument.updated.v1` topic 등록 (1 partition, retention 7d)
  - **modify-shared 위험 ★**: PR-9b·PR-10b가 만든 KafkaTopicConfig — market-data 본인 영역 보강 (충돌 0)

#### G. 도메인 보강 (옵션 — domain Instrument 그대로 유지)

- [ ] **domain Instrument 변경 0** — design §3-3 architect 결정. JPA 컬럼만 추가, domain은 simplicity 우선

#### H. 검증 게이트 통과
spec §11 함정 §11-1·§11-2·§11-9·§11-11 인지 + DoD 1·4·5·6·7·9·15·16 통과.

#### I. smoke test 1건

- [ ] `batch/src/test/kotlin/.../InstrumentSyncJobTest.kt` — KisInstrumentMasterPort mock + diff logic + delisted 처리 + Kafka publish 검증

### 3-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(domain): InstrumentAlias + InstrumentSearchResult + KisInstrumentMasterPort + InstrumentSearchIndex (T-IM1)` | `domain/.../instrument/{InstrumentAlias,InstrumentSearchResult}.kt + port/*.kt` |
| 2 | `feat(api): Flyway V4 — instrument_alias + name_norm + theme + gin_trgm + code prefix index` | `api/src/main/resources/db/migration/V4__instrument_master_search.sql` |
| 3 | `feat(consumer): KisInstrumentMasterAdapter (KIS open-trading-api genfile_*.py — KIS-first MCP 검증)` | `consumer/.../infrastructure/kis/{KisInstrumentMasterAdapter,KisInstrumentRaw}.kt` |
| 4 | `feat(consumer): InstrumentUpdatedKafkaPublisher + instrument.updated.v1 topic 등록` | `consumer/.../kafka/InstrumentUpdatedKafkaPublisher.kt + config/KafkaTopicConfig.kt` |
| 5 | `feat(batch): InstrumentSyncJob (diff logic + DELIST 보존 + Kafka publish + Redis invalidate)` | `batch/.../job/instrument/{InstrumentSyncJob,InstrumentSyncResult}.kt` |
| 6 | `feat(batch): InstrumentMasterSyncScheduler (ADR 0039 13 cron + 영업일 게이트 + boot sync 옵션)` | `batch/.../scheduler/InstrumentMasterSyncScheduler.kt` |
| 7 | `feat(batch): InstrumentSeedRunner + KOSPI 200·KOSDAQ 100·ETF 20 정적 시드 JSON` | `batch/.../bootstrap/InstrumentSeedRunner.kt + resources/instrument-seed.json` |
| 8 | `test(batch): InstrumentSyncJob smoke 1건` | test 파일 |
| 9 | `chore(changelog): PR-11a market-data slot row 채움 (ADR 0050)` | `CHANGELOG.md` 1줄 |

### 3-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
backend (consumer·batch·domain·api migration) — Instrument Master 본격 sync (T-IM1)

## Summary
- PR-11a: T-IM1 market-data — KIS 종목 마스터 fetcher(KisInstrumentMasterAdapter — KIS open-trading-api genfile_*.py Kotlin port — KIS-first MCP 검증) + InstrumentMasterSyncScheduler(ADR 0039 13 cron @Scheduled KST + KRX 영업일 게이트) + InstrumentSyncJob(diff logic — INSERT/UPDATE/DELIST + Kafka instrument.updated.v1 publish + Redis invalidate + 동시 실행 락) + 정적 시드 KOSPI 200·KOSDAQ 100·주요 ETF 20 JSON + InstrumentSeedRunner(@PostConstruct row=0 자동 INSERT) + Flyway V4(instrument_alias + name_norm + theme + gin_trgm + code prefix index) + 도메인(InstrumentAlias·InstrumentSearchResult + 2 port) + smoke 1건.
- ADR 0017·0039·0037 정합. backend-core는 PR-11b가 동시 진행 (검색 REST·Redis 인덱스·frontend 자동완성).
- 정합: `_workspace/specs/pr-11-im1-design.md` + `pr-11-dispatch-spec.md` + `pr-11-conflict-matrix.md`.

## 신규 ADR
없음 — ADR 0017·0039 정합으로 충분.

## Scope
- domain: InstrumentAlias + InstrumentSearchResult + 2 port (add-only)
- api: V4 Flyway 마이그레이션 (add-only)
- consumer: KisInstrumentMasterAdapter + InstrumentUpdatedKafkaPublisher + KafkaTopicConfig 보강 (modify-own)
- batch: 신규 영역 본격 (Scheduler + 2 Job + Seed)
- frontend: 변경 0

## 사용자 검증 방법
1. PR-11 spec PR 머지 + main 위
2. PR-11b backend-core 머지 (검색 REST + Redis 인덱스 + frontend 자동완성)
3. `MAGICJAR_INSTRUMENT_SYNC_AT_BOOT=true scripts/build-and-deploy.sh`
4. `psql -c "SELECT COUNT(*) FROM instrument WHERE market != 'UNKNOWN'"` → 2500+ row
5. `docker logs magicjar-batch-app | grep instrument-sync` → cron 실행 log

## 정합 ADR
- 0017 — Instrument Master 1급 시민 (1차 본격 채움)
- 0039 — Cron 13 시각 (본 PR 본격 구현)
- 0037 — KIS Rate Limit (sync 호출 시)
- 0050 — CHANGELOG 슬롯 채번룰 세 번째 적용 (in-place edit + anchor)
```

### 3-5. market-data 의존성·정합 의무

- **ADR 0017 §2-2** — 상장폐지 보존 의무 (DELETE 금지 — `delisted_at` UPDATE만)
- **ADR 0039 §2-1** — 13 cron 시각 LOCK
- **ADR 0039 §2-2** — 영업일 게이트 의무 + KST timezone 명시
- **ADR 0037** — KIS Rate Limiter 통과 의무
- **ADR 0050** — CHANGELOG slot row in-place edit + anchor 정합 (PR-10 패턴 입증)
- **룰 5 (AI 호출 자제)**: spring-ai/anthropic/openai SDK import 0건
- **룰 4 (테스트 마지막 일괄)**: smoke 1건만

**KIS-first 의무 (강조 ★★)**: 
- KIS 종목 마스터 endpoint·메시지 형식·필드 인덱스는 **KIS open-trading-api repo 출처**
- `mcp__kis-code-assistant__search_domestic_stock_api(query="종목 마스터")` MCP 호출 의무
- 코드 인라인 주석에 출처 URL/path 명시 의무
- 자체 추론 0건 — 추론 시 호가 데이터 깨짐 risk (spec §11-1 정합)

---

## §4. backend-core 위임 (PR-11b)

### 4-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | backend-core (sonnet) |
| 브랜치 | `agent/backend-core/pr-11-im1-backend` |
| base | `main` (PR-10 머지된 상태 + 본 spec PR 머지 후) |
| 작업 author | `bot+backend-core@magicjar.local` |
| 작업 author name | `magicjar-backend-core` |
| Task 파일 goal | `T-IM1 backend-core — InstrumentController(GET /api/instruments/search·{code}·universe + POST sync trigger 옵션) + InstrumentSearchService(Redis 1순위 + DB pg_trgm fallback + 한글 정규화 + 2자 prefix bucket) + InstrumentSearchIndexAdapter(Redis sorted set + 1일 TTL + lazy 재구성) + InstrumentJpaEntity 보강(name_norm·theme) + InstrumentAliasJpaEntity·Repository + SymbolMetaService 정정(instrument 테이블 1순위) + frontend InstrumentSearchInput + WatchlistAddForm 자동완성 전환 + axios 클라이언트 + hooks + smoke 3건` |
| Task 파일 deliverables | `api/.../controller/InstrumentController.kt + service/InstrumentSearchService.kt + persistence/{InstrumentJpaEntity 보강, InstrumentAliasJpaEntity, InstrumentAliasJpaRepository}.kt + infrastructure/redis/InstrumentSearchIndexAdapter.kt + service/SymbolMetaService 정정, domain/.../instrument/port/InstrumentRepository 메서드 추가(findByActiveTrue·findActiveByCodeIn·markDelisted), frontend/src/components/InstrumentSearchInput.tsx + api/instrument.ts + api/hooks/useInstrumentSearch.ts + pages/Watchlist/WatchlistAddForm.tsx 정정, smoke test 3건 (api 2 + frontend 1)` |
| 머지 정책 | rebase |
| 분량 | M~L |

### 4-2. 작업 범위 (Done 정의)

#### A. JPA 엔티티 보강 (api 모듈)

- [ ] `api/.../persistence/InstrumentJpaEntity.kt` — 보강 (modify-own)
  - 컬럼 추가: `name_norm: String?`, `theme: String?` (nullable)
  - protected constructor 정합 (PR-7 발행 패턴 그대로)
  - **modify-shared 위험 1**: PR-7 발행 파일 modify — backend-core 본인 영역 (충돌 0 — PR-11a market-data는 미진입)

- [ ] `api/.../persistence/InstrumentAliasJpaEntity.kt` — 신규 add-only
  ```kotlin
  @Entity
  @Table(name = "instrument_alias")
  @IdClass(InstrumentAliasId::class)
  class InstrumentAliasJpaEntity(
      @Id @Column(name = "code") val code: String,
      @Id @Column(name = "alias") val alias: String,
      @Id @Column(name = "source") val source: String,
  )
  ```

- [ ] `api/.../persistence/InstrumentAliasJpaRepository.kt` — 신규 add-only
- [ ] `api/.../persistence/InstrumentJpaRepository.kt` — 보강:
  - `findByActiveTrue(): List<InstrumentJpaEntity>`
  - `findByActiveTrueAndMarket(market: String): List<InstrumentJpaEntity>`
  - `@Query` native pg_trgm fallback 검색 메서드
  - `markDelisted(code: String, delistedAt: LocalDate)` (custom @Modifying)

#### B. domain port 보강 (domain 모듈 — modify-own)

- [ ] `domain/.../instrument/port/InstrumentRepository.kt` 보강:
  - `findByActiveTrue(): List<Instrument>`
  - `findActiveByCodeIn(codes: Collection<String>): List<Instrument>`
  - `markDelisted(code: String, delistedAt: LocalDate)`
  - `searchByPgTrgm(query: String, limit: Int, market: InstrumentMarket?): List<InstrumentSearchResult>` (DB fallback)
  - **modify-shared 위험 2**: PR-7 발행 파일 modify — backend-core 본인 영역 (PR-11a는 add-only port만 신규)

- [ ] `domain/.../instrument/port/InstrumentSearchIndex.kt` (PR-11a가 발행) — backend-core가 implement만

- [ ] `api/.../persistence/InstrumentRepositoryAdapter.kt` 보강 — 추가 메서드 implement

#### C. 검색 service + Redis adapter (api 모듈)

- [ ] `api/.../service/InstrumentSearchService.kt` — 신규
  - input: `query: String, limit: Int, market: InstrumentMarket?`
  - 정규화: 공백·점 제거 + lowercase (design §6-5)
  - Redis 1순위 (`InstrumentSearchIndex`)
  - Redis miss → DB pg_trgm fallback (`InstrumentRepository.searchByPgTrgm`)
  - timeout: Redis 100ms, DB 1s
  - 결과 score 정렬 + limit 적용
  - response → `InstrumentSearchResult`

- [ ] `api/.../infrastructure/redis/InstrumentSearchIndexAdapter.kt` — 신규
  - `InstrumentSearchIndex` (domain port) implement
  - Redis 키 매트릭스 design §6-1 정합
  - sorted set + 2자 prefix bucket (design §6-2)
  - rebuild: 부팅 시 + sync 후
  - invalidate: sync 후 전체 DEL
  - search: ZRANGEBYLEX + GET pipeline

#### D. REST controller (api 모듈)

- [ ] `api/.../controller/InstrumentController.kt` — 신규
  - `GET /api/instruments/search?q=&limit=&market=` — 자동완성·검색
  - `GET /api/instruments/{code}` — 단일 조회
  - `GET /api/instruments?market=&active=&limit=&offset=` — universe (옵션)
  - `POST /api/instruments/sync` — 수동 trigger (옵션 — JobLauncher 호출 또는 InstrumentSyncJob.run() 직접 호출)
  - OnboardingGuardFilter 통과 (PR-6 자동)
  - Swagger 4단 어노테이션 (사용자 메모리 `feedback_swagger_pattern.md` 정합 — Tag·Operation·Parameter·ApiResponses + 한국어 + example)
  - 응답 dto: `InstrumentSearchResponseDto`·`InstrumentDetailDto`·`InstrumentUniverseResponseDto`

#### E. SymbolMetaService 정정 (api 모듈 — PR-10b 정합)

- [ ] `api/.../service/SymbolMetaService.kt` — 보강 (modify-own)
  - instrument 테이블 1순위 read (design §11-8 정합)
  - 응답 SymbolDto의 name·market·industry는 instrument 우선
  - instrument 부재 또는 market='UNKNOWN'이면 KIS inquire-price industry fallback
  - **modify-shared 위험 3**: PR-10b 발행 파일 modify — backend-core 본인 영역 (PR-11a 미진입)

#### F. domain 보강 (필요 시 — modify-own)

- [ ] domain Instrument 변경 0 (architect 결정 — design §3-3 정합)

#### G. frontend 자동완성 (frontend 영역 — 룰 2 단일 PR 사유 §2 명시)

- [ ] `frontend/src/components/InstrumentSearchInput.tsx` — 신규
  - props: `value`·`onSelect`·`disabled`·`placeholder`
  - debounce 200ms
  - useInstrumentSearch hook 호출
  - dropdown 표시 (3+ 결과 가시성)
  - 한글 IME composition 처리 (design §11-6 — `compositionstart`/`compositionend`)
  - keyboard navigation (Up/Down/Enter)
  - 클릭 또는 Enter → onSelect callback
  - graceful: search 5xx → "검색 일시 불가능" 표시 + raw input fallback toggle

- [ ] `frontend/src/api/instrument.ts` — 신규
  - DTO 정의 + axios 호출
  - `searchInstruments(q, limit, market?): Promise<InstrumentSearchResultDto[]>`

- [ ] `frontend/src/api/hooks/useInstrumentSearch.ts` — 신규
  - useQuery + queryKey + staleTime: 60_000

- [ ] `frontend/src/pages/Watchlist/WatchlistAddForm.tsx` — 정정 (modify-own)
  - PR-7 옵션 C 6자리 직접 입력 → 자동완성 전환
  - graceful raw input fallback (axios 5xx 누적 3회 시)
  - **modify-shared 위험 4**: PR-7b 발행 파일 modify — backend-core 본인 영역 (frontend sub-agent 미디스패치)

#### H. 검증 게이트 통과
spec §11 함정 §11-3·§11-4·§11-6·§11-8·§11-12 인지 + DoD 1·2·3·8·10·11·12·13·14 통과.

#### I. smoke test 3건

- [ ] `api/src/test/kotlin/.../InstrumentSearchServiceTest.kt` — Redis mock + DB fallback 시나리오
- [ ] `api/src/test/kotlin/.../InstrumentControllerTest.kt` — `GET /api/instruments/search?q=삼성` mock service 응답 (PR-10 SymbolControllerTest mock 패턴 정합 — `@MockBean UserAccountJpaRepository.existsAny()=true`로 OnboardingGuardFilter 통과)
- [ ] `frontend/src/components/__tests__/InstrumentSearchInput.test.tsx` — vitest mock useInstrumentSearch → dropdown 렌더링 + onSelect callback 검증

### 4-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(domain): InstrumentRepository 보강 (findByActiveTrue·markDelisted·searchByPgTrgm)` | `domain/.../instrument/port/InstrumentRepository.kt` |
| 2 | `feat(api): InstrumentJpaEntity 보강 (name_norm·theme) + InstrumentAliasJpaEntity·Repository 신규` | `api/.../persistence/{InstrumentJpaEntity,InstrumentAliasJpaEntity,InstrumentAliasJpaRepository,InstrumentJpaRepository,InstrumentRepositoryAdapter}.kt` |
| 3 | `feat(api): InstrumentSearchIndexAdapter (Redis sorted set + 2자 prefix bucket + 1일 TTL)` | `api/.../infrastructure/redis/InstrumentSearchIndexAdapter.kt` |
| 4 | `feat(api): InstrumentSearchService (Redis 1순위 + DB pg_trgm fallback + 한글 정규화)` | `api/.../service/InstrumentSearchService.kt` |
| 5 | `feat(api): InstrumentController 4 endpoint (search·by-code·universe·sync trigger) + Swagger` | `api/.../controller/InstrumentController.kt` + DTO |
| 6 | `feat(api): SymbolMetaService 정정 — instrument 테이블 1순위 (PR-10 graceful 폐기)` | `api/.../service/SymbolMetaService.kt` |
| 7 | `feat(frontend): instrument API 클라이언트 + useInstrumentSearch hook` | `frontend/src/api/instrument.ts + hooks/useInstrumentSearch.ts` |
| 8 | `feat(frontend): InstrumentSearchInput (자동완성 + IME 처리 + 키보드 네비)` | `frontend/src/components/InstrumentSearchInput.tsx` |
| 9 | `feat(frontend): WatchlistAddForm 자동완성 전환 + raw input graceful fallback` | `frontend/src/pages/Watchlist/WatchlistAddForm.tsx` |
| 10 | `test(api+frontend): InstrumentSearchService·Controller·SearchInput smoke 3건` | test 파일 |
| 11 | `chore(changelog): PR-11b backend-core slot row 채움 (ADR 0050)` | `CHANGELOG.md` 1줄 |

### 4-4. PR 본문 템플릿

```markdown
## 본 PR 영향 영역
backend (api·domain) + frontend — Instrument 검색·자동완성 (T-IM1)

## Summary
- PR-11b: T-IM1 backend-core — InstrumentController 4 endpoint(GET search·by-code·universe + POST sync trigger) + InstrumentSearchService(Redis 1순위 + DB pg_trgm fallback + 한글 정규화 + 2자 prefix bucket) + InstrumentSearchIndexAdapter(Redis sorted set + 1일 TTL + lazy 재구성) + InstrumentJpaEntity 보강(name_norm·theme) + InstrumentAliasJpaEntity·Repository + InstrumentRepository port 보강(findByActiveTrue·markDelisted·searchByPgTrgm) + SymbolMetaService 정정(instrument 1순위 — PR-10 graceful 폐기) + frontend InstrumentSearchInput(자동완성 + IME 처리 + 키보드 네비) + axios 클라이언트 + useInstrumentSearch hook + WatchlistAddForm 자동완성 전환(graceful raw input fallback) + smoke 3건.
- ADR 0017·0050 정합. market-data는 PR-11a가 동시 진행 (Flyway V4 + KIS sync + Cron + 시드).

## 룰 2 단일 책임 사유
T-IM1 단일 트랙 — backend(검색 service) + frontend(자동완성 input)이 강하게 결합. InstrumentSearchInput은 backend `/api/instruments/search` 응답 schema 직접 의존. 분리 시 양쪽 round-trip 비용 > 가치. 한 sub-agent 묶음으로 인터페이스 정합 검증 빠름.

## 신규 ADR
없음.

## Scope
- domain: InstrumentRepository 보강 (modify-own)
- api: 신규 영역 본격 (Controller·SearchService·Redis adapter·Alias persistence·SymbolMetaService 정정)
- frontend: 신규 컴포넌트(InstrumentSearchInput) + 자동완성 전환(WatchlistAddForm) + axios·hook

## 사용자 검증 방법
1. PR-11 spec PR 머지 + main 위
2. PR-11a market-data 머지 (Flyway V4 + 시드 + Cron)
3. `MAGICJAR_INSTRUMENT_SYNC_AT_BOOT=true scripts/build-and-deploy.sh`
4. `curl 'http://localhost:8080/api/instruments/search?q=삼성&limit=10'`
5. http://localhost:3000 → /watchlist → "삼성" 입력 → dropdown 검증
6. row 클릭 → /symbol/005930 → 헤더 "삼성전자 · KOSPI · 전기전자" 확인

## 정합 ADR
- 0017 — Instrument Master 1급 시민 (검색 인덱스 본격)
- 0050 — CHANGELOG 슬롯 채번룰 세 번째 적용
```

### 4-5. backend-core 의존성·정합 의무

- **ADR 0017 §2-4** — Redis 검색 인덱스 매트릭스 (search prefix·list·by-code) 정합
- **ADR 0050** — CHANGELOG slot row in-place edit + anchor 정합
- **룰 2 (단일 책임)**: PR 본문에 사유 명시 의무 (frontend + backend 함께)
- **룰 4 (테스트 마지막 일괄)**: smoke 3건 (api 2 + frontend 1)
- **룰 5 (AI 호출 자제)**: spring-ai/anthropic/openai SDK import 0건. frontend AI 라이브러리 import 0건

**SymbolMetaService 정정 의무 ★** (design §11-8):
- PR-10b 발행 SymbolMetaService에 instrument 테이블 우선 read 추가
- 응답 SymbolDto의 name·market·industry는 instrument 우선
- instrument 부재 시 KIS inquire-price industry fallback (graceful 그대로)

**한글 IME composition 의무 ★** (design §11-6):
- InstrumentSearchInput은 `compositionstart`·`compositionend` 이벤트 핸들러 의무
- IME composition 진행 중에는 axios 호출 차단

---

## §5. 양쪽 공유 의존 (인터페이스 동결 시점)

본 spec 머지 즉시 양쪽이 다음을 바라보고 동시 시작:

### 5-1. 영역 분배 LOCK ★ (PR-9 add-add 결함 정정 정합 — backlog §5)

| 영역·디렉토리 | PR-11a (market-data) | PR-11b (backend-core) | 충돌 risk |
|---|---|---|---|
| `domain/.../instrument/InstrumentAlias.kt` | **신규 (단독)** | 변경 0 | **0%** |
| `domain/.../instrument/InstrumentSearchResult.kt` | **신규 (단독)** | 변경 0 | **0%** |
| `domain/.../instrument/port/KisInstrumentMasterPort.kt` | **신규 (단독)** | 변경 0 | **0%** |
| `domain/.../instrument/port/InstrumentSearchIndex.kt` | **신규 (단독)** | 변경 0 | **0%** |
| `domain/.../instrument/port/InstrumentRepository.kt` | 변경 0 | **modify (메서드 추가)** | **0%** (backend-core 본인 영역) |
| `domain/.../instrument/Instrument.kt` | **변경 0** | **변경 0** | **0%** (architect 결정 — domain simplicity) |
| `api/src/main/resources/db/migration/V4__*.sql` | **신규 (단독)** | 변경 0 | **0%** |
| `api/.../persistence/InstrumentJpaEntity.kt` | 변경 0 | **modify (name_norm·theme 추가)** | **0%** (backend-core 본인 영역) |
| `api/.../persistence/InstrumentJpaRepository.kt` | 변경 0 | **modify (메서드 추가)** | **0%** |
| `api/.../persistence/InstrumentRepositoryAdapter.kt` | 변경 0 | **modify (메서드 implement 추가)** | **0%** |
| `api/.../persistence/InstrumentAliasJpaEntity.kt` | 변경 0 | **신규** | **0%** |
| `api/.../persistence/InstrumentAliasJpaRepository.kt` | 변경 0 | **신규** | **0%** |
| `api/.../controller/InstrumentController.kt` | 변경 0 | **신규** | **0%** |
| `api/.../service/InstrumentSearchService.kt` | 변경 0 | **신규** | **0%** |
| `api/.../service/SymbolMetaService.kt` | 변경 0 | **modify (instrument 1순위)** | **0%** (PR-10b 발행 파일 — backend-core 본인 영역, PR-11a 미진입) |
| `api/.../infrastructure/redis/InstrumentSearchIndexAdapter.kt` | 변경 0 | **신규** | **0%** |
| `consumer/.../infrastructure/kis/KisInstrumentMasterAdapter.kt` | **신규 (단독)** | 변경 0 | **0%** |
| `consumer/.../infrastructure/kis/KisInstrumentRaw.kt` | **신규 (단독)** | 변경 0 | **0%** |
| `consumer/.../kafka/InstrumentUpdatedKafkaPublisher.kt` | **신규 (단독)** | 변경 0 | **0%** |
| `consumer/.../config/KafkaTopicConfig.kt` | **modify (instrument.updated.v1 추가)** | 변경 0 | **0%** (PR-9b·PR-10b 만든 파일 — market-data 본인 영역) |
| `batch/.../scheduler/InstrumentMasterSyncScheduler.kt` | **신규 (단독)** | 변경 0 | **0%** |
| `batch/.../job/instrument/{InstrumentSyncJob,InstrumentSyncResult}.kt` | **신규 (단독)** | 변경 0 | **0%** |
| `batch/.../bootstrap/InstrumentSeedRunner.kt` | **신규 (단독)** | 변경 0 | **0%** |
| `batch/src/main/resources/instrument-seed.json` | **신규 (단독)** | 변경 0 | **0%** |
| `frontend/src/components/InstrumentSearchInput.tsx` | 변경 0 | **신규** | **0%** |
| `frontend/src/api/instrument.ts` | 변경 0 | **신규** | **0%** |
| `frontend/src/api/hooks/useInstrumentSearch.ts` | 변경 0 | **신규** | **0%** |
| `frontend/src/pages/Watchlist/WatchlistAddForm.tsx` | 변경 0 | **modify (자동완성 전환)** | **0%** (PR-7b 발행 파일 — backend-core 본인 영역, frontend sub-agent 미디스패치) |
| `consumer/src/main/resources/application.yml` | (보강 0) | (보강 0) | **0%** — KIS sync는 KisRateLimiter bean 의존 (보강 0) |
| `batch/src/main/resources/application.yml` | (보강 권장 — 정적 시드 path) | 변경 0 | **0%** (market-data 단독 — batch 모듈) |
| `api/src/main/resources/application.yml` | 변경 0 | (보강 0) | **0%** |
| `CHANGELOG.md` | **modify (slot row in-place edit)** | **modify (slot row in-place edit)** | **ADR 0050 in-place edit + anchor 정합 — 충돌 0 보장** |

**양 sub-agent 사전 비교 → add-add 가능성 0% 검증**:
- ✅ market-data는 batch/consumer/domain(add-only)만, frontend·api(controller/service) 0
- ✅ backend-core는 api/domain(modify-own)·frontend만, batch·consumer 0
- ✅ CHANGELOG는 ADR 0050 슬롯 채번룰 in-place edit (PR-10 anchor 효과 입증)
- ✅ application.yml은 다른 모듈 (batch vs api) — 같은 파일 수정 0
- ✅ consumer 모듈 보강(KafkaTopicConfig)은 market-data 본인 영역 — backend-core 영역 미진입

**결론**: PR-9 add-add 충돌 결함은 본 PR-11에서 **0건 발생 가능 (사전 영역 분배 LOCK)**.

### 5-2. domain port 시그니처 (LOCK)

| port | 시그니처 | 위치 | 누가 만드는가 |
|---|---|---|---|
| `KisInstrumentMasterPort` 신규 | `fetchAll(): List<Instrument>` | domain | **PR-11a 단독** add-only |
| `InstrumentSearchIndex` 신규 | `rebuild`·`invalidate`·`search` | domain | **PR-11a 단독** add-only (port) — **PR-11b implement** |
| `InstrumentRepository` 보강 | 4 메서드 추가 (`findByActiveTrue`·`findActiveByCodeIn`·`markDelisted`·`searchByPgTrgm`) | domain | **PR-11b 단독** modify-own |
| `InstrumentAlias`·`InstrumentSearchResult`·`AliasSource` 신규 | data class · enum | domain | **PR-11a 단독** add-only |

### 5-3. REST endpoint 시그니처 (LOCK — design.md §4 정합)

| Method | path | 응답 | 누가 구현 |
|---|---|---|---|
| GET | `/api/instruments/search?q=&limit=&market=` | InstrumentSearchResponseDto | **PR-11b api 단독** |
| GET | `/api/instruments/{code}` | InstrumentDetailDto | **PR-11b api 단독** |
| GET | `/api/instruments?market=&active=&limit=&offset=` | InstrumentUniverseResponseDto | **PR-11b api 단독** |
| POST | `/api/instruments/sync` (옵션) | `{jobId, triggered}` | **PR-11b api 단독** |

### 5-4. Kafka topic 시그니처 (LOCK)

| topic | producer | consumer | payload | 누가 추가 |
|---|---|---|---|---|
| `instrument.updated.v1` | batch InstrumentSyncJob → consumer InstrumentUpdatedKafkaPublisher (본 PR-11a 신규) | (1차 비범위 — STOMP fanout는 후속) | `{changes: [{code, action, market, name}, ...], syncedAt}` | **신규 양쪽 (market-data 단독)** — KafkaTopicConfig 보강 |

### 5-5. Redis 키 매트릭스 (LOCK — design.md §6-1 정합)

| 키 | TTL | 작성자 | 읽기 | 형식 |
|---|---|---|---|---|
| `instrument:by-code:{code}` | 1일 | PR-11b InstrumentSearchService 또는 sync 후 SET | PR-11b InstrumentController | JSON |
| `instrument:list:{market}` | 1일 | PR-11b lazy 재구성 | PR-11b InstrumentController | JSON List<code> |
| `instrument:search:prefix:{2자prefix}` | 1일 | PR-11b lazy 재구성 | PR-11b InstrumentSearchService | sorted set (member=code, score=match) |
| `instrument:sync:running` | 600s lock | **PR-11a InstrumentSyncJob** (Redis SET NX) | **PR-11a 자체** | String "true" |
| `instrument:sync:lastAt` | 1일 | **PR-11a InstrumentSyncJob** | (보조 — sync 진행 표시) | ISO8601 |

### 5-6. Flyway V4 schema (LOCK — design.md §3-2 정합)

PR-11a가 V4 발행. 다음 컬럼·테이블·인덱스 신규:
- `instrument.name_norm` VARCHAR(80) NULL
- `instrument.theme` VARCHAR(200) NULL
- `idx_instrument_name_trgm` (gin · gin_trgm_ops)
- `idx_instrument_code_prefix` (text_pattern_ops)
- `instrument_alias` 테이블 + alias prefix 인덱스
- `pg_trgm` extension

**PR-11b는 V4 적용 후 InstrumentJpaEntity 컬럼 보강 + Repository 활용**.

### 5-7. 예시 페이로드

#### REST GET /api/instruments/search?q=삼성전&limit=10
```json
{
  "query": "삼성전",
  "results": [
    {"code":"005930","name":"삼성전자","market":"KOSPI","sector":"전기전자","score":1.0},
    {"code":"009150","name":"삼성전기","market":"KOSPI","sector":"전기전자","score":0.95}
  ],
  "totalMatched": 2,
  "limit": 10
}
```

#### REST GET /api/instruments/005930
```json
{
  "code":"005930","isin":"KR7005930003","name":"삼성전자","nameEn":"Samsung Electronics Co., Ltd.",
  "market":"KOSPI","type":"STOCK","sector":"전기전자","theme":"반도체,스마트폰,IT",
  "listedAt":"1975-06-11","delistedAt":null,"active":true,
  "aliases":[{"alias":"삼성전자","source":"KRX"}]
}
```

#### Kafka instrument.updated.v1
```json
{
  "syncedAt":"2026-05-07T11:00:00Z",
  "changes":[
    {"code":"002830","action":"DELIST","market":"KOSDAQ","name":"제일전기"},
    {"code":"099999","action":"INSERT","market":"KOSPI","name":"신규상장종목A"}
  ]
}
```

### 5-8. SoT 변경 시 처리

본 spec(`pr-11-im1-design.md` + 본 dispatch + `pr-11-conflict-matrix.md` + backlog 갱신)이 머지된 후 양쪽 sub-agent 진행 중에 시그니처 변경 필요 시:
1. sub-agent 즉시 중단
2. orchestrator 호출 → architect 디스패치
3. architect가 spec 갱신 → 양쪽 sub-agent 재개

본 PR-11 작업 중엔 spec 변경 0건 의무.

---

## §6. CHANGELOG 슬롯 채번룰 세 번째 적용 (ADR 0050 — PR-10 anchor 효과 입증)

본 spec PR이 ADR 0050 세 번째 적용. **PR-10 HTML 주석 anchor 시도가 CHANGELOG conflict 0건으로 입증** → PR-11도 동일 패턴 정합 적용.

### 6-1. 슬롯 row 정확 위치

`CHANGELOG.md` 현재 구조 (PR-10 hotfix #204 머지 후):
- Line 1: `# magicJar CHANGELOG`
- Line 2: 빈 줄
- Line 3·4: 표 header
- Line 5~9: PR-10 anchor 4건 + slot row 3건 (현재 첫 데이터 영역)
- Line 10~: PR-9 spec row 등 (이전 row들)

본 spec PR이 prepend할 위치 = **Line 5 (현재 PR-10 첫 anchor 위) — 새 PR-11 anchor 4건 + slot row 3건 prepend**.

### 6-2. 슬롯 row 형식 (3건 발행 + anchor 4건 — PR-10 패턴)

본 spec PR diff에서 prepend되는 정확 형식:

```markdown
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
<!-- pr-11-slot-architect -->
| 2026-05-07 | **docs(specs): PR-11 T-IM1 설계 + sub-agent 분배 + 충돌 행렬 + CHANGELOG 슬롯 (W1 잔여 진입)** — `_workspace/specs/pr-11-im1-design.md` (V4 신설 LOCK·REST 4 endpoint LOCK·Cron 13 LOCK·Redis 검색 인덱스 LOCK·frontend 자동완성 통합 LOCK·정적 시드 KOSPI 200·KOSDAQ 100 LOCK·상장폐지 보존 LOCK·KIS-first 강조), `pr-11-dispatch-spec.md` (PR-11a market-data·PR-11b backend-core 분배 + 룰 2 사유 명시 + 영역 분배 LOCK ★ — add-add 0% 검증), `pr-11-conflict-matrix.md` (**완전 병렬 권장** — modify-shared 1건 CHANGELOG는 ADR 0050 in-place edit + anchor 정합 — PR-10 효과 입증). `_workspace/backlog.md` 갱신 (§6 dogfooding 보류 명시·§7 W1 잔여 진입 정정·§11 SymbolControllerTest mock 별도 PR 권장). (architect) | docs/backlog | PR-11 T-IM1 설계 산출물 + W1 잔여 진입 |
<!-- pr-11-slot-market-data -->
| 2026-05-07 | **TBD: PR-11a T-IM1 market-data** (작성: market-data) — TBD | batch/consumer/domain | TBD |
<!-- pr-11-slot-backend-core -->
| 2026-05-07 | **TBD: PR-11b T-IM1 backend-core** (작성: backend-core) — TBD | api/domain/frontend | TBD |
<!-- pr-11-slot-end -->
```

### 6-3. sub-agent in-place edit 의무

PR-11a market-data (sub-agent):
1. main rebase (architect spec PR 머지 후)
2. `CHANGELOG.md` 열어 `<!-- pr-11-slot-market-data -->` anchor + 그 다음 row 1줄 검색
3. **그 row 1줄만** in-place edit — `TBD: PR-11a T-IM1 market-data (작성: market-data) — TBD` → 실제 산출물 한 줄 사유로 교체:
   ```markdown
   | 2026-05-07 | **feat(bootstrap): PR-11a T-IM1 market-data** — KisInstrumentMasterAdapter(KIS open-trading-api genfile_*.py — KIS-first MCP 검증) + InstrumentMasterSyncScheduler(ADR 0039 13 cron @Scheduled KST + KRX 영업일 게이트) + InstrumentSyncJob(diff INSERT/UPDATE/DELIST + Kafka instrument.updated.v1 publish + Redis invalidate + 동시 실행 락) + 정적 시드 KOSPI 200·KOSDAQ 100·ETF 20 JSON + InstrumentSeedRunner + Flyway V4(instrument_alias·name_norm·theme·gin_trgm) + 도메인(InstrumentAlias·InstrumentSearchResult·KisInstrumentMasterPort·InstrumentSearchIndex) + smoke 1건. ADR 0017·0039·0037 정합. (market-data) | batch/consumer/domain/api | PR-11a T-IM1 market-data |
   ```
4. **다른 row(architect spec row·anchor line·backend-core slot row) 절대 미수정** — 영역 침범 금지
5. anchor line 자체는 미수정
6. commit + push + PR + 머지

PR-11b backend-core (sub-agent):
1. main rebase
2. `<!-- pr-11-slot-backend-core -->` anchor 검색
3. `TBD: PR-11b T-IM1 backend-core (작성: backend-core) — TBD` → 실제 산출물 사유로 교체:
   ```markdown
   | 2026-05-07 | **feat(api+frontend): PR-11b T-IM1 backend-core** — InstrumentController 4 endpoint(GET search·by-code·universe + POST sync trigger) + InstrumentSearchService(Redis 1순위 + DB pg_trgm fallback + 한글 정규화) + InstrumentSearchIndexAdapter(Redis sorted set + 2자 prefix bucket + 1일 TTL + lazy 재구성) + InstrumentJpaEntity 보강(name_norm·theme) + InstrumentAliasJpaEntity·Repository + InstrumentRepository port 보강 + SymbolMetaService 정정(instrument 1순위 — PR-10 graceful 폐기) + frontend InstrumentSearchInput(자동완성 + IME 처리 + 키보드 네비) + axios + useInstrumentSearch hook + WatchlistAddForm 자동완성 전환(graceful raw input fallback) + smoke 3건. ADR 0017·0050 정합. 룰 2 단일 책임 사유 PR 본문 명시. (backend-core) | api/domain/frontend | PR-11b T-IM1 backend-core |
   ```
4. **다른 row 절대 미수정**
5. commit + push + PR + 머지

### 6-4. 일탈·fallback 처리 (ADR 0050 §2-4 정합)

sub-agent가 spec PR 슬롯 row 외 추가 변경 발생 시:
- 별도 정정 commit으로 본인 row 갱신 OR 새 row append (add-only) — 본인 책임
- add-only 충돌 발생 가능 — 둘째 머지자가 자체 rebase 해결

병렬 sub-agent 머지 순서:
- market-data 먼저 머지 → market-data row 채워짐 → backend-core slot row 그대로 + backend-core fillout 후 머지 → CHANGELOG 충돌 0 (anchor line + ADR 0050 §3-2 + PR-10 effect 정합)
- 동시 push 시점이라도 in-place edit이라 git이 다른 line 변경으로 인식 → 충돌 0

### 6-5. orchestrator 운영 절차

orchestrator가 market-data·backend-core 디스패치 시 다음 정확 위치 정보 전달:

> `CHANGELOG.md`에 `<!-- pr-11-slot-market-data -->` anchor 다음 row 또는 `<!-- pr-11-slot-backend-core -->` anchor 다음 row 1줄이 `TBD ... (작성: <agent>)` 형식으로 있다. 본인 row만 in-place edit. 형식 예시 = dispatch-spec §6-3.

### 6-6. 검증 의무

본 PR-11이 ADR 0050 세 번째 적용 + PR-10 anchor 효과 입증 정합. 검증 항목:
- spec PR diff에 anchor 4건 + slot row 3건 추가됨 확인
- sub-agent PR diff에 anchor 미수정 + 본인 row 1줄만 수정 확인
- 두 sub-agent 머지 시 CHANGELOG conflict 0건 확인 (PR-10 입증 정합)

---

## §7. 운영·실패 대응

### 7-1. market-data가 PR-11a 분량 초과 인지 시

PR-11a를 다음 단위로 분할 가능 (orchestrator 사전 동의 필요):
- PR-11a-1: Flyway V4 + 도메인 + 정적 시드 + InstrumentSeedRunner (최소 부팅 가능)
- PR-11a-2: KisInstrumentMasterAdapter + InstrumentSyncJob + Kafka publish
- PR-11a-3: InstrumentMasterSyncScheduler 13 cron + boot sync 옵션

PR-11b는 PR-11a-1만 머지되어도 진행 가능 (V4 적용 후 작업 시작).

### 7-2. market-data가 KIS 종목 마스터 endpoint 자체 추론 위험 시

- `kis-code-assistant` MCP `mcp__kis-code-assistant__search_domestic_stock_api(query="종목 마스터")` 의무
- KIS open-trading-api repo `genfile_kospi.py`·`genfile_kosdaq.py` 또는 동등 reference 검토
- architect 호출 → spec §5-5 정밀화 (endpoint·tr_id 명시 추가)
- 자체 추론 0건 — KIS-first 정책

### 7-3. PostgreSQL pg_trgm extension 미가용 시 (design §11-2·§11-11)

V4 마이그레이션 실패 시:
- (옵션 A) `CREATE EXTENSION` 라인을 별도 try-catch (Flyway는 SQL 실패 시 마이그레이션 abort — 옵션 A는 직접 적용 불가)
- (옵션 B 권장) `idx_instrument_name_trgm`만 skip — V4를 두 단계로 분할: V4(컬럼·테이블·일반 인덱스) + V4_1(pg_trgm 인덱스 시도, 실패 OK)
- 또는 docker-compose `postgres` `init.sql`에서 사전 `CREATE EXTENSION pg_trgm` 실행 (사전 권한 부여)

architect 결정: V4 단일 마이그레이션 + `CREATE EXTENSION IF NOT EXISTS pg_trgm` 시도 → 실패 시 sub-agent 호출 → 사후 조치 결정 (init.sql 보강 또는 V4 분할).

### 7-4. backend-core가 InstrumentSearchInput IME 처리 어려움 시

- `compositionstart`·`compositionend` 이벤트 핸들러 React 표준 정합
- 1차는 단순 (composition 중 차단)만 — 향후 분음/typo 처리는 post-MVP

### 7-5. CI fail 시

market-data·backend-core 본인 영역 fail → 본인 PR에서 수정.

### 7-6. ADR 정합 누락 발견 시

직접 ADR 변경 금지 — architect 호출 → 별도 PR로 정정.

### 7-7. PR-11a가 PR-11b 미머지 시 진행 가능?

가능 — domain port·Flyway V4·시드 모두 add-only. PR-11b는 V4 적용 후 작업 시작 가능 → **권장 머지 순서**: PR-11a 먼저 머지 → PR-11b rebase 후 머지.

### 7-8. PR-11b가 PR-11a 미머지 시 진행 가능?

부분 가능 — frontend InstrumentSearchInput는 mock 응답으로 자체 검증 가능. backend는 Flyway V4 컬럼 의존이라 PR-11a 머지 필요. 권장 = PR-11a 먼저.

### 7-9. 사용자 dogfooding 결과 흡수 (PR-11 머지 후 — backlog §6 정합)

본 PR-11 머지 후 사용자 dogfooding 재개 가능 (자동완성 가시성 ↑). 발견 결함은 backlog §6 sub-section 추가 → 다음 사이클 architect 디스패치.

---

## §8. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-11 dispatch-spec v1 발행. PR-11a market-data·PR-11b backend-core 병렬 분배 LOCK + 영역 분배 LOCK 강화 (PR-9 add-add 결함 정정 정합 — add-add 0% 검증) + 룰 2 단일 책임 사유 명시(frontend 영역도 backend-core가 함께 — 인터페이스 결합도) + ADR 0050 슬롯 채번룰 세 번째 적용(anchor 정합 PR-10 효과 입증) + KIS-first 의무 강조 (KIS 종목 마스터 fetcher 코드에 KIS open-trading-api genfile_*.py 출처 + kis-code-assistant MCP 사용 의무). (architect) |
