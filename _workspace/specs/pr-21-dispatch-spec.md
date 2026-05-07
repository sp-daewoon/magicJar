# PR-21 sub-agent 분배 명세 (instrument KIS-first 본격 재설계)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `pr-21-instrument-kis-first-design.md` v1 + `pr-21-kis-header-raw-verification.md` v1 + ADR 0017·0039·0050
- 본 문서: orchestrator가 market-data·backend-core 디스패치 시 사용하는 작업 명세 + 작업 author/branch/task goal 초안.
- 충돌 행렬은 별도 문서 `_workspace/specs/pr-21-conflict-matrix.md` 참조.
- **본 PR-21은 ADR 0050 (CHANGELOG 슬롯 채번룰) 네 번째 적용 사이클** — PR-9 첫 적용 + PR-10 anchor 효과 입증 + PR-11 정합 적용 후 본 PR-21 정합 적용.

---

## §1. PR 시리즈 개요

| PR | 담당 | 분량 | 브랜치 | 우선순위 |
|---|---|---|---|---|
| **PR-21a** | market-data | M | `agent/market-data/pr-21-instrument-kis-first` | **병렬 1** |
| **PR-21b** | backend-core | M | `agent/backend-core/pr-21-instrument-jpa-expansion` | **병렬 1** |

**디스패치 모드**: **병렬** (충돌 행렬 정합 — `pr-21-conflict-matrix.md` §8 결론).

**인터페이스 동결 시점**: 본 spec 4종(`pr-21-instrument-kis-first-design.md` + `pr-21-kis-header-raw-verification.md` + `pr-21-dispatch-spec.md` + `pr-21-conflict-matrix.md` + `_workspace/backlog.md` 갱신)이 머지되는 즉시 — market-data·backend-core 양쪽이 design §3 (DB 스키마 LOCK 70+ 컬럼) + §4 (Flyway V5 LOCK SQL) + §5 (InstrumentJpaEntity 83 필드 LOCK) + §6 (KisInstrumentMasterAdapter 파싱 LOCK widths 배열) + 본 dispatch §3·§4 (영역 분배 LOCK + KIS-first 출처 인용 의무) 보고 동시 시작 가능.

**최고 관리자 메모리 정합**:
- `feedback_kis_first_policy.md` — 본 PR-21이 위반 정정 사이클. KIS open-trading-api raw fetch 검증 보고서 baseline (`pr-21-kis-header-raw-verification.md`)
- `feedback_v3_skepticism_no_regression.md` — fitness gap 91.7% 정량화 + Tier 1·2 자동매매 본체 적합성 검증
- `feedback_orchestrator_active_parallel.md` — modify-shared 1건(CHANGELOG)만 + ADR 0050 정합 → **완전 병렬 권장**

---

## §2. 룰 2 정합 사유 (단일 책임 — 본 PR-21은 직접 위반 0)

본 PR-21a·PR-21b는 양쪽 모두 단일 영역 — 룰 2 위반 없음:

| sub-agent | 영역 | 룰 2 평가 |
|---|---|---|
| PR-21a market-data | batch + consumer + domain(add-only enum) + api(Flyway 단독) | KIS 입력 layer + sync logic 단일 영역 — OK |
| PR-21b backend-core | api(JPA entity·Repository·Service·DTO) + domain(InstrumentRepository port modify-own) | API 출력 layer 단일 영역 — OK |

**frontend 영역 본 PR-21 비범위** — UI 변경 0(데이터 모델 보강이 본질). 사용자 watchlist row의 추가 메타 노출은 별도 사이클 (룰 2 정합).

---

## §3. market-data 위임 (PR-21a)

