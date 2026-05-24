# W4 P2 — NoopMarketIndexSnapshotReader 단독 활성 root cause 진단

- 일자: 2026-05-24
- dispatcher: orchestrator (W4 P2 dispatch)
- 진단 영역: market-data (api 모듈 NoopReader · batch 모듈 cron · domain port 정합)
- 산출: root cause 결정 + 분기 옵션 + 자체 hotfix 가능성 평가
- qa P1 evidence 출처: `_workspace/qa/reports/2026-05-24-w4-p1-ai-mcp-evidence.md`

---

## 0. 결론 1줄

**root cause = (A) R1 미발행 — 그러나 더 정확하게는 (A')**: R1 market-data PR #677은 `MarketIndexSnapshotRepository` (write port) impl만 발행. 그러나 api 모듈의 `TrendQueryService` · `SectorRankingService`가 의존하는 port는 별개의 `MarketIndexSnapshotReader` (read port). **두 port가 분리된 다른 인터페이스** → write impl이 발행돼도 read port impl은 영원히 미발행 상태 → NoopReader가 `@ConditionalOnMissingBean` 폴백으로 단독 활성.

추가로 (C) **batch 컨테이너 미실행** root cause 동반 — write impl이 정상 발행됐어도 1분 cron 자체가 실행되지 않아 `market_index_snapshot` 표 0건. read adapter 가 발행되더라도 데이터 0건 상태.

---

## 1. evidence 매트릭스

### 1-1. 두 개의 분리된 인터페이스 존재 (root cause 핵심)

| port | package | 의존 모듈 | impl 존재 |
|---|---|---|---|
| `MarketIndexSnapshotReader` (**read**) | `domain.trend` | api (`TrendQueryService` · `SectorRankingService`) | **NoopMarketIndexSnapshotReader 단독** (api 모듈) |
| `MarketIndexSnapshotRepository` (**write**) | `domain.market.index.port` | batch (`MarketIndexSyncJob` · `MarketIndexDailyCloseJob`) | `MarketIndexSnapshotRepositoryAdapter` (batch 모듈) |

도메인 클래스도 분리:
- `domain.trend.MarketIndexSnapshot` — read port용 (api 모듈)
- `domain.market.index.MarketIndexSnapshot` — write port용 (batch 모듈)

각각 read/write 분리 의도 — read는 ports & adapters 패턴, write는 batch 발행 영속.

### 1-2. read port + Noop impl 발행 PR

```
20748c1c magicjar-backend-core feat(backend-core): W4 R1 — T-MARKET Phase 2 (market-trend-engine 모듈 + TrendPreference + REST 6 + V51)
```

→ backend-core가 read port `MarketIndexSnapshotReader`와 `NoopMarketIndexSnapshotReader` 둘 다 발행. 단, **read adapter 발행 spec dispatch 0** — backend-core spec(`MarketIndexSnapshotReader.kt:11~13` KDoc)에 "R1 market-data 머지 시 자동 교체"라 명시했으나 R1 market-data PR #677은 write port 영역만 처리.

### 1-3. write impl PR (PR #677) 산출 매트릭스

PR #677 (mergedAt 2026-05-24T06:30:00Z, MERGED) — `feat(market-data): W4 R1 — T-MARKET Phase 1 (KIS adapter 5 + cron Job 2 + V47·V48 + INGESTION_LOW)`:

- domain layer: `MarketIndexSnapshot` (write용 도메인) + 3 port (`KisIndexFetcher` · `MarketIndexSnapshotRepository` · `SectorIndexSnapshotRepository`) — **모두 write 영역**
- batch layer: 5 KIS adapter + 2 Job + 2 JPA Entity + 2 JPA Repository + 2 RepositoryAdapter — **모두 write/persistence**
- Flyway V47 · V48 — 표 생성
- **read port·read adapter 발행 0건** (`grep "MarketIndexSnapshotReader" PR 변경 파일 = 0건`)

→ PR #677 spec scope = write/persistence 단독. read adapter는 별도 dispatch가 필요했으나 누락 = **dispatch gap**.

### 1-4. api 모듈 의존 그래프 → batch impl 흡수 불가

```
api/build.gradle.kts dependencies:
  - implementation(project(":domain"))
  - implementation(project(":pattern-engine"))
  - implementation(project(":indicator-impl"))
  - implementation(project(":strategy-engine"))
  - implementation(project(":persistence-shared"))
  - implementation(project(":market-trend-engine"))
  → batch 모듈 의존 0
```

→ batch 모듈의 `MarketIndexSnapshotRepositoryAdapter` Component scan 대상 외 — 설령 read port를 구현했더라도 api ApplicationContext에 들어갈 수 없음. **모듈 경계 자체가 단독 root cause 변수**.

### 1-5. `@ConditionalOnMissingBean` 정합 검증

```kotlin
// api/.../NoopMarketIndexSnapshotReader.kt:20~22
@Component
@ConditionalOnMissingBean(MarketIndexSnapshotReader::class, ignored = [NoopMarketIndexSnapshotReader::class])
class NoopMarketIndexSnapshotReader : MarketIndexSnapshotReader { ... }
```

→ Bean Conditional 자체는 **정합 설계** (다른 Bean 활성 시 자동 비활성 + 자기 자신 ignored 명시 — 무한 재귀 차단). 따라서 root cause (B) Bean Conditional 충돌 아님.

NoopReader 코드 4 method 모두 빈 list/null 반환:
```kotlin
override fun findLatestIndex(code: String): MarketIndexSnapshot? = null
override fun findLatestIndexes(codes: List<String>): List<MarketIndexSnapshot> = emptyList()
override fun findLatestSectorSnapshots(): List<SectorSnapshot> = emptyList()
override fun findPreviousSectorSnapshots(): List<SectorSnapshot>? = null
```

### 1-6. 1분 cron 실행 evidence (root cause C — 동반 문제)

```
docker ps | grep magicjar:
  magicjar-api       Up 30 minutes (healthy)
  magicjar-frontend  Up About an hour (healthy)
  magicjar-redis     Up 4 days (healthy)
  magicjar-postgres  Up 4 days (healthy)
  magicjar-kafka     Up 4 days (healthy)
  magicjar-kafka-ui  Up 4 days (healthy)
  magicjar-dozzle    Up 4 days

→ batch · consumer 컨테이너 미실행
```

```
docker exec magicjar-postgres psql ... -c "SELECT count(*) FROM market_index_snapshot;"
 count
-------
     0
(1 row)

docker exec magicjar-postgres psql ... -c "SELECT count(*) FROM sector_index_snapshot;"
 count
-------
     0
(1 row)
```

→ V47·V48 표는 정상 생성 (`\d market_index_snapshot` 컬럼·인덱스·UQ 매트릭스 정합), 단지 데이터 0건. 1분 cron `MarketIndexSyncJob`이 batch 모듈에 정의됐으나 batch 컨테이너 미실행 → 실행 0회 → 표 0건.

### 1-7. P1 evidence "NoopReader fallback 데이터" 정정

qa P1 보고서가 MCP `get_market_trend` 응답을 "NoopReader fallback 데이터"로 표현했으나, **NoopReader는 mock 데이터 합성 0** (feedback_no_mock_data 정합) — 빈 list/null만 반환. 따라서 MCP 응답이 "비어 있음" 이지 "mock 데이터" 아님. NoopReader fallback = `dataInsufficient=true` flag + 빈 indexes — frontend "데이터 없음" 표시 의도 (`MarketIndexController.snapshot()` Swagger 4단 명시).

→ P1 evidence는 root cause 후보 (A) 본질을 정확히 짚었음. 본 P2 진단으로 (A')로 정밀화 + (C) 동반 발견.

---

## 2. 분기 결정

P2 dispatch 분기 매트릭스 (orchestrator 매뉴얼 §2 정합):

| 분기 옵션 | 검증 결과 |
|---|---|
| (A) R1 미발행 | **TRUE** — R1 market-data PR #677은 write port만 발행 (read port impl 0) |
| (A') read/write port 분리 + dispatch gap | **TRUE** — 두 port가 분리된 인터페이스이므로 단순 "R1 amend"가 아니라 신규 read adapter dispatch 필요 |
| (B) Bean Conditional 충돌 | FALSE — `@ConditionalOnMissingBean(... ignored = [self])` 정합 |
| (C) batch 컨테이너 미실행 (동반) | **TRUE** — read adapter 발행돼도 데이터 0건 상태 (운영 단계 cron 실행 의무 동반) |

### 2-1. hotfix 영역 결정

본 결함 처리에 필요한 작업:

| # | 작업 | 영역 | 작업량 |
|---|---|---|---|
| H1 | api 모듈에 `MarketIndexSnapshotReadAdapter` 신설 (`MarketIndexSnapshotReader` impl) | market-data | adapter 1 + JpaEntity 1 + JpaRepository 1 + SectorIndex 동일 set + test = 7~10 파일 |
| H2 | batch 컨테이너 기동 (`docker compose up -d batch consumer`) | sre/ops (backend-core) | 0 코드 + 운영 절차 |
| H3 | read adapter dispatch spec 추가 (architect ADR amend) | architect | ADR 0100 §2-7 amend (R1 read adapter 영역 정의 명시) |

### 2-2. hotfix 분기 선택 — escalation 의제

H1 자체는 **market-data 영역 단독** + JPA Entity 재발행 패턴 (api 모듈 자체 entity 정합 — `DisclosureJpaEntity`·`MarketCalendarJpaEntity`·`UserAccountJpaEntity` 등 다수 사례). 영역 LOCK 위반 0.

단, 다음 의무 동반:
- **architect spec amend** 동반 의무 (ADR 0100 §2-7에 read adapter 영역 정의 부재 → 후속 sub-agent도 같은 dispatch gap 재발 risk)
- **KIS-first 무관** (read-only JPA 쿼리, KIS endpoint 호출 0)
- **본 영역 spec 정합 검증** — `MarketIndexSnapshotReader.findPreviousSectorSnapshots()` 의미 (전일 영업일 cutoff 기준)이 KDoc만으로 ambiguous → architect 확인 의무

→ **자체 hotfix 본문 발행은 architect spec confirm 후 분기 권장**. 본 P2 dispatch는 진단 + 의제 발행으로 완료 + orchestrator escalation으로 분기.

---

## 3. orchestrator escalation 의제

1. **architect dispatch — ADR 0100 §2-7 amend** (R1 영역 매트릭스에 read adapter 영역 정의 명시 · read adapter 발행 PR 신설 의무 · `MarketIndexSnapshotReader.findPreviousSectorSnapshots()` "전일 영업일 cutoff" 규약 LOCK)
2. **market-data dispatch — read adapter 신설 hotfix PR** (architect spec amend 정합 후 dispatch · 7~10 파일 영역 단독 변경)
3. **운영 dispatch — batch · consumer 컨테이너 기동** (`docker compose -f docker/docker-compose.yml --env-file ... up -d batch consumer` · 1분 cron 첫 발사 후 `market_index_snapshot` 표 row 적재 evidence 확인 의무)

---

## 4. 본 영역 hotfix 비범위

본 P2 dispatch 비범위 항목 (다른 P2 dispatch 처리):
- `ai_comment_log` 컬럼 P0 — ai-engineer dispatch
- `TrendPreference 400` (maPeriods 1~5 validation 위반) + `backtest 422` — backend-core P1 dispatch
- ADR 갱신 자체 — architect dispatch

---

## 5. 4 lens self-check

- **세심함**: 두 port + 두 도메인 클래스 분리 + batch/api 모듈 의존 그래프 + `@ConditionalOnMissingBean` ignored self 검증 + 컨테이너 실행 evidence 4 layer 모두 다룸
- **영향도**: read adapter 발행 시 API endpoint 4건 (`GET /api/market/index/snapshot`·`/{code}/trend` + `/api/market/sector/snapshot`·`/ranking`) 자본 path 영향 0 — read-only · KIS Rate Limit 점유 0 (DB 쿼리만)
- **연결**: PR #677 산출물과 1:1 매핑 — V47·V48 표 read 쿼리 (write 쪽 entity·repository는 batch 모듈이라 별도 발행 의무) + `domain.trend.MarketIndexSnapshot` ↔ V47 `market_index_snapshot` row 1:1 변환 + `SectorSnapshot` ↔ V48 1:1
- **확장성**: 향후 NoopReader는 mock mode 또는 graceful fallback path 보존 — `@ConditionalOnMissingBean` 패턴이 이미 충분. dev/test 환경에서 `magicjar.market.index.sync.enabled=false` 시 적용 path

---

## 6. 자체 hotfix 발행 결정

본 P2 진단 dispatch는 **진단 + escalation 의제 발행으로 완료**.

자체 hotfix 본문 미발행 사유:
1. architect spec confirm 필요 (`findPreviousSectorSnapshots()` 규약 ambiguous)
2. ADR 0100 §2-7 amend 동반 의무 (재발 방지)
3. batch 컨테이너 기동 (root cause C) 동반 의무 — 운영 dispatch
4. orchestrator 분기 결정 영역 — 영역 LOCK 위반 0이지만 spec amend chain 동반

→ 본 진단 보고서 + task.mjs done + 보고서만 commit 하는 PR 또는 commit chore 발행 (영역 책임 보고서 only) — orchestrator 분기 결정 위임.

---

## 7. 참고 파일 (절대경로)

- read port: `/Users/ak-song/magic_ch1/.claude/worktrees/agent-a8e70a5bcae3f447d/domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/trend/MarketIndexSnapshotReader.kt`
- Noop impl: `/Users/ak-song/magic_ch1/.claude/worktrees/agent-a8e70a5bcae3f447d/api/src/main/kotlin/kr/spdaewoon/magicjar/api/persistence/trend/NoopMarketIndexSnapshotReader.kt`
- read domain: `/Users/ak-song/magic_ch1/.claude/worktrees/agent-a8e70a5bcae3f447d/domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/trend/SectorSnapshot.kt`
- write port: `/Users/ak-song/magic_ch1/.claude/worktrees/agent-a8e70a5bcae3f447d/domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/market/index/port/MarketIndexSnapshotRepository.kt`
- write domain: `/Users/ak-song/magic_ch1/.claude/worktrees/agent-a8e70a5bcae3f447d/domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/market/index/MarketIndexSnapshot.kt`
- write impl: `/Users/ak-song/magic_ch1/.claude/worktrees/agent-a8e70a5bcae3f447d/batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/persistence/market/MarketIndexSnapshotRepositoryAdapter.kt`
- 1분 cron: `/Users/ak-song/magic_ch1/.claude/worktrees/agent-a8e70a5bcae3f447d/batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/job/market/MarketIndexSyncJob.kt`
- api 서비스 (read port 소비자): `/Users/ak-song/magic_ch1/.claude/worktrees/agent-a8e70a5bcae3f447d/api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/trend/TrendQueryService.kt` · `.../SectorRankingService.kt`
- ADR 0100: `/Users/ak-song/magic_ch1/.claude/worktrees/agent-a8e70a5bcae3f447d/_workspace/arch/adr/0100-t-market-track.md`
- PR #677: `feat(market-data): W4 R1 — T-MARKET Phase 1` mergedAt 2026-05-24T06:30:00Z

---

## 8. 헌법 정합

- §1 task.mjs 4단계 (start · check · done) — 본 보고서 작성 완료 후 done
- §1-A worktree git config — 본 worktree `bot+market-data@magicjar.local` · `magicjar-market-data` LOCK
- §1-C task 파일 commit 의무 — `_workspace/tasks/T-20260524-1737-worktree-agent-a8e70a5bcae3f447d.md`
- §3 KIS-first — 본 P2는 read-only 진단 + read 쿼리 spec 의제만, KIS endpoint 호출 0 → 무관
- §4 CHANGELOG slot — 보고서 발행 row 추가 의무
- §5 PR 직전 rebase 의무 — PR 발행 시 적용
- §6 Repository default fun 함정 — 본 P2 진단 영역 외 (해당 PR #677은 이미 0 LOCK 검증)
