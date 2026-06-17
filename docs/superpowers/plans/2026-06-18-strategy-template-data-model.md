# 전략 시작 화면 + 템플릿 데이터화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **magicJar 실행 주의:** 이 plan은 다중 영역(domain·api·frontend·Flyway)이라 `magicjar-orchestrator`가 라운드별로 디스패치한다. 커밋은 에이전트 모자별 inline author 의무 — backend-core: `git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local"`, frontend: `bot+frontend@magicjar.local`. main 직접 push 금지(agent/* 브랜치+PR, rebase 머지).

**Goal:** 전략 빌더 진입 시 빈 캔버스 대신 "전략 시작 화면"을 띄우고, 검증 프리셋·즐겨찾기를 DB로 데이터화해 새 매매 유형이 코드 배포 없이 자라나게 한다.

**Architecture:** 기존 `strategy` 테이블에 `owner`(SYSTEM/USER) 컬럼을 추가해 프리셋(SYSTEM 소유)과 사용자 전략(USER 소유)을 한 테이블에서 다룬다. 프리셋 11개는 Flyway 시드로 적재(frontend 하드코딩 제거), 즐겨찾기는 `strategy_favorite` 테이블로 승격(localStorage 폐기). 시작 화면은 단일 조회 API(`GET /api/strategies/start`)로 세 재료(템플릿·내 전략·즐겨찾기)를 받는다.

**Tech Stack:** Kotlin 2.0 / Spring Boot 3.3 / JPA + Hibernate / Flyway / PostgreSQL(JSONB) / React 19 + TS + TanStack Query + Vite / vitest + MSW + RTL.

**연관 spec:** `docs/superpowers/specs/2026-06-18-strategy-template-data-model-design.md`

---

## File Structure

### R1 — 도메인·시그니처 (backend-core)
- Modify: `domain/.../strategy/Strategy.kt` — `owner: StrategyOwner` 필드 추가
- Create: `domain/.../strategy/StrategyOwner.kt` — `enum class StrategyOwner { SYSTEM, USER }`
- Create: `domain/.../strategy/StrategyFavorite.kt` — `data class StrategyFavorite(userId, strategyId, createdAt)`
- Create: `domain/.../strategy/port/StrategyFavoritePort.kt` — 즐겨찾기 포트
- Create: `api/.../controller/dto/StrategyStartResponse.kt` — 시작 화면 DTO
- Modify: `api/.../controller/StrategiesController.kt` — `GET /start`, `POST/DELETE /{id}/favorite` 스텁

### R2 — 영속·시드·백엔드 (backend-core)
- Create: `api/src/main/resources/db/migration/V88__strategy_owner_and_favorite.sql`
- Create: `api/src/main/resources/db/migration/V89__seed_system_strategy_templates.sql`
- Modify: `api/.../persistence/strategy/StrategyJpaEntity.kt` — `owner` 컬럼, `userId` nullable
- Modify: `api/.../persistence/strategy/StrategyJpaRepository.kt` — `findAllByOwner`, userId 조회
- Modify: `api/.../persistence/strategy/StrategyEntityMapper.kt` — owner 매핑
- Create: `api/.../persistence/strategy/StrategyFavoriteJpaEntity.kt` / `...JpaRepository.kt` / `...JpaAdapter.kt`
- Create: `api/.../service/strategy/StrategyStartService.kt` — 세 재료 합성
- Modify: `api/.../controller/StrategiesController.kt` — 스텁 → 구현

### R3 — UI·일괄 검증 (frontend)
- Create: `frontend/src/components/strategy/StrategyStartScreen.tsx`
- Create: `frontend/src/api/hooks/useStrategyStart.ts`
- Modify: `frontend/src/api/strategy.ts` — `getStart`, `addFavorite`, `removeFavorite`
- Modify: `frontend/src/pages/Strategy/StrategyBuilderPage.tsx` — 시작 화면 분기 + 즐겨찾기 API 전환 + localStorage 흡수
- Delete: `frontend/src/lib/dsl/smartMoneyPresets.ts`, `frontend/src/lib/dsl/minerviniFavorites.ts` (시드 채취 후)
- Test: 백엔드 `StrategiesControllerStartTest.kt`, 프론트 `strategyStart.contract.test.ts`

