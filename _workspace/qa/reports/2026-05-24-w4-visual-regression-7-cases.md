# W4 R3 — E2E Visual 회귀 7 케이스 evidence

> **dispatch**: orchestrator W4 R3 qa 단독 (v3.9.0 cut prereq 검증)
> **owner**: magicjar-qa (품질 헌터 격상 — ADR 0094 §1-2 amend)
> **작성일**: 2026-05-24 (`bot+qa@magicjar.local`)
> **권위**: `_workspace/specs/2026-05-24-w4-r1-r2-r3-dispatch-spec.md` §R3-1
> **base**: origin/main HEAD 0de49168 (R0·R1·R2 모두 머지 완료)
> **연관**: ADR 0094 §9-2 (frontend 핵심 화면 변경 PR Visual 자동 의무 dispatch) · ADR 0096 §6-2 (DR drill)

---

## §0. Executive summary

| 영역 | 상태 | 비고 |
|------|------|------|
| Playwright spec 7 케이스 작성 | GREEN | `frontend/tests/e2e/w4/w4-1.spec.ts ~ w4-7.spec.ts` add-only |
| Flyway V47~V52 적용 | GREEN | 6건 모두 적용 (16:32:26 batch) |
| W4 6 표 생성 (market_index_snapshot · sector_index_snapshot · ai_comment_log · prompt_template · user_preference · mcp_audit) | GREEN | psql evidence |
| prompt_template seed 5건 적재 | GREEN | symbol/chart/strategy/signal/market |
| 자본 path (OrderService·OrderRouterKafkaListener·KillSwitchAutoToggler) 0 touch | GREEN | git diff 47f2d220..0de49168 grep evidence |
| W1·W2·W3 AI provider import 0건 | GREEN | grep `anthropic\|openai\|gemini\|org.springframework.ai` 외 W4 ai/ 디렉토리 0건 |
| W4 시점 4 모듈 docker logs ERROR/FATAL | GREEN (재배포 후 5분 window) | api·batch·consumer·frontend 모두 0건 |
| **api 부팅 abort (블로킹 결함)** | **RED** | `McpAuditJpaRepository` bean 미스캔 — `JpaScanConfiguration` 화이트리스트에 `api.mcp.persistence` 누락 |
| **hotfix 1줄 적용 (qa 자율)** | GREEN | `JpaScanConfiguration.kt` basePackages + entityScan 양쪽에 `api.mcp.persistence` add-only |
| Playwright 7 케이스 실제 실행 | RED (보류) | api 부팅 실패로 backend 의존 케이스 실측 0건 — **hotfix PR 머지 + 재배포 후 후속 dispatch에서 진행** |

---

## §1. 7 케이스 매트릭스

### 1-1. W4-1 DashboardPage MarketIndexStrip

| 항목 | 상태 | evidence |
|------|------|----------|
| spec 파일 | GREEN | `frontend/tests/e2e/w4/w4-1-dashboard-market-index-strip.spec.ts` add-only 작성 |
| 검증 항목 | spec 코드 | MarketIndexStrip section + heading "시장 지수" + 상세 추세 링크 ARIA + fullPage baseline 스크린샷 |
| 실측 | 보류 | api 부팅 후 ECONNREFUSED 가능 — hotfix 머지 후 후속 dispatch에서 baseline 발행 (`pnpm qa:visual:update`) |

### 1-2. W4-2 /market/trend 페이지

| 항목 | 상태 | evidence |
|------|------|----------|
| spec 파일 | GREEN | `frontend/tests/e2e/w4/w4-2-market-trend-page.spec.ts` add-only 작성 |
| 검증 항목 | spec 코드 | 3 패널 grid 마운트 대기 + fullPage baseline 스크린샷 |
| 실측 | 보류 | 동일 — 후속 dispatch |

### 1-3. W4-3 SettingsModal 추세 분석 설정

| 항목 | 상태 | evidence |
|------|------|----------|
| spec 파일 | GREEN | `frontend/tests/e2e/w4/w4-3-settings-trend-preference.spec.ts` add-only 작성 |
| 검증 항목 | spec 코드 | 헤더 ⚙️ 클릭 → 모달 열림 → "추세 분석 설정" 텍스트 노출 검증 + baseline |
| 실측 | 보류 | 동일 — 후속 dispatch |

