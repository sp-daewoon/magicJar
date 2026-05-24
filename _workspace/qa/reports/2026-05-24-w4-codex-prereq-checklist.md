# W4 R3 — Codex 외부 review prereq 8 항목 체크리스트

> **dispatch**: orchestrator W4 R3 qa 단독 (사용자 "Codex가 봐도 손댈 게 없는 수준" 게이트 prereq)
> **owner**: magicjar-qa
> **작성일**: 2026-05-24
> **base**: origin/main HEAD 0de49168
> **권위**: `_workspace/specs/2026-05-24-w4-codex-validation-checklist.md` (Codex 4 lens 격자)
> **연관**: dispatch spec §R5 v3.9.0 release 게이트

---

## §0. 4 lens 격자 종합 결과

| Lens | 항목 | 상태 | evidence 위치 |
|------|------|------|----------------|
| 세심함 | Playwright 7 spec 한국어 assertion + ARIA selector | GREEN | `frontend/tests/e2e/w4/w4-*.spec.ts` |
| 세심함 | Flyway V47~V52 6건 적용 정합 | GREEN | psql evidence (§1) |
| 영향도 | 자본 path (OrderService·OrderRouterKafkaListener·KillSwitchAutoToggler) 0 touch | GREEN | git diff grep evidence (§3) |
| 영향도 | W1·W2·W3 AI provider import 0건 | GREEN | grep evidence (§2) |
| 연결 | 5 AI 진입점 spec + 6 MCP tool spec + STOMP race-safe + 다중 탭 sync | GREEN (spec 코드 단계) | spec 파일 단위 |
| 연결 | McpToolRegistry 6 등록 confirm | GREEN | api 부팅 직전 로그 `등록 tool 6개:[backtest_strategy, explain_indicator, get_candles, get_journal, get_market_trend, run_screener]` |
| 확장성 | 회귀 케이스 add-only 정합 + 향후 트랙 재사용 가능 | GREEN | 본 spec 패턴 1:1 |
| 영향도 | api 부팅 GREEN (cut prereq) | **RED** | McpAuditJpaRepository bean 미스캔 — hotfix PR 머지 후 회복 |

→ **8 항목 중 7 GREEN + 1 RED (블로킹)**. 본 qa PR 머지 + 재배포 후 후속 dispatch에서 RED→GREEN 회복 검증.

---

## §1. Flyway V47~V52 적용 evidence

```sql
$ docker exec magicjar-postgres psql -U magicjar -d magicjar -c "
    select version, description, success, installed_on
    from flyway_schema_history
    where version::int >= 47 order by version::int"

 version |      description      | success |        installed_on
---------+-----------------------+---------+----------------------------
 47      | market index snapshot | t       | 2026-05-24 16:32:26.758341
 48      | sector index snapshot | t       | 2026-05-24 16:32:26.806673
 49      | ai comment log        | t       | 2026-05-24 16:32:26.826444
 50      | prompt template       | t       | 2026-05-24 16:32:26.850341
 51      | user preference       | t       | 2026-05-24 16:32:26.864376
 52      | mcp audit             | t       | 2026-05-24 16:32:26.879499
(6 rows)
```

→ **6건 모두 GREEN** + 6 W4 표 모두 생성 (`market_index_snapshot`·`sector_index_snapshot`·`ai_comment_log`·`prompt_template`·`user_preference`·`mcp_audit`)

prompt_template seed 5건 적재 GREEN:

```sql
$ docker exec magicjar-postgres psql -U magicjar -d magicjar -c "select count(*) from prompt_template"
 count
-------
     5
```

---

## §2. W1·W2·W3 AI provider import 0건 grep (ADR 0026 §3 정합)

```bash
$ grep -rlE "anthropic|openai|gemini|com\.anthropic|com\.openai|org\.springframework\.ai" \
    --include="*.kt" \
    domain/src/main/kotlin \
    indicator-impl/src \
    strategy-engine/src \
    pattern-engine/src \
    persistence-shared/src \
    batch/src/main/kotlin \
    market-trend-engine/src \
    api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller \
    api/src/main/kotlin/kr/spdaewoon/magicjar/api/infrastructure \
    api/src/main/kotlin/kr/spdaewoon/magicjar/api/stomp \
    api/src/main/kotlin/kr/spdaewoon/magicjar/api/mcp \
    consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order \
    consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/signal \
    consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/risk \
    consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/market

domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/ai/AiCommentProvider.kt   # port interface — provider 호출 0
```

→ **W4 ai/ 디렉토리 외 AI provider import 0건 GREEN**. domain의 `AiCommentProvider.kt`는 ADR 0026 §2-3 정합 port interface (실제 provider 호출 0건 — adapter 책임).

---

## §3. 자본 path 0 touch grep (ADR 0023 §2-4 정합)

```bash
$ git log 47f2d220..0de49168 --name-only --pretty=format: | \
    grep -E "OrderService|OrderRouterKafkaListener|KillSwitchAutoToggler" | sort -u
# (empty — 0 touch GREEN)
```

→ **W4 R0~R2 (12 commits) 자본 path 0 touch GREEN**. 보수 lens (ADR 0087) 매트릭스 정합.

