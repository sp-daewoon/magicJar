# ADR 0027: MCP 서버 미래 노출 (W4)

- 상태: **Accepted (2026-05-24 amend — tool 5 → 6 (`get_market_trend` 신규) + V21 예약 폐기 → V45 LOCK + JSON Schema 본문 추가 + alert routing 매트릭스 동반)**
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M) — 사용자 비전(2026-05-02) 명시 정합 + brainstorming 결재 cec6a4d6 (2026-05-24)
- master §위치: `docs/architecture/master-architecture.md` v2 §1-5·§D.18·§3-4
- 트랙: T-MCP (MCP Server, W4)
- 선행: ADR 0001, 0026 (AI Option)
- 2026-05-24 amend 관련: ADR 0094 (운영 단계 — MCP 외부 노출 lens) · ADR 0095 (alert routing — MCP 분당 호출 100 초과 임계) · ADR 0100 (T-MARKET — `get_market_trend` tool input source) · ADR 0101 (TrendPreference — periods 생략 시 user pref echo)

---

## 1. 맥락

사용자 비전(2026-05-02 원문):

> "AI를 접목시킬 수 있는거지 이 시스템에 대한 MCP를 새로 만들어서 활용할 수 있게 하는거지"

magicJar는 미래에 **MCP 서버를 외부에 노출**한다 — Claude/Cursor 등 AI 클라이언트가 magicJar의 도메인을 호출 가능하게. 우리가 server, AI가 client. 1차안의 "KIS MCP를 우리가 client로 호출" 가정은 폐기.

## 2. 결정

### 2-1. MCP server 위치 (default 12 §4)

**1차 — `api` 모듈 내 endpoint** (별도 sidecar 컨테이너 X). W4 본격 구현 시 결정 가능 (sidecar로 분리할 정당성 있으면 ADR 추가).

```
api/
├── src/main/kotlin/kr/spdaewoon/magicjar/api/mcp/
│   ├── McpController.kt           # MCP protocol HTTP/SSE endpoint
│   ├── McpToolRegistry.kt         # tool 6종 등록·라우팅 (2026-05-24 amend — 5→6)
│   ├── tool/
│   │   ├── GetCandlesTool.kt
│   │   ├── RunScreenerTool.kt
│   │   ├── GetJournalTool.kt
│   │   ├── ExplainIndicatorTool.kt
│   │   ├── BacktestStrategyTool.kt
│   │   └── GetMarketTrendTool.kt  # 2026-05-24 amend — T-MARKET 합류 (ADR 0100)
│   └── auth/
│       └── ApiKeyAuthFilter.kt    # 1차 — API key
```

### 2-2. tool 6종 (2026-05-24 amend — 5 → 6)

| tool | 입력 | 출력 | 권한 | LLM 호출 |
|---|---|---|---|---|
| `get_candles` | `code`·`interval`·`from`·`to` | OHLCV array (adjusted default) | read | 0 |
| `run_screener` | `universe`·`dslYaml` | code list + 강도 랭킹 | read | 0 |
| `get_journal` | `from`·`to`·`code?` | 매매일지 entries + summary | read | 0 |
| `explain_indicator` | `name`·`params`·`context?` | 지표/차트/패턴의 정의·계산식·해석 (정적 catalog — LLM 호출 X) | read | 0 |
| `backtest_strategy` | `dslYaml`·`period`·`universe` | 성과 카드(Sharpe·MDD·승률) + trades | read | 0 |
| **`get_market_trend` (2026-05-24 amend — 신규)** | **`scope` (index\|sector\|both) + `periods?` + `rsPeriod?`** | **지수·섹터 추세 snapshot (echo periods · isUserCustom 필드)** | **read** | **0** |

**모두 read 전용**. write tool (주문 발행 등)은 안전성 검증 후 post-MVP.

### 2-3. JSON Schema (2026-05-24 amend — `get_market_trend` JSON Schema 본문 추가)

각 tool은 MCP protocol 정합 JSON Schema 정의 — Claude/Cursor가 자동 발견·호출 가능:

```json
{
  "name": "get_candles",
  "description": "Get OHLCV candles for a Korean stock instrument (adjusted prices by default).",
  "inputSchema": {
    "type": "object",
    "properties": {
      "code": { "type": "string", "pattern": "^[0-9]{6}$" },
      "interval": { "type": "string", "enum": ["1m","5m","15m","30m","60m","D1","W1","MO1"] },
      "from": { "type": "string", "format": "date-time" },
      "to": { "type": "string", "format": "date-time" }
    },
    "required": ["code", "interval"]
  }
}
```

**2026-05-24 amend — `get_market_trend` JSON Schema** (ADR 0100·0101 정합):

```json
{
  "name": "get_market_trend",
  "description": "지수·섹터 추세 snapshot (사용자 MA periods 기본 적용 — ADR 0101 TrendPreference). periods 생략 시 user preference echo, 명시 시 일회성 override.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "scope": {
        "type": "string",
        "enum": ["index", "sector", "both"],
        "description": "조회 범위: index=4 지수 (KOSPI·KOSDAQ·NASDAQ·S&P) · sector=KRX 21 업종 + ranking · both=양쪽"
      },
      "periods": {
        "type": "array",
        "items": { "type": "integer", "minimum": 1, "maximum": 252 },
        "minItems": 1,
        "maxItems": 5,
        "description": "MA 기간 list (1~5개·각 1~252일·sorted ASC). 생략 시 user preference (ADR 0101) 적용."
      },
      "rsPeriod": {
        "type": "integer",
        "minimum": 1,
        "maximum": 252,
        "default": 60,
        "description": "RS rating 비교 기간 (Mansfield RS — 섹터 vs KOSPI). default 60."
      }
    },
    "required": ["scope"]
  }
}
```