### 3-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | market-data (sonnet) |
| 브랜치 | `agent/market-data/pr-21-instrument-kis-first` |
| base | `main` (PR-20 머지된 상태 + 본 spec PR 머지 후) |
| 작업 author | `bot+market-data@magicjar.local` |
| 작업 author name | `magicjar-market-data` |
| Task 파일 goal | `PR-21a market-data — KIS 종목 마스터 본격 파싱 (KOSPI 70 widths + KOSDAQ 64 widths Python 1:1 정합) + Flyway V5 (70 컬럼 add-only ALTER + 8 CHECK + 8 인덱스) + InstrumentType enum 'DR' add + scrt_grp_cls_code 권위 매핑 + KisInstrumentRaw data class + InstrumentSyncJob upsert 70+ 필드 보강 + smoke 1건 (KIS-first 정책 위반 정정 + 자체 추론 0건 — pr-21-kis-header-raw-verification.md 직접 인용)` |
| Task 파일 deliverables | `api/src/main/resources/db/migration/V5__instrument_kis_first_expansion.sql + batch/.../infrastructure/kis/KisInstrumentMasterAdapter.kt(보강) + KisInstrumentRaw.kt(70+ 필드) + batch/.../job/instrument/InstrumentSyncJob.kt(upsert 보강) + domain/.../instrument/Instrument.kt(InstrumentType 'DR' enum add) + batch/.../persistence/BatchInstrumentJpaEntity.kt(write 필요 필드 보강) + consumer/.../persistence/ConsumerInstrumentJpaEntity.kt(read 필요 필드 보강) + smoke 1건` |
| 머지 정책 | rebase |
| 분량 | M |

### 3-2. 작업 범위 (Done 정의)

#### A. Flyway V5 마이그레이션 (api 모듈) ★

- [ ] `api/src/main/resources/db/migration/V5__instrument_kis_first_expansion.sql` — 신규
  - design §4-2 SQL 그대로 — 70 ALTER + 8 CHECK + 8 INDEX
  - design §4-2 주석 그대로 (KIS open-trading-api 출처 + raw 검증 보고서 path 인용)
  - **함정 §12-5 인지** — V5 번호 충돌 검증 (현재 V1·V2·V3·V4 가동 중 — V5 OK)
  - **modify-shared 위험 0**: V5는 신규 파일 (PR-21a 단독)

#### B. KisInstrumentMasterAdapter 본격 파싱 보강 (batch 모듈) ★★

- [ ] `batch/.../infrastructure/kis/KisInstrumentMasterAdapter.kt` — 보강
  - design §6-3 widths 배열 LOCK 그대로 — KOSPI 70 + KOSDAQ 64
  - parseLine, parseKospiPart2, parseKosdaqPart2 함수 분리
  - mapType(scrt_grp_cls, prst_cls) — design §7-2 권위 매핑 (자체 추론 폐기)
  - parseYyyymmdd 헬퍼 (00000000 검출)
  - **인라인 주석에 KIS-first 출처 명시 의무**:
    ```kotlin
    // KIS open-trading-api raw 검증 정합 — _workspace/specs/pr-21-kis-header-raw-verification.md
    // KOSPI Part2 widths (.h L9~L98 + kis_kospi_code_mst.py L54~L68 정합)
    // KOSDAQ Part2 widths (.h L9~L88 + kis_kosdaq_code_mst.py L54~L67 정합)
    ```
  - **함정 §12-1·§12-2·§12-3·§12-4 인지** — width 배열 mismatch·part1 가변·yyyymmdd "00000000"·roe 음수
  - 기존 detectType(name, code) 폐기 — mapType(scrt_grp_cls, prst_cls)로 정정

- [ ] `batch/.../infrastructure/kis/KisInstrumentRaw.kt` — 신규 (또는 KisInstrumentMasterAdapter 동일 파일에 inline)
  - 80+ 필드 data class
  - design §6-4 정합

#### C. InstrumentSyncJob upsert 보강 (batch 모듈)