---

## R1 — 도메인·시그니처

> 개발룰 4: R1 PR은 테스트 0건 OK. 컴파일 통과 + 시그니처 확정이 목표.

### Task 1: StrategyOwner enum + Strategy 도메인 필드

**Files:**
- Create: `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/strategy/StrategyOwner.kt`
- Modify: `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/strategy/Strategy.kt`

- [ ] **Step 1: enum 생성**

```kotlin
package kr.spdaewoon.magicjar.domain.strategy

/** 전략 소유 주체 — SYSTEM=시드 프리셋(시작화면 템플릿·자동매매 비대상), USER=사용자 전략. */
enum class StrategyOwner { SYSTEM, USER }
```

- [ ] **Step 2: Strategy.kt에 필드 추가**

`Strategy` data class에 `val owner: StrategyOwner = StrategyOwner.USER` 추가(기본값으로 기존 생성 호출부 무손상). `userId`는 SYSTEM 행에서 null일 수 있으므로 `val userId: Long?`로 nullable 전환 — 단 nullable 전환이 닿는 호출부(`!!`/non-null 가정)를 `grep -rn "\.userId" domain api consumer`로 점검하고 SYSTEM 무관 경로는 그대로 둔다.

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :domain:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  add domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/strategy/StrategyOwner.kt \
      domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/strategy/Strategy.kt
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  commit -m "feat(domain): Strategy owner(SYSTEM/USER) 필드 + userId nullable (R1)"
```

### Task 2: StrategyFavorite 모델 + 포트

**Files:**
- Create: `domain/.../strategy/StrategyFavorite.kt`
- Create: `domain/.../strategy/port/StrategyFavoritePort.kt`

- [ ] **Step 1: 모델**

```kotlin
package kr.spdaewoon.magicjar.domain.strategy

import java.time.Instant

/** 사용자별 전략 즐겨찾기 (localStorage 승격 — spec 2026-06-18 §5.2). */
data class StrategyFavorite(
    val userId: Long,
    val strategyId: Long,
    val createdAt: Instant,
)
```

- [ ] **Step 2: 포트 (헌법 §6 — interface body 0건, 어댑터가 구현)**

```kotlin
package kr.spdaewoon.magicjar.domain.strategy.port

interface StrategyFavoritePort {
    fun add(userId: Long, strategyId: Long)        // 멱등 (UNIQUE 충돌 무시)
    fun remove(userId: Long, strategyId: Long)
    fun strategyIdsOf(userId: Long): List<Long>
}
```

- [ ] **Step 3: 컴파일 + 커밋**

Run: `./gradlew :domain:compileKotlin` → SUCCESSFUL
```bash
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  add domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/strategy/StrategyFavorite.kt \
      domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/strategy/port/StrategyFavoritePort.kt
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  commit -m "feat(domain): StrategyFavorite 모델 + 포트 (R1)"
```

### Task 3: 시작 화면 DTO + 컨트롤러 시그니처

**Files:**
- Create: `api/.../controller/dto/StrategyStartResponse.kt`
- Modify: `api/.../controller/StrategiesController.kt`

- [ ] **Step 1: DTO (Swagger 4단 패턴 — @field:Schema)**

```kotlin
package kr.spdaewoon.magicjar.api.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "전략 시작 화면 — 검증 템플릿 + 내 전략 + 즐겨찾기")
data class StrategyStartResponse(
    @field:Schema(description = "SYSTEM 소유 검증 템플릿") val templates: List<StrategyStartItem>,
    @field:Schema(description = "내(userId) 전략, favorite 플래그 포함") val myStrategies: List<StrategyStartItem>,
    @field:Schema(description = "즐겨찾기한 strategyId 목록") val favoriteIds: List<Long>,
)