응답 schema (예시):

```json
{
  "type": "object",
  "properties": {
    "indexTrends": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "code": { "type": "string" },
          "ma": { "type": "object", "description": "Map<period, value> — 예: {5: 2670.12, 20: 2685.34, 60: 2710.55}" },
          "crossSignal": { "type": "string", "enum": ["GOLDEN", "DEAD", "NEUTRAL"] },
          "trendLabel": { "type": "string", "enum": ["UP", "SIDEWAYS", "DOWN"] },
          "periodsUsed": { "type": "array", "items": { "type": "integer" } },
          "isUserCustom": { "type": "boolean", "description": "false=default · true=사용자 설정 적용" },
          "dataInsufficient": { "type": "boolean", "description": "candles.size < longMa 시 true — frontend 데이터 부족 한국어 표시" }
        }
      }
    },
    "sectorRanking": {
      "type": "object",
      "properties": {
        "todayTop": { "type": "array", "description": "1d 등락률 0.7 + 거래대금 0.3 가중" },
        "longTop": { "type": "array", "description": "RS rating + maPeriods 우상향 fit" },
        "periodsUsed": { "type": "array", "items": { "type": "integer" } },
        "rsPeriod": { "type": "integer" }
      }
    }
  }
}
```

### 2-4. 인증

| 단계 | 방식 |
|---|---|
| 1차 (W4) | API key (env `MAGICJAR_MCP_API_KEY`) — localhost 한정 |
| 2차 (post-MVP) | OAuth2 (외부 client) |
| 3차 (실서비스) | Multi-tenant API key + per-user 권한 |

API key는 `mcp_audit` 테이블에 `client_id` 매핑.

### 2-5. audit (2026-05-24 amend — V21 예약 폐기 → V45 LOCK)

```sql
-- V45__mcp_audit.sql (2026-05-24 amend — 본 ADR 권위)
CREATE TABLE mcp_audit (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    client_id VARCHAR(64),
    tool VARCHAR(64) NOT NULL,
    params JSONB,
    response_size_bytes INT,
    latency_ms INT,
    error_message TEXT,
    called_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mcp_user_time ON mcp_audit(user_id, called_at DESC);
CREATE INDEX idx_mcp_tool_time ON mcp_audit(tool, called_at DESC);
```

호출 로그·비용·드리프트 추적.

**2026-05-24 amend — alert routing (ADR 0095 amend 동반)**:
- MCP 분당 호출 100 초과 시 Slack WARN (외부 client abuse risk)
- `get_market_trend` tool 호출 빈도 dashboard panel (Grafana — backend-core P0-OPS-2 stack reuse)

### 2-6. 오프라인 동작

- 모든 tool은 **read 전용** — DB·캐시 직접 접근, AI provider 의존 0.
- LLM 호출 0 — `explain_indicator`도 정적 catalog (지표 정의·계산식 catalog 영속, post-W4 보강).
- magicJar 본체가 down이면 MCP server도 down — 별도 sidecar 분리 시 가용성 분리 가능 (post-MVP).

## 3. 결과·영향

- T-MCP (W4) 트랙 진입 시 본 ADR 직접 의존.
- W4 = T-AI (AI Option Console) + T-MCP (MCP server) — 둘 다 W4 안에 포함 (사용자 결정 default 12).
- magicJar는 **server**. AI 클라이언트가 magicJar의 사용자.

## 4. 검토 대안

- **(a) sidecar 컨테이너** — 가용성 분리 가능, 운영 복잡도↑. post-MVP 결정.
- **(b) endpoint 미노출** — 사용자 비전 위반.
- **(c) api 모듈 내 endpoint 1차 (채택)** — 운영 단순 + sidecar 전환 path 확보.

## 5. References

- master-architecture.md §1-5, §3-4, §D.18
- ADR 0001 (비전 v2 — 우리가 server)
- ADR 0026 (AI 옵션 — explain_indicator는 LLM 호출 X)
- MCP protocol https://modelcontextprotocol.io/
- **2026-05-24 amend 동반**: ADR 0094 (운영 단계) · ADR 0095 (alert routing — MCP 분당 100 초과 WARN) · ADR 0100 (T-MARKET — `get_market_trend` input source) · ADR 0101 (TrendPreference — periods 생략 시 user pref echo)

---

## 6. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-05-03 | ADR 0027 신설 — MCP 서버 미래 노출 (우리가 server · AI client · tool 5종 read-only · API key 인증) | architect |
| 2026-05-24 | **amend — §2-2 tool 5 → 6 (`get_market_trend` 신규 · T-MARKET 합류 · LLM 호출 0) + §2-3 `get_market_trend` JSON Schema 본문 추가 (request·response 양방) + §2-5 영속 참조 V21 예약 폐기 → V45(mcp_audit) LOCK + idx_mcp_tool_time 인덱스 추가 + MCP 분당 호출 100 초과 alert (ADR 0095 amend 동반)** — 사용자 brainstorming 결재 cec6a4d6 정합. orchestrator → architect R0 dispatch. ADR 0094·0100·0101 동반. 운영 코드 0 touch. (architect, opus 4.7 1M) |