- [ ] `batch/.../job/instrument/InstrumentSyncJob.kt` — 보강
  - design §6-5 upsertEntity() 형식
  - INSERT/UPDATE 시 모든 70+ 필드 매핑
  - DELIST 시 active=false, delisted_at=today 외 다른 필드 변경 0 (감사 가능성)
  - Kafka instrument.updated.v1 payload는 변경 0 (필드 추가 영향 없음 — 변경 종목 코드 list만)

#### D. domain 모듈 — InstrumentType enum 'DR' 추가 (add-only)

- [ ] `domain/.../instrument/Instrument.kt` — InstrumentType enum 보강
  ```kotlin
  enum class InstrumentType {
      STOCK, ETF, ETN, PREFERRED, WARRANT, REIT,
      DR,           // 신규 add-only
      UNKNOWN,
  }
  ```
  - **add-only enum value** — 기존 코드 호환

#### E. batch/consumer Entity 정합 (read-write 필요 필드만)

- [ ] `batch/.../persistence/BatchInstrumentJpaEntity.kt` — 보강
  - sync write에 필요한 70+ 필드 매핑 (api 모듈 InstrumentJpaEntity와 동일 매핑 — backend-core가 만든 것을 mirror)
  - **공유 entity 회피 결정**: 모듈 분리 정책상 entity 클래스 모듈마다 별도. 같은 테이블 매핑이라 컬럼명 정합 필수.
  - design §5-3 정합

- [ ] `consumer/.../persistence/ConsumerInstrumentJpaEntity.kt` — 보강 검토
  - consumer는 read 위주 (시그널 분기 등 필요 시) — 1차 비범위 가능
  - architect 권장: 본 PR-21에서 consumer entity 1차 변경 0 (필요 시 후속 PR — 시그널·전략 트랙 진입 시)

#### F. smoke 테스트 (1건)

- [ ] `batch/src/test/.../KisInstrumentMasterAdapterTest.kt` — 신규 또는 기존 보강
  - KOSPI 005930(삼성전자) byte 배열 mock + 모든 70+ 필드 검증
  - KOSDAQ 035720(카카오) byte 배열 mock + 모든 64+ 필드 검증
  - widths 합계 검증 (227·221) + part1 가변 + yyyymmdd 검증

### 3-3. KIS-first 정책 강화 (sub-agent 의무)

- [ ] **자체 추론 0건** — 모든 byte offset·widths·필드명은 `pr-21-kis-header-raw-verification.md` 직접 인용
- [ ] 코드 inline 주석에 KIS open-trading-api raw URL 명시:
  ```kotlin
  /**
   * KIS open-trading-api 종목 마스터 파싱.
   * raw 검증 출처: _workspace/specs/pr-21-kis-header-raw-verification.md
   * KOSPI: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/종목마스터정보(코스피).h
   * KOSDAQ: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/종목마스터정보(코스닥).h
   * Python: kis_kospi_code_mst.py·kis_kosdaq_code_mst.py field_specs widths
   */
  ```
- [ ] **MCP 사용 권장**: `mcp__kis-code-assistant__search_domestic_stock_api(query="종목 마스터")` 결과 commit message 또는 PR 본문에 인용

### 3-4. CHANGELOG slot row (ADR 0050 정합)

architect spec PR diff에 다음 row 사전 점유:

```markdown
<!-- pr-21-slot-market-data -->
| 2026-05-07 | **feat(batch+api+consumer): PR-21a instrument KIS-first 본격 파싱** — TBD (작성: market-data) | batch/api/consumer/domain | PR-21a instrument KIS-first |
```

sub-agent는 `(작성: market-data)` row 1줄만 in-place edit. design §1·§2·§3·§6 인용해서 산출 요약 작성.

---

## §4. backend-core 위임 (PR-21b)

