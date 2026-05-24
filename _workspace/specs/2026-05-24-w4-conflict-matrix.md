# W4 통합 사이클 충돌 매트릭스 (R1 3 병렬 + R2 2 병렬)

> **상태**: architect R0 spec (사용자 brainstorming 정합)
> **권위**: R1·R2 sub-agent dispatch 시 본 spec §1·§2·§3 사전 검증 의무
> **소유**: architect → orchestrator
> **작성일**: 2026-05-24

---

## §1. 결론 — 모든 R 단계 add-only 0 충돌 LOCK

| R 단계 | 병렬 수 | 충돌 risk | 결론 |
|---|---|---|---|
| R0 | 1 (architect 단독) | 0 | spec/ADR md only |
| R1 | 3 병렬 (market-data · backend-core · ai-engineer) | LOW | Flyway 번호 + 모듈 영역 + Kafka 토픽 사전 분배 LOCK |
| R2 | 2 병렬 (frontend · backend-core) | 0 | 완전 영역 분리 (frontend vs api mcp/) |
| R3 | 1 (qa 단독) | 0 | tests/e2e/w4/ 신규 디렉토리 |

---

## §2. R1 3 병렬 충돌 매트릭스 (LOW risk + 사전 분배 LOCK)

### 2-1. 영역 침범 매트릭스

| sub-agent | 영역 점유 | 영역 침범 risk | mitigation |
|---|---|---|---|
| market-data | api `infrastructure/kis/index/` (신규) · batch `job/market/` (신규) · domain `market/index/MarketIndexFetcherPort.kt` (신규) | 0 (모두 신규 디렉토리 add-only) | — |
| backend-core (T-MARKET Phase 2) | **신규 모듈 `market-trend-engine/`** · domain `trend/` (신규) · api `controller/market/` (신규) + `controller/user/` (신규) · `infrastructure/persistence/TrendPreferenceJpaAdapter.kt` · settings.gradle.kts amend (+1 라인) | 0 (모두 신규 디렉토리 add-only) | — |
| ai-engineer | api `ai/` (신규 패키지) · api `controller/ai/` (신규) · consumer `listener/AiCommentDispatcherListener.kt` (신규) · domain `ai/` (신규 또는 ADR 0026 §2-3 정합 신설) | 0 | — |

### 2-2. Flyway 번호 사전 분배 LOCK (R0 spec §1 정합 — 충돌 회피)

| version | 표 | 담당 | 충돌 검증 |
|---|---|---|---|
| **V40** | `market_index_snapshot` | market-data | LOCK |
| **V41** | `sector_index_snapshot` | market-data | LOCK |
| **V42** | `ai_comment_log` | ai-engineer | LOCK |
| **V43** | `prompt_template` (+ seed 5건) | ai-engineer | LOCK |
| **V44** | `user_preference` | backend-core (T-MARKET) | LOCK |
| **V45** | `mcp_audit` | backend-core (T-MCP) — R2 영역 | LOCK |

**충돌 LOCK 검증 의무**: 각 sub-agent dispatch 시 본 매트릭스 정합 의무. V40~V45 외 신규 마이그레이션 발견 시 architect 우선 결정 (orchestrator escalation 의무).

### 2-3. Kafka 토픽 사전 분배 LOCK

| topic | publisher | consumer | sub-agent |
|---|---|---|---|
| `market.index.updated.v1` | batch (MarketIndexSyncJob) | api (STOMP fanout) | market-data publish + backend-core consume |
| `market.sector.updated.v1` | batch (MarketIndexSyncJob) | api (STOMP fanout) | market-data publish + backend-core consume |
| `ai.comment.requested.v1` | api (AiExplainController) | consumer (AiCommentDispatcherListener) | ai-engineer 양방 |
| `ai.comment.responded.v1` | consumer (AiCommentDispatcherListener) | api (STOMP fanout) | ai-engineer 양방 |

**충돌 회피**: 각 토픽의 publisher와 consumer가 같은 sub-agent 또는 명확히 분리. consumer 측 fanout listener (`MarketIndexUpdatedListener`)는 backend-core 영역 — market-data가 토픽 publish만, fanout은 backend-core의 STOMP layer가 정합.

### 2-4. REST endpoint 사전 분배 LOCK

| endpoint | sub-agent | 영역 |
|---|---|---|
| `GET /api/market/index/snapshot` | backend-core (T-MARKET) | MarketIndexController |
| `GET /api/market/index/{code}/trend` | backend-core | MarketIndexController |
| `GET /api/market/sectors/snapshot` | backend-core | MarketSectorController |
| `GET /api/market/sectors/ranking` | backend-core | MarketSectorController |
| `GET·PUT /api/users/me/preferences/trend` | backend-core | UserPreferenceController |
| `POST /api/ai/explain` | ai-engineer | AiExplainController |

**충돌 0** — 모든 endpoint controller 신규 add-only.

### 2-5. STOMP topic 사전 분배 LOCK

| topic | sub-agent |
|---|---|
| `/topic/market.index.{code}` | backend-core (MarketIndexStompPublisher) |
| `/topic/market.sector.rank` | backend-core |
| `/topic/market.user.{userId}.pref` | backend-core |
| `/topic/ai.explain.{requestId}` | ai-engineer (AiExplainStompPublisher) |

**충돌 0** — STOMP publisher 신규 add-only.

### 2-6. settings.gradle.kts amend 충돌 (HIGH risk — backend-core 단독)