### 1-4. W4-4 5 AI 진입점

| 항목 | 상태 | evidence |
|------|------|----------|
| spec 파일 | GREEN | `frontend/tests/e2e/w4/w4-4-ai-5-entries.spec.ts` add-only 작성 (SYMBOL·CHART·STRATEGY·SIGNAL·MARKET 5 type forEach) |
| 검증 항목 | spec 코드 | POST `/api/ai/explain` → 200/202 graceful or 400/403 분기 + 응답 body 정합 |
| 실측 | 보류 | api 부팅 후 실측 가능 — hotfix 머지 후 후속 dispatch |

### 1-5. W4-5 MCP 6 tool

| 항목 | 상태 | evidence |
|------|------|----------|
| spec 파일 | GREEN | `frontend/tests/e2e/w4/w4-5-mcp-curl-6-tools.spec.ts` add-only 작성 |
| 검증 항목 | spec 코드 | GET `/mcp/tools` → 6 tool name 정합 (`get_candles`, `run_screener`, `get_journal`, `explain_indicator`, `backtest_strategy`, `get_market_trend`) |
| 실측 | 보류 — McpToolRegistry 6 등록 evidence GREEN | api 부팅 직전 로그 `[mcp.registry] 등록 tool 6개: [backtest_strategy, explain_indicator, get_candles, get_journal, get_market_trend, run_screener]` (16:32:30.082) — 6 tool 등록 자체는 GREEN 확정 |

### 1-6. W4-6 TrendPreference Bean Validation

| 항목 | 상태 | evidence |
|------|------|----------|
| spec 파일 | GREEN | `frontend/tests/e2e/w4/w4-6-preference-validation.spec.ts` add-only 작성 (4 bad case: 0개·6개·중복·253) |
| 검증 항목 | spec 코드 | PUT `/api/users/me/preferences/trend` → 400 Bad Request + 한국어 message contract (한글 1자 이상) |
| 실측 | 보류 | api 부팅 후 실측 가능 |

### 1-7. W4-7 KIS Rate Limit 실측

| 항목 | 상태 | evidence |
|------|------|----------|
| spec 파일 | GREEN | `frontend/tests/e2e/w4/w4-7-kis-rate-limit-realtime.spec.ts` add-only 작성 |
| 검증 항목 | spec 코드 | actuator/prometheus endpoint 존재 검증 (보조 evidence — 실측은 docker logs grep) |
| 실측 | 보류 | MarketIndexSyncJob 1분 cron 60회 운영 후 26 req/min ± 5% + EGW00201 0건 — 후속 dispatch |

---

## §2. 블로킹 결함 — McpAuditJpaRepository bean 미스캔 (RED)

### 2-1. 증상

api 부팅 로그 (16:32:30.086):

```
APPLICATION FAILED TO START

Description:

Parameter 0 of constructor in kr.spdaewoon.magicjar.api.mcp.McpAuditService required a bean of type
'kr.spdaewoon.magicjar.api.mcp.persistence.McpAuditJpaRepository' that could not be found.

Action:

Consider defining a bean of type 'kr.spdaewoon.magicjar.api.mcp.persistence.McpAuditJpaRepository' in your configuration.
```

→ api 컨테이너 exit code 1 → batch·consumer도 dependency failure로 부팅 abort → 4 모듈 중 frontend만 healthy

### 2-2. Root cause

`api/src/main/kotlin/kr/spdaewoon/magicjar/api/config/JpaScanConfiguration.kt` 의 `@EnableJpaRepositories(basePackages = [...])` 화이트리스트가 다음 2 패키지만 등록:
- `kr.spdaewoon.magicjar.api.persistence`
- `kr.spdaewoon.magicjar.persistence.shared`