### 4-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | backend-core (sonnet) |
| 브랜치 | `agent/backend-core/pr-21-instrument-jpa-expansion` |
| base | `main` (PR-20 머지된 상태 + 본 spec PR 머지 후) |
| 작업 author | `bot+backend-core@magicjar.local` |
| 작업 author name | `magicjar-backend-core` |
| Task 파일 goal | `PR-21b backend-core — InstrumentJpaEntity 83 필드 보강 (Tier 1·2·3·4 70 add-only) + InstrumentRepository 조회 메서드 보강 (findByActiveTrueAndTradingHaltFalse 등) + InstrumentSearchService 응답 보강 (type 분류 + 시총·매매 가드 노출) + WatchlistEntryDto 보강 (type·marketCapPrev·tradingHalt·managementStock·industryMedium 6 필드 추가) + SymbolMetaService 응답 보강 + InstrumentDto 보강 + smoke 1건` |
| Task 파일 deliverables | `api/.../persistence/InstrumentJpaEntity.kt(70 필드 보강) + api/.../persistence/InstrumentJpaRepository.kt(메서드 추가) + api/.../persistence/InstrumentRepositoryAdapter.kt(implement) + api/.../service/InstrumentSearchService.kt(응답 보강) + api/.../service/SymbolMetaService.kt(보강) + api/.../service/WatchlistService.kt(응답 보강) + api/.../controller/{InstrumentDto,WatchlistDto}.kt(필드 추가) + domain/.../instrument/port/InstrumentRepository.kt(메서드 시그니처 add) + smoke 1건` |
| 머지 정책 | rebase |
| 분량 | M |

### 4-2. 작업 범위 (Done 정의)

#### A. InstrumentJpaEntity 70 필드 보강 (api 모듈) ★★

- [ ] `api/.../persistence/InstrumentJpaEntity.kt` — 보강
  - design §5-2 entity 형식 그대로
  - 기존 14 필드 변경 0 (호환 보장)
  - 신규 70 필드 add-only — Tier 1(1) + Tier 2(17) + Tier 3(33) + Tier 4(19)
  - protected constructor 정합 유지

#### B. InstrumentRepository port 보강 (domain 모듈)

- [ ] `domain/.../instrument/port/InstrumentRepository.kt` — 메서드 시그니처 add
  ```kotlin
  // 신규 메서드 (add-only — 기존 메서드 변경 0)
  fun findActiveTradeable(): List<Instrument>  // tradingHalt=false AND liquidation=false AND active=true
  fun countByMarket(): Map<String, Long>       // KOSPI·KOSDAQ row count
  fun findByMarketCapDesc(limit: Int): List<Instrument>  // T-SC universe 시총 정렬
  fun findByKospi200(): List<Instrument>       // T-SC universe — KOSPI200
  ```
  - 모두 add-only

#### C. InstrumentRepositoryAdapter 보강 (api 모듈)

- [ ] `api/.../persistence/InstrumentRepositoryAdapter.kt` — implement
  - 위 add 메서드 4건 implement
  - 기존 메서드 변경 0

- [ ] `api/.../persistence/InstrumentJpaRepository.kt` — JPQL/native 쿼리 add
  - findActiveTradeable: `WHERE active = true AND trading_halt = false AND liquidation = false`
  - findByMarketCapDesc: `ORDER BY market_cap_prev DESC NULLS LAST LIMIT ?`

#### D. InstrumentSearchService 응답 보강

- [ ] `api/.../service/InstrumentSearchService.kt` — 응답 보강
  - InstrumentSearchResult에 type·marketCapPrev·tradingHalt 노출 (검색 결과에 위험 가드 표시)
  - **graceful**: 기존 검색 query 동작 변경 0 (필드 add-only)

#### E. SymbolMetaService 응답 보강

- [ ] `api/.../service/SymbolMetaService.kt` — 보강
  - GET /api/symbol/{code} 응답 schema에 type·marketCapPrev·tradingHalt·managementStock·industryMedium 추가
  - 기존 응답 필드 변경 0

#### F. WatchlistEntryDto + WatchlistService 응답 보강