@Schema(description = "시작 화면 카드 1건")
data class StrategyStartItem(
    val id: Long,
    val name: String,
    val nameKo: String?,
    val tf: String,
    val dslEntry: String,
    val dslExit: String?,
    val takeProfitPct: java.math.BigDecimal?,
    val fixedStopLossPct: java.math.BigDecimal?,
    val favorite: Boolean,
)
```

- [ ] **Step 2: 컨트롤러 스텁 추가** (`StrategiesController.kt`에 메서드 3개. 기존 생성자 주입에 `StrategyStartService`·`StrategyFavoritePort`는 R2에서 채우고, R1은 `TODO()` 본문 + 시그니처 확정)

```kotlin
@Operation(summary = "전략 시작 화면 조회", description = "검증 템플릿 + 내 전략 + 즐겨찾기를 단일 호출로 반환")
@GetMapping("/start")
fun start(@Parameter(description = "사용자 id", example = "1") @RequestParam userId: Long): StrategyStartResponse =
    strategyStartService.load(userId)

@Operation(summary = "전략 즐겨찾기 추가(멱등)")
@PostMapping("/{id}/favorite")
@ResponseStatus(HttpStatus.CREATED)
fun addFavorite(@PathVariable id: Long, @RequestParam userId: Long) = favoritePort.add(userId, id)

@Operation(summary = "전략 즐겨찾기 해제")
@DeleteMapping("/{id}/favorite")
@ResponseStatus(HttpStatus.NO_CONTENT)
fun removeFavorite(@PathVariable id: Long, @RequestParam userId: Long) = favoritePort.remove(userId, id)
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :api:compileKotlin`
Expected: BUILD SUCCESSFUL (StrategyStartService 미구현이면 R1에서는 컨트롤러에 임시 주입 대신 인터페이스만 두고 `TODO()` 반환으로 시그니처만 통과시킨다)

- [ ] **Step 4: 커밋**

```bash
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  add api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/dto/StrategyStartResponse.kt \
      api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/StrategiesController.kt
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  commit -m "feat(api): 전략 시작화면·즐겨찾기 API 시그니처 + DTO (R1)"
```

---

## R2 — 영속·시드·백엔드 구현

> R2 진입 전 **자원 audit**(메모리 feedback_spec_audit): Flyway 최신 = V87 확인 완료(다음 V88·V89). REST `/api/strategies/start`·`/{id}/favorite` 경로 충돌 없음 확인. ADR 1건 발행(spec §12 — owner 도입·origin 관계·SYSTEM user_id NULL 규약).

### Task 4: Flyway V88 — owner 컬럼 + user_id nullable + strategy_favorite

**Files:**
- Create: `api/src/main/resources/db/migration/V88__strategy_owner_and_favorite.sql`

- [ ] **Step 1: 마이그레이션 작성** (헌법 §6 IF NOT EXISTS LOCK + 헤더 컨벤션)

```sql
-- ============================================================================
-- V88 — strategy owner 컬럼 + strategy_favorite 테이블
-- ============================================================================
-- spec: docs/superpowers/specs/2026-06-18-strategy-template-data-model-design.md §5
-- 목적: 프리셋(SYSTEM)·사용자 전략(USER)을 한 테이블에서 다루고, 즐겨찾기를 DB로 승격.
-- consumer: GET /api/strategies/start, POST/DELETE /api/strategies/{id}/favorite
-- 컨벤션: IF NOT EXISTS LOCK · owner DEFAULT 'USER'(기존 26행 무손실) · user_id NULL=SYSTEM
-- ============================================================================

ALTER TABLE strategy ADD COLUMN IF NOT EXISTS owner VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE strategy ALTER COLUMN user_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS ix_strategy_owner ON strategy (owner);

