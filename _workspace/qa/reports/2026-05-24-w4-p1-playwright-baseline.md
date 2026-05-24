# W4 P1 — Playwright Baseline 발행 실측 evidence

- **dispatch**: orchestrator → qa W4 P1 후속 (5건)
- **agent**: qa (magicjar-qa "품질 헌터")
- **수행 시각**: 2026-05-24 17:13 KST
- **HEAD baseline**: `094ab481` (W4 R3 머지 직후, R3 hotfix 포함)
- **task**: `_workspace/tasks/T-20260524-1702-w4-p1-evidence.md`
- **branch**: `agent/qa/w4-p1-evidence`

## 1. 환경 복구 evidence (P0 사전 차단 해소)

### 1-1. 사전 차단 — main repo stale jar

- worktree `094ab481` HEAD에는 W4 R3 hotfix(JpaScanConfiguration → `api.mcp.persistence` 추가) 포함되어 있었으나, **main repo local `main`은 2 commit behind** 상태 — `api/build/libs/api-0.0.1-SNAPSHOT.jar` mtime이 May 24 16:32(R2 시점)로 stale.
- 부팅 실패 evidence:
  ```
  APPLICATION FAILED TO START
  Parameter 0 of constructor in McpAuditService required a bean of type
  'kr.spdaewoon.magicjar.api.mcp.persistence.McpAuditJpaRepository' that could not be found.
  ```
- 복구 절차:
  1. `git fetch origin main && git pull --ff-only` (main repo)
  2. `./gradlew :api:bootJar -x test` → jar mtime 17:05:11 (R3 hotfix 포함)
  3. `bash scripts/build-and-deploy.sh --no-gradle api-app` → 컨테이너 recreate → healthy
- 부팅 evidence:
  ```
  17:05:30.829 [-] [main] INFO  ApiApplicationKt - Started ApiApplicationKt in 7.777 seconds
  ```

### 1-2. frontend Playwright 의존성 설치

- worktree에 `frontend/node_modules` 0건 → `pnpm install --prefer-offline` (1s · offline cache 정합)
- Chromium 1223 browser 사전 캐시 정합 (Library/Caches/ms-playwright)

## 2. Playwright Baseline 발행 매트릭스 (7 spec)

| spec | 시나리오 | 결과 | baseline PNG | 비고 |
|------|---------|------|--------------|------|
| W4-1 | Dashboard MarketIndexStrip — 4 카드 + heading + 상세 링크 | PASS (2.4s) | `w4-1-dashboard-market-index-strip-chromium-darwin.png` 103KB | MarketIndexSnapshot 0건 graceful empty path |
| W4-2 | `/market/trend` 3 패널 grid + 차트 + 섹터 ranking | PASS (3.5s) | `w4-2-market-trend-page-chromium-darwin.png` 89KB | section 노출 정상 |
| W4-3 | SettingsModal TrendPreferenceSection | PASS (3.2s) | `w4-3-settings-trend-preference-chromium-darwin.png` 192KB | 모달 진입 + 폼 노출 정상 |
| W4-4 | POST `/api/ai/explain` 5 type (SYMBOL·CHART·STRATEGY·SIGNAL·MARKET) | **5/5 PASS** (~115ms 합산) | n/a (API spec) | 5 진입점 모두 `REFUSED` + `AI_DISABLED` + 한국어 userMessage |
| W4-5 | GET `/mcp/tools` (6 tool 검증) | **FAIL** | n/a | **spec 결함** — path `/mcp/tools` 오타. 실제 매핑 `/api/mcp/tools` |
| W4-6 | TrendPreference Bean Validation 거부 | **0/4 FAIL** | n/a | **backend 결함** — 400 기대 / 500 응답 (Bean Validation 위반 시 GlobalExceptionHandler가 500으로 폴백) |
| W4-7 | actuator metrics endpoint KIS Rate Limit 노출 | PASS (176ms) | n/a (metrics scrape) | Prometheus endpoint 200 OK |

### 2-1. PASS 합산: **8 케이스 PASS** (W4-1·W4-2·W4-3·W4-4 5건·W4-7)
### 2-2. FAIL 합산: **5 케이스 FAIL** (W4-5 1건 + W4-6 4건)

## 3. 발견 결함 매트릭스 (cut 차단 분류)

### 3-1. P0 — backend 결함 (cut 차단)

**결함 #1: TrendPreference Bean Validation 500 fallback**
- 위치: `TrendPreferenceController` PUT `/api/users/me/preferences/trend`
- 증상: 4 validation 위반(0개 periods · 6개 periods · 중복 [5,5,20] · value 253) 모두 500 응답
- 기대: 400 Bad Request + 한국어 violation message
- 영향: spec W4-6 4건 모두 FAIL
- dispatch: **backend-core P0 hotfix 필요** — `@ControllerAdvice`에 `MethodArgumentNotValidException` 핸들러 누락 의심

### 3-2. P1 — spec 결함 (qa 자체 hotfix)

**결함 #2: W4-5 spec path 오타**
- spec 라인 27: `http://localhost:8080/mcp/tools`
- 실제 controller: `@RequestMapping("/api/mcp")` → `/api/mcp/tools`
- 영향: spec FAIL (실제 endpoint는 정상)
- 본 P1 dispatch 범위 외 — 후속 P2 dispatch에서 spec fix + 재실행 의무

### 3-3. P2 — backend 결함 (post-MVP graceful)

**결함 #3: backtest_strategy 500 fallback (strategyId 미존재)**
- MCP `POST /api/mcp/tools/backtest_strategy` → strategyId=1 미존재 시 500 응답
- log: `WARN McpController - [mcp] backtest_strategy FAIL ... err=전략을 찾을 수 없습니다: strategyId=1`
- 기대: 422/404 + 한국어 graceful refuse
- 영향: post-MVP user UX 개선 후보. cut 차단 X (사용자 흐름에서 미존재 strategyId 호출은 비정상)

## 4. baseline PNG evidence

```
frontend/tests/e2e/w4/
├─ w4-1-dashboard-market-index-strip.spec.ts-snapshots/
│   └─ w4-1-dashboard-market-index-strip-chromium-darwin.png (103439 bytes)
├─ w4-2-market-trend-page.spec.ts-snapshots/
│   └─ w4-2-market-trend-page-chromium-darwin.png (89111 bytes)
└─ w4-3-settings-trend-preference.spec.ts-snapshots/
    └─ w4-3-settings-trend-preference-chromium-darwin.png (192850 bytes)
```

총 385KB · 3 PNG · 모두 1440x900 viewport · chromium-darwin baseline. add-only 정합 (기존 PNG 0건 덮어쓰기).

## 5. 권고 매트릭스

| 우선순위 | 영역 | 작업 |
|---------|------|------|
| P0 | backend-core | TrendPreference Bean Validation 400 handler 발행 (cut 차단) |
| P1 | qa | W4-5 spec path `/mcp/tools` → `/api/mcp/tools` 수정 + 재실행 |
| P2 | backend-core | backtest_strategy graceful refuse (StrategyNotFoundException → 422) |
| P3 | architect | NoopMarketIndexSnapshotReader 단독 활성 현황 진단 (MarketIndexSyncJob 미작동) |

## 6. 본 P1 산출물 commit 범위

- baseline PNG 3건: `frontend/tests/e2e/w4/*-snapshots/*.png`
- 보고서 3건: `_workspace/qa/reports/2026-05-24-w4-p1-*.md`
- task 파일: `_workspace/tasks/T-20260524-1702-w4-p1-evidence.md`
- 코드 변경 0건 (qa 영역 LOCK)