- [ ] `api/.../controller/WatchlistDto.kt` — `WatchlistEntryDto` 필드 add
  ```kotlin
  data class WatchlistEntryDto(
      val code: String,
      val name: String?,         // PR-20 기존
      val market: String?,       // PR-20 기존
      // 신규 add-only
      val type: String?,         // STOCK·ETF·PREFERRED 등
      val marketCapPrev: Long?,  // 시총 (억)
      val tradingHalt: Boolean,
      val managementStock: Boolean,
      val industryMedium: String?,  // 업종 중분류
      // ... addedAt 등 기존 필드
  )
  ```

- [ ] `api/.../service/WatchlistService.kt` — list/add 응답 보강
  - InstrumentRepository로 entity 조회 → DTO 매핑 시 새 필드 채움
  - 기존 동작 변경 0 (graceful — instrument 부재 시 새 필드 모두 null/false)

#### G. InstrumentDto 보강

- [ ] `api/.../controller/InstrumentDto.kt` — `InstrumentDetailDto` 보강
  - GET /api/instruments/{code} 응답에 70+ 필드 노출 검토
  - **architect 결정**: Tier 1·2·3 핵심 필드만 노출 (Tier 4 재무는 후속). 약 30 필드 응답 schema.
  - 사용자 친화적 그룹화 — `tradingGuards{trading_halt·liquidation·...}`, `classification{is_kospi200·industry_medium·...}`, `marketMeta{base_price·prev_volume·...}`

#### H. smoke 테스트 (1건)

- [ ] `api/src/test/.../InstrumentRepositoryAdapterTest.kt` 또는 기존 보강
  - findActiveTradeable·findByMarketCapDesc 동작 검증
  - WatchlistService DTO 매핑 검증

### 4-3. CHANGELOG slot row (ADR 0050 정합)

```markdown
<!-- pr-21-slot-backend-core -->
| 2026-05-07 | **feat(api+domain): PR-21b instrument JPA entity 70 필드 보강 + Repository·DTO 응답 보강** — TBD (작성: backend-core) | api/domain | PR-21b instrument JPA 보강 |
```

sub-agent는 `(작성: backend-core)` row 1줄만 in-place edit.

---

## §5. 인터페이스 동결 LOCK

### 5-1. 영역 분배 LOCK ★ (PR-9 결함 정정 + PR-10·PR-11 정합)

| 영역 | sub-agent | 변경 파일 (대표) |
|---|---|---|
| `api/src/main/resources/db/migration/V5__*.sql` | **market-data** 단독 | V5 신규 |
| `api/.../persistence/InstrumentJpaEntity.kt` | **backend-core** 단독 | 70 필드 보강 |
| `api/.../persistence/InstrumentJpaRepository.kt` | **backend-core** 단독 | 메서드 add |
| `api/.../persistence/InstrumentRepositoryAdapter.kt` | **backend-core** 단독 | implement |
| `api/.../service/InstrumentSearchService.kt` | **backend-core** 단독 | 응답 보강 |
| `api/.../service/SymbolMetaService.kt` | **backend-core** 단독 | 응답 보강 |
| `api/.../service/WatchlistService.kt` | **backend-core** 단독 | DTO 매핑 |
| `api/.../controller/InstrumentDto.kt` | **backend-core** 단독 | DTO 추가 필드 |
| `api/.../controller/WatchlistDto.kt` | **backend-core** 단독 | DTO 추가 필드 |
| `domain/.../instrument/port/InstrumentRepository.kt` | **backend-core** 단독 | 메서드 시그니처 |
| `domain/.../instrument/Instrument.kt` (InstrumentType enum 'DR' add) | **market-data** 단독 | enum value add |
| `batch/.../infrastructure/kis/KisInstrumentMasterAdapter.kt` | **market-data** 단독 | 파싱 본격 보강 |
| `batch/.../infrastructure/kis/KisInstrumentRaw.kt` | **market-data** 단독 | 신규 또는 보강 |
| `batch/.../job/instrument/InstrumentSyncJob.kt` | **market-data** 단독 | upsert 보강 |
| `batch/.../persistence/BatchInstrumentJpaEntity.kt` | **market-data** 단독 | sync write 필요 필드 |
| `consumer/.../persistence/ConsumerInstrumentJpaEntity.kt` | (1차 비범위) | 변경 0 |