backend-core (T-MARKET Phase 2)가 `include("market-trend-engine")` 1 라인 add. market-data·ai-engineer는 settings.gradle.kts 0 touch LOCK. 

**충돌 회피**: 본 1 라인 add는 backend-core 단독 책임. orchestrator dispatch trigger 시 명시 의무.

### 2-7. 공유 import / domain 모듈 충돌 (LOW risk)

| 공유 | 충돌 | mitigation |
|---|---|---|
| `KisCallPriority` enum | market-data가 INGESTION_LOW value 추가 (+1 라인 amend) | enum은 add-only — backend-core·ai-engineer는 본 enum 사용 0 (T-MARKET cron 한정) |
| `Candle` domain | 모든 sub-agent reuse | read-only — 변경 0 |
| `user_account` 표 FK | backend-core가 user_preference에서 reference | read-only — 변경 0 |

### 2-8. modify-shared 매트릭스 (0 LOCK 검증)

검증 명령 (orchestrator 머지 후 의무):
```bash
# R1 3 PR diff 교집합 검증
git log --oneline --all --since="R0 머지 시점"
git diff <R1-PR-A>..<R1-PR-B> --stat | grep -v "^_workspace/\|^CHANGELOG.md\|^docs/" | wc -l
# 0이어야 LOCK 검증 통과 (CHANGELOG·spec·docs 외 modify-shared 0)
```

---

## §3. R2 2 병렬 충돌 매트릭스 (0 risk + 완전 영역 분리)

### 3-1. 영역 침범 매트릭스

| sub-agent | 영역 점유 | 영역 침범 risk |
|---|---|---|
| frontend | `frontend/src/components/market/` · `frontend/src/components/ai/` · `frontend/src/pages/MarketTrendPage.tsx` · `frontend/src/hooks/use*` · `frontend/src/api/*` · `frontend/src/types/*` + DashboardPage·SettingsModal·SymbolDetailPage·StrategyPage·DashboardPage·Routes·AppHeader amend (각 5~30 라인) | 0 — 모두 frontend 영역 |
| backend-core (T-MCP) | api `mcp/` (신규 패키지) · Flyway V45 · `mcp/indicator_catalog.json` resource | 0 — frontend 영역 0 touch |

### 3-2. 완전 영역 분리

- frontend는 TS/TSX 영역만 변경 (Kotlin 0)
- backend-core T-MCP는 Kotlin 영역만 변경 (TS 0)
- 양 sub-agent 간 modify-shared 0 LOCK

### 3-3. CHANGELOG slot 분배

| sub-agent | slot anchor |
|---|---|
| frontend | `<!-- pr-N-slot-frontend -->` |
| backend-core (T-MCP) | `<!-- pr-N-slot-backend-core-mcp -->` |

각 sub-agent 본인 row만 in-place edit. modify-shared 0 (헌법 §4 정합).

---

## §4. R3 충돌 (0 risk — qa 단독)

qa 단독 dispatch — `tests/e2e/w4/` 신규 디렉토리 + `_workspace/reports/` 신규 보고서. 영역 침범 0.

---

## §5. 머지 순서 LOCK (충돌 회피 의무)

orchestrator dispatch 직렬 순서:

1. **R0 architect** (본 PR) — main 머지
2. **R1 3 PR 머지** (병렬 OK · 머지 순서 무관) — Flyway V40~V44 모두 적용 + market-trend-engine 모듈 신설 + ai_comment_log·prompt_template 영속
3. **R2 2 PR 머지** (병렬 OK · 머지 순서 무관) — frontend UI + MCP T-MCP V45 적용
4. **R3 qa PR 머지** — E2E Visual 회귀 7 케이스 GREEN
5. **v3.9.0 cut** — baseline + tag + release notes

### 5-1. R 단계 간 직렬 의무 (병렬 금지)

R0 머지 전 R1 dispatch 금지 · R1 3 PR 모두 머지 전 R2 dispatch 금지 · R2 2 PR 모두 머지 전 R3 dispatch 금지. R1 ↔ R2 ↔ R3 직렬 의무 (T-AI controller 의존성 + UI는 backend endpoint 의존 + qa Visual은 UI 의존).

### 5-2. R 단계 내 병렬 OK (동시 dispatch)

R1 3 sub-agent 동시 dispatch OK (영역 분리 검증 §2 정합). R2 2 sub-agent 동시 dispatch OK (완전 영역 분리 §3 정합).

---

## §6. 충돌 발생 시 escalation 절차

각 sub-agent dispatch 중 충돌 발견 시:

1. **즉시 작업 중단** + orchestrator 보고
2. **architect 우선 결정** (모듈 경계 · Flyway 번호 · Kafka 토픽 · STOMP 등 권위 영역)
3. architect 결정 후 본 spec amend → 영향 sub-agent 재 dispatch (orchestrator 단독)
4. 충돌 evidence는 `_workspace/reports/2026-05-??-w4-conflict-evidence.md` 보고서 의무 (Codex 검증 게이트 정합)

---

## §7. References

- R0 design: `_workspace/specs/2026-05-24-w4-r0-design.md`
- R1·R2·R3 dispatch spec: `_workspace/specs/2026-05-24-w4-r1-r2-r3-dispatch-spec.md`
- Codex 검증 checklist: `_workspace/specs/2026-05-24-w4-codex-validation-checklist.md`
- 헌법 `_workspace/arch/agent-charter.md` §4 (CHANGELOG slot) · §5 (rebase)