CREATE TABLE IF NOT EXISTS strategy_favorite (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    strategy_id BIGINT      NOT NULL REFERENCES strategy(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_strategy_favorite_user_strategy
    ON strategy_favorite (user_id, strategy_id);

COMMENT ON COLUMN strategy.owner IS 'spec 2026-06-18 §5.1 — SYSTEM(시드 프리셋·자동매매 비대상) | USER';
COMMENT ON TABLE strategy_favorite IS 'spec 2026-06-18 §5.2 — 사용자별 전략 즐겨찾기(localStorage 승격)';
```

- [ ] **Step 2: 마이그레이션 적용 확인**

Run: `docker compose ... up -d api-app` 후 `docker exec magicjar-postgres psql -U magicjar -d magicjar -c "\d strategy_favorite"`
Expected: 테이블 존재 + ux 인덱스. `SELECT owner, count(*) FROM strategy GROUP BY owner;` → `USER|26`.

- [ ] **Step 3: 커밋**

```bash
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  add api/src/main/resources/db/migration/V88__strategy_owner_and_favorite.sql
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  commit -m "feat(db): V88 strategy owner 컬럼 + strategy_favorite (R2)"
```

### Task 5: 프리셋 시드 DSL 채취 + V89

> spec §5.3 LOCK — 시드 DSL은 **수기 추정 금지**, 프론트 serializer로 채취한 값만 사용.

**Files:**
- Create: `api/src/main/resources/db/migration/V89__seed_system_strategy_templates.sql`
- (임시) `frontend/scripts/extract-preset-dsl.mjs`

- [ ] **Step 1: 채취 스크립트** — `smartMoneyPresets.ts`·`minerviniFavorites.ts`의 각 preset.tree를 `serializeBlockTree(tree)`로 변환해 `{id,label,suggestedInterval,entry,exit}` 11행을 stdout JSON으로 출력하는 일회용 mjs를 작성하고 실행한다.

Run: `cd frontend && node scripts/extract-preset-dsl.mjs > /tmp/preset-dsl.json`
Expected: 11건 JSON, 각 entry DSL 문자열(예: `fvg(50).bullish_active == 1 AND close > fvg(50).bullish_mid`).

- [ ] **Step 2: 채취값을 V89 시드로 박기** (멱등 — name UNIQUE 없으면 `WHERE NOT EXISTS`)

```sql
-- ============================================================================
-- V89 — SYSTEM 전략 템플릿 시드 (스마트머니 6 + 미너비니 5)
-- ============================================================================
-- spec: 2026-06-18 §5.3 — frontend serializer 채취 DSL (수기 추정 0). 멱등.
-- ============================================================================
INSERT INTO strategy (user_id, owner, name, name_ko, dsl_entry, dsl_exit, universe_market, tf,
                      take_profit_pct, fixed_stop_loss_pct, ast_depth, node_count, indicator_call_count,
                      estimated_eval_time_ms, complexity_class, status, origin, created_at, updated_at)
SELECT NULL, 'SYSTEM', 'fvg-bullish-reclaim', 'FVG 매수 후보',
       '<채취 entry DSL>', '<채취 exit DSL>', 'KOSPI', '3m',
       NULL, NULL, 0, 0, 0, 0, 'LOW', 'ACTIVE', 'SEED', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM strategy WHERE owner='SYSTEM' AND name='fvg-bullish-reclaim');
-- … 나머지 10건 동일 패턴(채취값으로 치환) …
```
> ast_depth/node_count 등 복잡도 컬럼은 0으로 시드(템플릿은 평가 비대상). origin='SEED'로 표기해 manual/AI와 구분.

- [ ] **Step 3: 시드 검증**

Run: `docker exec magicjar-postgres psql -U magicjar -d magicjar -c "SELECT owner,count(*) FROM strategy GROUP BY owner;"`
Expected: `SYSTEM|11`, `USER|26`. 임시 mjs 삭제.

- [ ] **Step 4: 커밋**

```bash
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  add api/src/main/resources/db/migration/V89__seed_system_strategy_templates.sql
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  commit -m "feat(db): V89 SYSTEM 전략 템플릿 11건 시드(채취 DSL) (R2)"
```

### Task 6: JPA 엔티티/매퍼 owner + 자동매매 SYSTEM 제외

**Files:**
- Modify: `api/.../strategy/StrategyJpaEntity.kt`, `StrategyEntityMapper.kt`, `StrategyJpaRepository.kt`

- [ ] **Step 1: 엔티티 컬럼** — `StrategyJpaEntity`에 `@Column(name="owner") val owner: String = "USER"` 추가, `userId: Long?`로 nullable. `toEntity()`/`toDomain()`에 owner 매핑(`safeOwner()` — invalid 값은 USER로 흡수, 기존 `safeStatus` 패턴 정합).

- [ ] **Step 2: 자동매매 SYSTEM 제외 가드** — `findActiveByCodeAndInterval` 등 시그널/평가 조회가 SYSTEM 행을 절대 잡지 않도록 `AND owner = 'USER'` 조건 추가. `grep -rn "findActive\|findSellEval\|findAllByStatus" api` 로 평가 경로 전수 확인 후 일괄 가드.

- [ ] **Step 3: 조회 메서드** — `findAllByOwner(owner: String): List<StrategyJpaEntity>`(템플릿용), 기존 `findAllByUserIdAndStatusNot`(내 전략용) 재사용.

- [ ] **Step 4: 테스트(R2 최소) + 커밋** — `StrategyJpaAdapter` owner 왕복 저장/조회 1건. SYSTEM 행이 `findActive...`에 안 잡힘 1건.

Run: `./gradlew :api:test --tests "*StrategyJpaAdapter*"` → PASS
```bash
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  commit -am "feat(api): strategy owner 매핑 + 자동매매 SYSTEM 제외 가드 (R2)"
```

### Task 7: StrategyFavorite 어댑터

**Files:**
- Create: `api/.../strategy/StrategyFavoriteJpaEntity.kt`, `StrategyFavoriteJpaRepository.kt`, `StrategyFavoriteJpaAdapter.kt`

- [ ] **Step 1: 엔티티** — `@Entity @Table(name="strategy_favorite")`, 컬럼 id/userId/strategyId/createdAt.
- [ ] **Step 2: 리포지터리** — `JpaRepository<…, Long>` + `fun findAllByUserId(userId): List<…>`, `fun deleteByUserIdAndStrategyId(userId, strategyId): Int`.
- [ ] **Step 3: 어댑터** — `StrategyFavoritePort` 구현. `add`는 멱등(`try insert / catch DataIntegrityViolation → no-op` 또는 존재 체크 후 insert). `strategyIdsOf`는 `findAllByUserId().map{it.strategyId}`.
- [ ] **Step 4: 테스트 + 커밋** — add 멱등(중복 add 후 count=1), remove, strategyIdsOf 1건.

```bash
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  commit -am "feat(api): StrategyFavorite JPA 어댑터(멱등 add) (R2)"
```

### Task 8: StrategyStartService + 컨트롤러 구현

**Files:**
- Create: `api/.../service/strategy/StrategyStartService.kt`
- Modify: `api/.../controller/StrategiesController.kt` (스텁 → 실주입)

- [ ] **Step 1: 서비스** — `load(userId)`:
  1. `templates = strategyRepository.findAllByOwner("SYSTEM").map { it.toStartItem(favorite=false) }`
  2. `favoriteIds = favoritePort.strategyIdsOf(userId).toSet()`
  3. `myStrategies = strategyRepository.findAllByUserId(userId).map { it.toStartItem(favorite = it.id in favoriteIds) }`
  4. `StrategyStartResponse(templates, myStrategies, favoriteIds.toList())`
  - 0건이면 빈 리스트(mock 합성 0 — 메모리 feedback_no_mock_data).
- [ ] **Step 2: 컨트롤러 실주입** — R1 `TODO()` 제거, `strategyStartService`·`favoritePort` 생성자 주입 연결.
- [ ] **Step 3: 테스트(@WebMvcTest+MockMvc, mockito-kotlin)** — `/start` 응답에 templates/myStrategies/favoriteIds 합성 검증, favorite 플래그 정확. `POST /{id}/favorite`→201, `DELETE`→204.

Run: `./gradlew :api:test --tests "*StrategiesControllerStart*"` → PASS

- [ ] **Step 4: 커밋 + 배포 검증** — `scripts/build-and-deploy.sh api-app` 후 `curl 'localhost:8080/api/strategies/start?userId=1'`로 templates 11 + myStrategies 응답 실측.

```bash
git -c user.name="magicjar-backend-core" -c user.email="bot+backend-core@magicjar.local" \
  commit -am "feat(api): 전략 시작화면 조회 서비스 + 즐겨찾기 구현 (R2)"
```

---

## R3 — UI·일괄 검증

### Task 9: frontend API + hook

**Files:**
- Modify: `frontend/src/api/strategy.ts`
- Create: `frontend/src/api/hooks/useStrategyStart.ts`

- [ ] **Step 1: API 함수** (`sigStrategyApi`에 추가, 기존 apiClient 패턴)

```ts
export interface StrategyStartItem {
  id: number; name: string; nameKo: string | null; tf: string
  dslEntry: string; dslExit: string | null
  takeProfitPct: number | null; fixedStopLossPct: number | null; favorite: boolean
}
export interface StrategyStartResponse {
  templates: StrategyStartItem[]; myStrategies: StrategyStartItem[]; favoriteIds: number[]
}
// sigStrategyApi에 추가:
getStart: (userId: number) =>
  apiClient.get<StrategyStartResponse>(`/strategies/start`, { params: { userId } }).then(r => r.data),
addFavorite: (id: number, userId: number) =>
  apiClient.post(`/strategies/${id}/favorite`, null, { params: { userId } }),
removeFavorite: (id: number, userId: number) =>
  apiClient.delete(`/strategies/${id}/favorite`, { params: { userId } }),
```

- [ ] **Step 2: hook** (기존 useSigStrategy 패턴 — queryKey·staleTime·enabled)

```ts
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { sigStrategyApi, type StrategyStartResponse } from '../strategy'

const STRATEGY_START_KEY = (userId: number) => ['strategy-start', userId] as const

export function useStrategyStart(userId: number | null | undefined) {
  return useQuery<StrategyStartResponse>({
    queryKey: STRATEGY_START_KEY(userId ?? 0),
    queryFn: () => sigStrategyApi.getStart(userId!),
    enabled: userId !== null && userId !== undefined,
    staleTime: 60 * 1000,
  })
}
export function useToggleFavorite(userId: number) {
  const qc = useQueryClient()
  return useMutation<void, Error, { id: number; on: boolean }>({
    mutationFn: ({ id, on }) => on ? sigStrategyApi.addFavorite(id, userId) : sigStrategyApi.removeFavorite(id, userId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['strategy-start', userId] }),
  })
}
```

- [ ] **Step 3: typecheck + 커밋**

Run: `cd frontend && pnpm typecheck` → no error
```bash
git -c user.name="magicjar-frontend" -c user.email="bot+frontend@magicjar.local" \
  add frontend/src/api/strategy.ts frontend/src/api/hooks/useStrategyStart.ts