**add-add 0% 사전 검증**: PR-21a·PR-21b의 add 파일 list 사전 비교 — 동명 add 가능성 0% (모듈 분리 + 책임 분리).

**modify-shared 1건**: `CHANGELOG.md` (ADR 0050 슬롯 채번룰 + anchor 정합).

### 5-2. domain 모듈 충돌 회피 ★

`domain/.../instrument/Instrument.kt` 변경 분리:
- **market-data**: InstrumentType enum 'DR' 추가만 (1줄)
- **backend-core**: 변경 0

**hunk 분리**: 같은 파일이지만 enum value 1줄 추가 vs 다른 영역 변경 0 → git diff hunk가 자동 분리. PR-21a 단독 modify-own.

### 5-3. domain port modify 분리

- `domain/.../instrument/port/InstrumentRepository.kt` — backend-core 단독 (4 메서드 add)
- `domain/.../instrument/port/KisInstrumentMasterPort.kt` — 변경 0 (KIS port 시그니처는 그대로 — KisInstrumentRaw 도입은 batch 내부)

---

## §6. KIS-first 정책 강화 (sub-agent 의무) ★

### 6-1. 본 PR-21 KIS-first 의무 강화 사유

사용자 비판 (`feedback_kis_first_policy.md` 위반) 정정 사이클이라 sub-agent 모두 KIS-first raw fetch 의무 강화:

1. **architect (본 spec)**: KIS 헤더 + Python 파서 raw fetch + 검증 보고서 baseline (`pr-21-kis-header-raw-verification.md`)
2. **market-data (PR-21a)**: 코드 inline 주석에 raw URL + 검증 보고서 path 명시 의무 (§3-3)
3. **backend-core (PR-21b)**: 본 spec § 인용으로 자체 추론 0건 — JPA entity 컬럼명·길이·CHECK 모두 design §3·§5 LOCK 그대로 사용

### 6-2. KIS MCP 사용 권장

```
mcp__kis-code-assistant__search_domestic_stock_api(query="종목 마스터 코스피 코스닥")
```

결과를 commit message 또는 PR 본문에 인용 (자체 추론 0건 입증).

### 6-3. 인용 의무 검증

- [ ] PR 본문에 `pr-21-kis-header-raw-verification.md` 인용
- [ ] 코드 inline 주석에 raw URL (KOSPI/KOSDAQ `.h` + Python `.py`) 명시
- [ ] mapType()·widths 배열 등 KIS-first 결정 근거 명시

---

## §7. CHANGELOG 슬롯 row 사전 점유 (ADR 0050) ★

architect spec PR diff에 다음 4개 anchor + 3 row 사전 점유:

```markdown
<!-- pr-21-slot-architect -->
| 2026-05-07 | **docs(specs): PR-21 instrument KIS-first 본격 재설계 (사용자 비판 응답 + KIS 헤더 70+ 필드 spec)** — TBD (작성: architect) | docs/spec | PR-21 spec |
<!-- pr-21-slot-market-data -->
| 2026-05-07 | **feat(batch+api+consumer): PR-21a instrument KIS-first 본격 파싱** — TBD (작성: market-data) | batch/api/consumer/domain | PR-21a instrument KIS-first |
<!-- pr-21-slot-backend-core -->
| 2026-05-07 | **feat(api+domain): PR-21b instrument JPA entity 70 필드 보강** — TBD (작성: backend-core) | api/domain | PR-21b instrument JPA 보강 |
<!-- pr-21-slot-end -->
```

architect는 spec PR commit 시 본인 row를 채워서 발행 (`(작성: architect)` row 그대로 갱신). PR-21a·PR-21b sub-agent는 main 위에서 본인 row만 in-place edit.