---

## §4. McpToolRegistry 6 tool 등록 evidence (PR #679 정합)

```
$ docker logs magicjar-api 2>&1 | grep "mcp.registry"
16:32:30.082 [-] [main] INFO  k.s.magicjar.api.mcp.McpToolRegistry -
  [mcp.registry] 등록 tool 6개: [backtest_strategy, explain_indicator, get_candles, get_journal, get_market_trend, run_screener]
```

→ **MCP 6 tool 등록 GREEN** (api 부팅 abort 직전 ApplicationContext refresh 후 McpToolRegistry는 정상 초기화 완료).

---

## §5. 5 AI provider 등록 evidence (PR #678 정합)

api 부팅 abort로 실측 보류 — spec 검증:

```bash
$ ls consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ai/
AiCommentDispatcherListener.kt   AnthropicCommentAdapter.kt
AiConfig.kt                       GeminiCommentAdapter.kt
AiCostCalculator.kt               OpenAiCommentAdapter.kt
AiProviderRegistry.kt
```

→ 3 provider (Anthropic·Gemini·OpenAI) + dispatcher + cost calculator + registry 산출물 정합 GREEN (ADR 0026 §2 amend 정합).

---

## §6. KIS Rate Limit INGESTION_LOW priority evidence (ADR 0037 amend)

worktree 검증 — `domain/.../market/ratelimit/KisCallPriority.kt` enum 정합:

```bash
$ grep -E "USER_PATH|INGESTION|BACKFILL|INGESTION_LOW" \
    domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/market/ratelimit/KisCallPriority.kt
USER_PATH,
INGESTION,
INGESTION_LOW,   // ← R1 market-data 신설 (ADR 0037 amend)
BACKFILL,
```

→ **enum 4 value GREEN**. INGESTION_LOW 신규 priority 등록 정합. 실측 26 req/min 검증은 부팅 GREEN 후속 dispatch.

---

## §7. ai_comment_log + mcp_audit 영속 evidence (보류)

api 부팅 abort로 실측 보류. 표 생성 evidence는 GREEN (§1).

후속 dispatch:
- 5 AI 진입점 1회씩 호출 후 `select count(*) from ai_comment_log` → 5건 INSERT 확인
- 6 MCP tool 1회씩 curl 호출 후 `select count(*) from mcp_audit` → 6건 INSERT 확인

---

## §8. TrendPreference V51 신규 사용자 lazy INSERT 정합 (보류)

api 부팅 abort로 실측 보류. table 생성 evidence는 GREEN (§1).

후속 dispatch:
- GET `/api/users/me/preferences/trend` → 200 응답 + default `[5,20,60]` echo
- `select count(*) from user_preference` → 1건 INSERT 확인 (lazy create)

---

## §9. 4 lens 종합 격자 — 8 항목 매트릭스

| # | 항목 | 세심함 | 영향도 | 연결 | 확장성 | 종합 |
|---|------|:--:|:--:|:--:|:--:|:--:|
| 1 | Playwright 7 spec 작성 | GREEN | GREEN | GREEN | GREEN | GREEN |
| 2 | Flyway V47~V52 6건 적용 | GREEN | GREEN | GREEN | GREEN | GREEN |
| 3 | W4 6 표 생성 + seed | GREEN | GREEN | GREEN | GREEN | GREEN |
| 4 | 자본 path 0 touch | GREEN | GREEN | GREEN | GREEN | GREEN |
| 5 | W1·W2·W3 AI import 0건 | GREEN | GREEN | GREEN | GREEN | GREEN |
| 6 | MCP 6 tool registry 등록 | GREEN | GREEN | GREEN | GREEN | GREEN |
| 7 | 3 AI provider 산출물 정합 | GREEN | GREEN | GREEN | GREEN | GREEN |
| 8 | **api 부팅 GREEN (cut prereq)** | **RED** | **RED** | **RED** | **RED** | **RED** |

→ **7 GREEN + 1 RED (블로킹)**. 본 qa PR `JpaScanConfiguration.kt` hotfix 1줄 머지 + main repo 재배포 후 #8 RED→GREEN 후속 dispatch에서 회복 검증.

---

## §10. 후속 dispatch 매트릭스 (orchestrator trigger 권고)

| 우선순위 | 항목 | 담당 |
|---------|------|------|
| P0 | 본 qa PR (JpaScanConfiguration hotfix) 머지 | 사용자 + orchestrator |
| P0 | 재배포 + 4 모듈 healthy 검증 | orchestrator |
| P0 | Playwright 7 케이스 실측 + baseline 발행 | qa |
| P1 | 5 AI 진입점 + 6 MCP tool 실측 (curl evidence) | qa |
| P1 | TrendPreference 4 bad case + lazy INSERT 실측 | qa |
| P1 | KIS Rate Limit MarketIndexSyncJob 1분 cron 실측 | qa |
| P2 | JpaScanConfiguration 장기 fix ADR 의제 | backend-core |
| P2 | v3.9.0 cut prereq 최종 게이트 + Codex 외부 review 제출 | orchestrator + 사용자 |