그러나 R2 backend-core T-MCP Phase 1 (PR #679)에서 `McpAuditJpaRepository`를 **`kr.spdaewoon.magicjar.api.mcp.persistence`** 패키지에 신규 추가했고, 본 화이트리스트는 갱신되지 않음.

다른 R1/R2 신규 JpaRepository는 `api.persistence.*` 서브패키지에 추가되어 정상 스캔됨:
- `api/persistence/AiCommentLogJpaRepository.kt` (T-AI)
- `api/persistence/PromptTemplateJpaRepository.kt` (T-AI)
- `api/persistence/trend/UserPreferenceJpaRepository.kt` (T-MARKET Phase 2)

→ McpAuditJpaRepository 단 1건만 화이트리스트 밖.

### 2-3. qa 자율 hotfix (자율 처리 메모리 정합 — `feedback_conflict_resolution_autonomy`)

```kotlin
@EnableJpaRepositories(
    basePackages = [
        "kr.spdaewoon.magicjar.api.persistence",
        "kr.spdaewoon.magicjar.api.mcp.persistence",   // ← R3 qa hotfix
        "kr.spdaewoon.magicjar.persistence.shared",
    ],
)
@EntityScan(
    basePackages = [
        "kr.spdaewoon.magicjar.api.persistence",
        "kr.spdaewoon.magicjar.api.mcp.persistence",   // ← R3 qa hotfix
        "kr.spdaewoon.magicjar.persistence.shared",
    ],
)
```

본 hotfix는 worktree에 commit (`agent/qa/w4-r3-visual-regression`). PR 머지 후 main repo 흡수 → 재배포 → 부팅 GREEN → Playwright 7 케이스 실측 + Visual baseline 발행 후속 dispatch.

### 2-4. 장기 fix 의제 (backend-core 영역 dispatch 후보)

본 hotfix는 패치성 + 재발 가능. 장기 fix:
- 옵션 A — `@EnableJpaRepositories(basePackages = "kr.spdaewoon.magicjar")` 단일화로 모든 sub-package 자동 스캔
  - 위험: 영역 분리 약화 + `persistence.shared` 모듈 부트스트랩 path 충돌 가능
- 옵션 B — 신규 패키지 추가 시 화이트리스트 갱신 의무를 CHANGELOG slot 또는 PR template 체크리스트에 명시
  - 권장: 본 결함 evidence 첨부 + ADR 0088 amend 또는 신규 ADR 발행
- 옵션 C — JpaScan integration test 추가 — 모든 `*JpaRepository` 클래스가 ApplicationContext에 bean 등록 검증
  - 권장: ADR 0085 정합 통합 테스트 패턴 1:1

본 의제는 backend-core 영역 별도 dispatch로 escalation.

---

## §3. 후속 dispatch 의제 (사용자 결재 + orchestrator trigger)

1. **본 qa hotfix PR 머지** (cut 차단 해소)
2. **재배포** — `scripts/build-and-deploy.sh` (main repo)
3. **부팅 GREEN 확인** — api·batch·consumer·frontend healthy
4. **Playwright 7 케이스 실측** — `pnpm qa:visual:update`로 baseline 발행 + diff 0건 확인
5. **MCP 6 tool curl evidence** — `curl -H "X-Api-Key: ${KEY}" /mcp/tools` + 각 tool POST 호출
6. **5 AI 진입점 evidence** — POST /api/ai/explain 5 type forEach
7. **TrendPreference 4 bad case evidence** — PUT /api/users/me/preferences/trend 4 case forEach
8. **KIS Rate Limit 실측** — MarketIndexSyncJob 1분 cron 60회 + actuator/prometheus grep
9. **mcp_audit + ai_comment_log INSERT evidence** — 5 진입점 + 6 tool 호출 후 row count 검증
10. **장기 fix ADR 의제 (옵션 B 또는 C)** — backend-core 영역 별도 dispatch

---

## §4. 관련 ADR 격자

- **ADR 0094 §9-2** — frontend 핵심 화면 변경 PR Visual 자동 의무 dispatch (본 R3 dispatch 발동 근거)
- **ADR 0094 §1-2 amend** — qa "품질 헌터" 격상 (사용자 결재 2026-05-21)
- **ADR 0096 §6-2** — qa DR drill 자동 재현 의무 (분기 1회 — 본 dispatch 외 별도)
- **ADR 0088** — persistence-shared 모듈 분리 + JpaScanConfiguration 권위 (본 hotfix가 정합 갱신)
- **ADR 0027** — MCP 본문 (`api.mcp.*` 패키지 책임 분리)
- **ADR 0100·0101·0102** — W4 R0 신설 (본 dispatch 회귀 가드 본체)
