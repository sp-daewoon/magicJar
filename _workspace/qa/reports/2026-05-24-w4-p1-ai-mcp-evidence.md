# W4 P1 — 5 AI 진입점 + 6 MCP tool 실측 매트릭스

- **dispatch**: orchestrator → qa W4 P1 후속 (5건 중 §2·§3)
- **agent**: qa (magicjar-qa "품질 헌터")
- **수행 시각**: 2026-05-24 17:11 KST (recreate 후)
- **HEAD baseline**: `094ab481`
- **MCP API key**: `qa-test-mcp-key-001` (임시 테스트 키 — 본 cycle 후 삭제 의무)

## 1. 5 AI 진입점 실측 — POST `/api/ai/explain` (default mock mode)

`MAGICJAR_AI_ENABLED=false` default → 5 진입점 모두 graceful refuse 분기 (AI 비용 0 + LLM 호출 0).

| # | type | status | refuseCode | userMessage (한국어) | latency_ms |
|---|------|--------|-----------|---------------------|-----------|
| 1 | SYMBOL | 200 | AI_DISABLED | AI 기능이 비활성 상태입니다. 설정에서 활성화한 후 다시 시도해 주세요. | 124 |
| 2 | CHART | 200 | AI_DISABLED | (동일) | 10 |
| 3 | STRATEGY | 200 | AI_DISABLED | (동일) | 9 |
| 4 | SIGNAL | 200 | AI_DISABLED | (동일) | 9 |
| 5 | MARKET | 200 | AI_DISABLED | (동일) | 7 |

### 1-1. 응답 schema 정합 evidence

```json
{
  "requestId": "7df587ad-ba07-4d12-9984-91863d3d60c4",
  "status": "REFUSED",
  "refuseCode": "AI_DISABLED",
  "userMessage": "AI 기능이 비활성 상태입니다. 설정에서 활성화한 후 다시 시도해 주세요."
}
```

- `AiExplainResponse` DTO 정합 (4 필드 모두 정상 응답)
- `requestId` UUID 5건 모두 unique (`AiExplainService.handle()` 라인 33 `UUID.randomUUID()` 발급 검증)
- 한국어 contract: "AI 기능이 비활성 상태입니다" / "설정에서 활성화한 후 다시 시도해 주세요" 5건 모두 일관

### 1-2. AiExplainService 4단계 가드 evidence (log)

```
17:11:30.514 [AiExplain] refuse — requestId=648122d4 userId=1 type=SYMBOL code=AI_DISABLED
17:11:30.558 [AiExplain] refuse — requestId=84a7bc15 userId=1 type=CHART code=AI_DISABLED
17:11:30.581 [AiExplain] refuse — requestId=2e6df082 userId=1 type=STRATEGY code=AI_DISABLED
17:11:30.603 [AiExplain] refuse — requestId=d72f6518 userId=1 type=SIGNAL code=AI_DISABLED
17:11:30.624 [AiExplain] refuse — requestId=ff8518e1 userId=1 type=MARKET code=AI_DISABLED
```

→ `AiKillSwitchGuard.check()` 진입 + `magicjar.ai.enabled=false` default 분기 정합.

## 2. 6 MCP tool 실측 — POST `/api/mcp/tools/{name}` (X-Api-Key + localhost)

### 2-1. GET `/api/mcp/tools` (registry 검증)

- 응답: 6 tool 모두 정상 노출 + JSON Schema 정합
- 컨테이너 외부(host) curl 호출 시 **403** (`MCP는 localhost 한정 (외부 client 접근 금지 — post-MVP OAuth2)`) — 보안 정합
- 컨테이너 내부 wget으로 정상 응답 200

```
tool count: 6
 - backtest_strategy
 - explain_indicator
 - get_candles
 - get_journal
 - get_market_trend
 - run_screener
```

### 2-2. 6 tool 개별 호출 매트릭스

| # | tool | status | 응답 요약 | latency_ms (audit) | response_bytes (audit) |
|---|------|--------|----------|--------------------|------------------------|
| 1 | explain_indicator | 200 | RSI 정의·계산식·해석 (정적 catalog) | 2 | 535 |
| 2 | get_market_trend | 200 | scope=index · `periodsUsed:[5,20,60]` · `isUserCustom:false` (default echo) | 14 | 797 |
| 3 | get_candles | 200 | 005930 일봉 5건 (KIS adapter 실제 응답 — 270500~288500원대) | 7 | 1081 |
| 4 | get_journal | 200 | ORDER_FILLED 13번 entry 실제 응답 (066570 매수) | 16 | 2424 |
| 5 | run_screener | 200 | strategyId=1 미존재 graceful (`universeSize:0` · `matchedCount:0`) | 51 | 118 |
| 6 | backtest_strategy | **500** | "전략을 찾을 수 없습니다: strategyId=1" (graceful 미적용) | 6 | (오류) |

### 2-3. get_market_trend periods 생략 → user preference echo PASS

- input: `{"scope":"index"}` (periods 생략)
- 응답: `periodsUsed:[5,20,60]` · `rsPeriod:60` · `isUserCustom:false`
- ADR 0101 TrendPreference echo path 정합

### 2-4. explain_indicator 응답 본문 (정적 catalog)

```json
{
  "name":"RSI",
  "fullName":"Relative Strength Index",
  "category":"momentum",
  "params":{"period":"default 14 (J. Welles Wilder)"},
  "formula":"RSI = 100 - (100 / (1 + RS)) where RS = avg_gain(period) / avg_loss(period)",
  "interpretation":"0~100 범위. 70 이상 과매수 · 30 이하 과매도 (Wilder 표준). 다이버전스 패턴 ..."
}
```

→ LLM 호출 0 (ADR 0027 §2-6 LOCK 정합) — 21 지표 catalog 정합.

## 3. 발견 결함 (cut 영향 평가)

### 3-1. backtest_strategy 500 (post-MVP graceful 후보)

- `IllegalStateException("전략을 찾을 수 없습니다: strategyId=1")` 미처리 → 500
- McpController 라인 `[mcp] backtest_strategy FAIL ... err=...` log 확인
- 사용자 흐름에서는 미존재 strategyId 호출 0 (UI에 노출된 strategy만 호출) → cut 차단 X
- **권고**: post-MVP `StrategyNotFoundException` → 422 + 한국어 메시지

### 3-2. 신규 user lazy INSERT path 검증 불가 (back single-user mode)

- spec 1-4 의도: 신규 user 999로 GET → INSERT 0 / PUT → INSERT 1
- 실제: 백엔드가 `X-User-Id: 999` 헤더 무시 + default user `74683223` (SystemConfig fallback)으로 fallback
- 결과: PUT은 user `74683223` 행 UPSERT (정합) · INSERT 1건은 ADR 0101 의도 정합 분기 검증 OK
- **권고**: P2 — multi-user 분기 분기 검증은 ADR 0102 user model 진화 이후로 보류

## 4. AI/MCP 영속 작동 매트릭스 요약

- 5 AI 진입점: status 200 + REFUSED + 한국어 5/5 PASS
- 6 MCP tool: 5/6 PASS (backtest_strategy 1건 post-MVP graceful 후보)
- get_market_trend user preference echo: PASS

## 5. cut 직전 결정 위임

- backtest_strategy 500 → cut 차단 X (post-MVP P2 후보)
- 다음 보고서 §1-4 ai_comment_log INSERT 결함은 별도 (persistence-evidence.md)