**위치**: CHANGELOG.md 헤더 표 바로 아래 (현재 `<!-- pr-11-slot-architect -->` ~ `<!-- pr-11-slot-end -->`이 line 14~20에 있음 — 그 위에 prepend).

---

## §8. 권장 머지 순서

### 8-1. 시나리오 A — PR-21a 먼저 머지 → PR-21b 합류 (권장 ★)

```
T0: spec 5종 머지 (architect)
T0+5분: market-data 작업 시작 (worktree A)
T0+5분: backend-core 작업 시작 (worktree B)
T+3시간: market-data PR-21a 푸시 → CI green → 사용자 머지
T+3.5시간: backend-core rebase → PR-21b 푸시 → CI green → 통합 검증 → 머지
```

**왜 권장**:
- PR-21a Flyway V5가 먼저 적용되면 PR-21b InstrumentJpaEntity 70 컬럼 매핑이 V5 적용 위에서 컴파일 + 부팅 검증 가능
- PR-21b의 entity 컬럼명·길이는 V5 SQL과 1:1 정합 의무 — V5 먼저 머지가 안전

### 8-2. 시나리오 B — 동시 머지 (병렬 push)

영역 분리라 양쪽 동시 머지 가능. 단 **PR-21b가 먼저 머지될 위험**:
- V5 미적용 → InstrumentJpaEntity 70 컬럼 컴파일은 OK (Flyway는 부팅 시점)
- 단 부팅 시 entity 매핑 컬럼이 DB에 없음 → JPA validation fail (Hibernate `validate` 모드)
- 본 시스템은 `ddl-auto=validate` 추정 — 검증 의무

**처리**: 시나리오 A 강제 권장.

### 8-3. 시나리오 C — backend-core 먼저 (권장하지 않음)

- V5 미적용 시 부팅 실패 risk
- architect 권장 = 시나리오 A

### 8-4. 직렬은 권장하지 않음

`feedback_orchestrator_active_parallel.md` 정합 — modify-shared 1건(CHANGELOG)만 + ADR 0050으로 해소 → 병렬 권장 유지.

---

## §9. 운영 체크리스트 (오케스트라가 머지 시점 점검)

### 9-1. PR-21a (market-data) 머지 시점 (시나리오 A 우선)

- [ ] PR-21a CI green
- [ ] V5 SQL 70 ALTER + 8 CHECK + 8 INDEX 정합 (design §4-2 LOCK)
- [ ] KisInstrumentMasterAdapter widths 배열 LOCK 정합 (KOSPI 70 + KOSDAQ 64)
- [ ] 코드 inline 주석에 KIS raw URL 명시 확인
- [ ] InstrumentType enum 'DR' add-only
- [ ] BatchInstrumentJpaEntity 70+ 필드 매핑 (또는 read-only — sync에 필요한 핵심 필드)
- [ ] CHANGELOG slot row 채워짐 (`grep "(작성: market-data)" CHANGELOG.md` → TBD 아닌 실제 사유)
- [ ] PR-21b 작업자에게 "main rebase + force push" 안내

### 9-2. PR-21b (backend-core) 머지 시점

- [ ] PR-21b CI green
- [ ] InstrumentJpaEntity 70 필드 보강 (design §5-2 LOCK 정합)
- [ ] InstrumentRepository port 4 메서드 add
- [ ] WatchlistEntryDto 6 필드 add
- [ ] InstrumentDto 응답 schema 보강
- [ ] CHANGELOG slot row 채워짐
- [ ] 통합 검증 — V5 적용 후 entity 매핑 + Repository 조회 동작

### 9-3. 양쪽 머지 후 통합 검증