git -c user.name="magicjar-frontend" -c user.email="bot+frontend@magicjar.local" \
  commit -m "feat(frontend): 전략 시작화면 API + hook (R3)"
```

### Task 10: StrategyStartScreen 컴포넌트

**Files:**
- Create: `frontend/src/components/strategy/StrategyStartScreen.tsx`

- [ ] **Step 1: 컴포넌트** — props `{ userId: number; onPick: (item: StrategyStartItem) => void; onBlank: () => void }`. `useStrategyStart(userId)`로 조회. 렌더: 검색 input(클라이언트 필터), ⭐내 전략·즐겨찾기 섹션, 📦검증 템플릿 섹션, 💬"말로 설명"(기존 AI 생성 모달 트리거), "빈 캔버스로 직접"(onBlank). 카드 클릭 → onPick(item). 즐겨찾기 ★ 토글 → useToggleFavorite. 로딩/에러/빈상태(0건 "아직 없음 + 빈 캔버스로" — mock 0). 디자인은 spec final-design 목업 정합, Tailwind 기존 카드 클래스 재사용.

- [ ] **Step 2: 단위 테스트(vitest+RTL+MSW)** — `/strategies/start` mock 응답으로 템플릿/내전략 렌더, 카드 클릭 시 onPick 호출, 즐겨찾기 토글 시 POST/DELETE 발사. 빈 응답 시 "빈 캔버스로" 노출.

Run: `cd frontend && pnpm vitest run src/components/strategy/__tests__/StrategyStartScreen.test.tsx` → PASS

- [ ] **Step 3: 커밋**

```bash
git -c user.name="magicjar-frontend" -c user.email="bot+frontend@magicjar.local" \
  commit -am "feat(frontend): StrategyStartScreen 컴포넌트 + 테스트 (R3)"