- [ ] `psql -c "\d instrument"` → 83 컬럼 + 8 인덱스 + 9 CHECK 정합
- [ ] `psql -c "SELECT COUNT(*) FROM instrument WHERE securities_group IS NOT NULL"` ≥ 2500 (sync 후)
- [ ] `psql -c "SELECT type, COUNT(*) FROM instrument GROUP BY type"` → STOCK·ETF·ETN·PREFERRED·REIT·DR 정확 분류
- [ ] `curl http://localhost:8080/api/watchlist` → 응답에 type·marketCapPrev·tradingHalt 노출 확인
- [ ] 사용자 dogfooding — Watchlist row에 매매 가드 표시 확인 (UI 변경은 별도 사이클)

### 9-4. 머지 후 정리 (ADR 0033 §결과)

- [ ] worktree 2개 unlock + remove
- [ ] 로컬 브랜치 2개 -d
- [ ] task.mjs archive 2건
- [ ] CHANGELOG 정합 확인 (4 row 모두 채워짐, TBD 잔재 0건)

---

## §10. 발견된 함정·위험 (sub-agent 인지 의무)

design §12 cross-ref:

1. **§12-1 widths 배열 mismatch** — KOSPI 70 vs KOSDAQ 64 분리 필수
2. **§12-2 part1 한글명 가변** — `lineBytes.size - part2Length - 21` 계산 정합
3. **§12-3 yyyymmdd "00000000"** — null 처리
4. **§12-4 roe 음수** — BigDecimal 변환 (음수 부호 안전)
5. **§12-5 V5 번호 충돌** — 현재 V1·V2·V3·V4만 — V5 OK
6. **§12-6 정적 시드 부족** — Tier 1만 채움 (mock 합성 0)
7. **§12-7 KIS 응답 변경 risk** — smoke test에 005930 시총·거래정지 등 알려진 값 검증
8. **§12-8 PR-11 spec 누락 흔적** — architect SKILL 강화 의무 (§16 ADR 보강 또는 SKILL 명시)
9. **§12-9 entity 3 모듈 정합** — api(전체) + batch(write 필요) + consumer(1차 비범위)

---

## §11. 사용자 메모리 정합 검증

| 사용자 메모리 | 본 PR-21 정합 |
|---|---|
| `feedback_kis_first_policy.md` (위반 정정 사이클 ★) | ✅ — KIS 헤더 raw fetch 보고서 baseline + sub-agent inline 주석 의무 |
| `feedback_v3_skepticism_no_regression.md` (fitness gap 의무) | ✅ — fitness gap 91.7% 정량 + Tier 1·2 자동매매 본체 적합성 |
| `feedback_orchestrator_active_parallel.md` (적극 병렬) | ✅ — 영역 분배 LOCK + add-add 0% + modify-shared 1건만 |
| `feedback_3tier_hierarchy.md` | ✅ — 오케스트라 → architect(설계) → market-data·backend-core(병렬 실행) |
| `feedback_autonomy.md` (Claude 자율) | ✅ — architect 자율 결정(트레이드오프 9건) + 사용자 결정 필요 0 |
| `feedback_no_mock_data.md` | ✅ — 정적 시드는 시스템 부팅 path만 (사용자 path는 KIS sync 결과 기준) |
| `feedback_qa_on_demand.md` (MVP QA 미적용) | ✅ — smoke 1+1건 + 사용자 dogfooding 수동 검증 |
| `feedback_spec_plan_scope.md` (다중 ADR 시 spec) | ✅ — KIS-first 정책 위반 정정 + 70 필드 보강 = spec 발행 정합 |
| `project_kis_key_management.md` | ✅ — 본 PR-21 키 관련 변경 0 |
| `feedback_orchestrator_nickname.md` | ✅ |

---

## §12. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-21 dispatch 명세 v1 발행. PR-21a market-data·PR-21b backend-core 분배 + 영역 LOCK 강화 (add-add 0% 사전 검증) + KIS-first 정책 강화 (raw fetch 보고서 직접 인용 의무) + 시나리오 A 권장 (V5 → entity 순). 사용자 비판(KIS 14 컬럼 자체 추론) 응답 사이클. (architect, opus 4.7 1M) |