```

### Task 11: StrategyBuilderPage 통합 + 즐겨찾기 API 전환

**Files:**
- Modify: `frontend/src/pages/Strategy/StrategyBuilderPage.tsx`

- [ ] **Step 1: 시작 화면 분기** — `tree`가 비어 있고 `strategyId`/`researchDraftMeta`/`aiDraftMeta` 미로드일 때 `<StrategyStartScreen userId={registeredUserId} onPick={loadFromStartItem} onBlank={() => setStarted(true)} />` 렌더. `loadFromStartItem(item)`: `parseDslTextToTree(item.dslEntry, item.dslExit ?? undefined)`로 tree 세팅 + 리스크플랜(tp/sl) 저장모달 기본값 전달(ADR 0112 — 자동 저장 안 함), `setStarted(true)`.

- [ ] **Step 2: 하드코딩 프리셋 import 제거** — `SMART_MONEY_STRATEGY_PRESETS`·`MINERVINI_STRATEGY_FAVORITES` import(line 38~41) 및 `favoritePresets` useMemo(line 208~218) 제거. BlockPalette 즐겨찾기 탭은 `useStrategyStart` 데이터(templates+myStrategies)로 공급하도록 props 전환.

- [ ] **Step 3: 즐겨찾기 localStorage → API** — `loadCustomFavorites`/`saveCustomFavorites`/`load·saveHiddenDefaultFavoriteIds`(line 78~124, 177~178, 333~366) 제거. `handleAddFavorite`/`handleRemoveFavorite`를 `useToggleFavorite` mutation으로 대체.

- [ ] **Step 4: localStorage 1회 흡수 마이그레이션** — 마운트 시 기존 `magicjar.strategyBuilder.customFavorites.v1` 키가 있으면, 각 항목을 1회 `addFavorite` 발사 후 키 제거(베스트에포트, 실패 무해). 단 customFavorites는 "사용자가 만든 트리"라 strategy로 존재하지 않을 수 있으므로 — 흡수 대상은 **strategyId로 식별 가능한 즐겨찾기만**. 식별 불가 항목은 무시하고 로그만.

- [ ] **Step 5: 시나리오 테스트(통합)** — StrategyBuilderPage 진입 시 시작 화면 렌더 → 템플릿 카드 클릭 → 캔버스에 tree 로드됨 → 저장 → 즐겨찾기 토글. MSW로 `/strategies/start`·`/favorite` mock.

Run: `cd frontend && pnpm vitest run src/pages/Strategy/__tests__/StrategyBuilderPage.test.tsx` → PASS
Run: `cd frontend && pnpm typecheck && pnpm build` → 성공

- [ ] **Step 6: 커밋**

```bash
git -c user.name="magicjar-frontend" -c user.email="bot+frontend@magicjar.local" \
  commit -am "feat(frontend): 빌더 시작화면 통합 + 즐겨찾기 API 전환 + 하드코딩 프리셋 제거 (R3)"
```

### Task 12: 하드코딩 프리셋 파일 제거 + qa Visual + 최종 검증

**Files:**
- Delete: `frontend/src/lib/dsl/smartMoneyPresets.ts`, `frontend/src/lib/dsl/minerviniFavorites.ts`
- Delete: `frontend/src/lib/dsl/__tests__/smartMoneyPresets.test.ts`

- [ ] **Step 1: 잔여 참조 grep** — `grep -rn "smartMoneyPresets\|minerviniFavorites\|SMART_MONEY_STRATEGY_PRESETS\|MINERVINI_STRATEGY_FAVORITES" frontend/src` → 0건이어야 삭제. StrategyFavoritePanel 등 잔여 import 정리.
- [ ] **Step 2: 파일 삭제 + typecheck/build**

Run: `cd frontend && pnpm typecheck && pnpm build && pnpm vitest run` → 전부 PASS

- [ ] **Step 3: qa Visual** (헌법 §9-2 — 시작 화면 신규 = Visual 자동 의무) — Playwright로 시작 화면 스냅샷 + 템플릿 클릭→빌더 진입 E2E 1건.
- [ ] **Step 4: 배포 + 실측 검증** — `scripts/build-and-deploy.sh` 후 4모듈 docker logs 에러 0 확인. 브라우저에서 `/strategy/builder` 진입 시 시작 화면(템플릿 11 + 내 전략) 표시, 카드 클릭 시 조건 로드, 즐겨찾기 ★ 다중기기 영속 확인.
- [ ] **Step 5: 커밋**

```bash
git -c user.name="magicjar-frontend" -c user.email="bot+frontend@magicjar.local" \
  commit -am "chore(frontend): 하드코딩 프리셋 제거(DB 데이터화 완료) + qa Visual (R3)"
```

---

## Self-Review 결과 (작성자 점검)

**Spec coverage:**
- §2 목표(시작 화면·무큐레이션·DB화·자라남) → Task 9~11(시작화면)·Task 4~5(DB화)·Task 5+11(자라남:시드+저장흡수) ✅
- §5 데이터 모델(owner·favorite·시드) → Task 1·4·5·7 ✅
- §6 API(start·favorite) → Task 3·8·9 ✅
- §7 마이그레이션(무손실·시드·localStorage 흡수·하드코딩 제거 순서 LOCK) → Task 4(DEFAULT)·5(시드)·11-4(흡수)·12(제거, R2 완료 후) ✅
- §8 단계(R1→R2→R3) → 그대로 ✅
- §9 에러(start 실패 폴백·DSL 로드 실패) → Task 10-1(빈상태 폴백). **gap: DSL 파서 버전 불일치 카드 경고** → Task 11-1 loadFromStartItem에 try/catch + 카드 경고 추가 필요(아래 보강).
- §12 ADR → R2 진입 전 명시 ✅

**보강(gap fix):** Task 11-1 `loadFromStartItem`은 `parseDslTextToTree` 실패 시 throw하지 않고 토스트/카드 경고로 처리(기존 AI 생성 `dslValid=false` 패턴 재사용) — 한 템플릿이 깨져도 나머지 사용 가능.

**Placeholder scan:** V89 시드의 `<채취 entry DSL>`은 의도된 채취 산출물 자리(Task 5-1에서 실제 값 생성). 그 외 placeholder 없음.

**Type consistency:** `StrategyStartItem`(백엔드 DTO)·`StrategyStartItem`(프론트 interface) 필드명 일치(id·name·nameKo·tf·dslEntry·dslExit·takeProfitPct·fixedStopLossPct·favorite). `StrategyOwner`/`owner` 문자열 매핑 일관. `useToggleFavorite`·`getStart`/`addFavorite`/`removeFavorite` 시그니처 일관 ✅.
