# magicJar MCP Server 명세

> **선행**: `docs/architecture/master-architecture.md` v2 §1-5·§3-4·§D.18, ADR 0045 (MCP 서버 미래)
> **트랙**: T17 (W4)
> **상태**: R1 — read·analysis tool 5종 구현
> **작성일**: 2026-05-03
> **작성자**: architect (opus 4.7 1M)

---

## §1. 비전

magicJar는 **MCP 서버**를 외부에 노출한다. Claude Desktop·Cursor·기타 AI 클라이언트가 magicJar의 도메인 데이터·분석 결과를 호출 가능하다. **우리가 server, AI가 client.**

```
Claude Desktop / Cursor / Custom AI client (= MCP client)
        ↓ JSON-RPC 2.0 over HTTP (1차) / SSE / stdio (post-MVP)
   magicJar api 모듈 /mcp/v1/jsonrpc endpoint
        ↓
   McpServerController → McpDispatcher → tool handlers
        ↓
   기존 application service (CandleQueryService · ScreenerExecutor · ...)
        ↓
   DB · Redis · 도메인 엔진 (LLM 호출 0)
```

### §1-1. 절대 원칙 (ADR 0045)

1. **read·analysis only** — 매매 트리거 tool은 영원히 노출 X (`place_order`·`cancel_order`·`modify_order` 등 전 종류 금지)
2. **AI 호출 0** — MCP 서버 본체는 LLM 호출하지 않는다. 모든 응답은 DB·캐시·순수 도메인 엔진 결과
3. **인증 필수** — 1차 localhost 한정 + API key. post-MVP OAuth2
4. **모든 호출 audit** — `mcp_call_log` 테이블에 호출자·tool·params·응답 크기·latency·에러 기록

---

## §2. Protocol — MCP over JSON-RPC 2.0

### §2-1. Transport

| 단계 | Transport | 용도 |
|---|---|---|
| **R1 (현재)** | HTTP POST `/mcp/v1/jsonrpc` | 모든 method (initialize·tools/list·tools/call) |
| R2 (post-MVP) | SSE `GET /mcp/v1/events` | server-initiated notification (subscription) |
| R3 (post-MVP) | stdio | Claude Desktop 직접 spawn 시 |

R1은 polling 모델로 충분 — tool 응답은 동기. notification은 향후 추가.

### §2-2. JSON-RPC 2.0 envelope

요청:
```json
{
  "jsonrpc": "2.0",
  "id": "<uuid-or-int>",
  "method": "tools/call",
  "params": { "name": "get_candles", "arguments": { "code": "005930", "interval": "D1" } }
}
```

성공 응답:
```json
{
  "jsonrpc": "2.0",
  "id": "<echo>",
  "result": { ... }
}
```

에러 응답:
```json
{
  "jsonrpc": "2.0",
  "id": "<echo>",
  "error": { "code": -32602, "message": "Invalid params: code must match ^[0-9]{6}$" }
}
```

### §2-3. 에러 코드 (JSON-RPC 표준 + 확장)

| code | 의미 | HTTP status |
|---|---|---|
| -32700 | Parse error (JSON 깨짐) | 400 |
| -32600 | Invalid request (envelope 불량) | 400 |
| -32601 | Method not found | 404 |
| -32602 | Invalid params (schema validation 실패) | 400 |
| -32603 | Internal error | 500 |
| -32000 | Tool execution error (DB·도메인 엔진 실패) | 500 |
| -32001 | Tool not found (`tools/call`의 name 미등록) | 404 |
| -32002 | Authentication required | 401 |
| -32003 | Rate limit exceeded | 429 |
| -32004 | Forbidden tool (write·trading tool 등) | 403 |

---

## §3. Method 카탈로그

### §3-1. `initialize` (필수, 핸드셰이크)

요청 params:
```json
{
  "protocolVersion": "2025-03-26",
  "capabilities": { "roots": { "listChanged": false } },
  "clientInfo": { "name": "Claude Desktop", "version": "0.7.0" }
}
```

응답 result:
```json
{
  "protocolVersion": "2025-03-26",
  "capabilities": {
    "tools": { "listChanged": false },
    "resources": {},
    "prompts": {}
  },
  "serverInfo": { "name": "magicjar-mcp", "version": "0.1.0" },
  "instructions": "magicJar는 한국 주식 자동매매 시스템의 read-only MCP 서버입니다. 캔들·시세·종목·매매일지·스크리너를 호출할 수 있으나 매매 트리거(주문 발행)는 노출하지 않습니다."
}
```

### §3-2. `tools/list`

응답 result:
```json
{
  "tools": [
    { "name": "get_candles", "description": "...", "inputSchema": { ... } },
    ...
  ]
}
```

### §3-3. `tools/call`

요청 params:
```json
{ "name": "<tool_name>", "arguments": { ... } }
```

응답 result (MCP 표준):
```json
{
  "content": [
    { "type": "text", "text": "<JSON-encoded payload>" }
  ],
  "isError": false
}
```

페이로드는 텍스트 1개 — JSON 직렬화 후 string으로 감싼다 (MCP 표준 권장).

### §3-4. `notifications/initialized`

client가 initialize 직후 보내는 ack. server는 무시 (200 빈 응답).

---

## §4. Tool 카탈로그 (R1 5개 + R2 5개)

| # | name | 권한 | R1 구현 | 의존 |
|---|---|---|---|---|
| 1 | `get_candles` | read | ✅ | `CandleQueryService` |
| 2 | `get_quote` | read | ✅ | `QuoteQueryService` |
| 3 | `search_instruments` | read | ✅ | `InstrumentService.search` |
| 4 | `run_screener` | analysis | ✅ | `ScreenerExecutor` + `StrategyDslParser` |
| 5 | `get_journal` | read | ✅ | `OrderApiService` (orders + order_events) |
| 6 | `explain_indicator` | read | R2 | 정적 catalog (LLM 호출 X) |
| 7 | `backtest_strategy` | analysis | R2 | `BacktestEngine` |
| 8 | `list_strategy_presets` | read | R2 | KIS preset YAML 10종 |
| 9 | `evaluate_strategy_at` | analysis | R2 | `StrategyDslEvaluator` |
| 10 | `get_performance` | read | R2 | `PerformanceQueryService` |

### §4-1. `get_candles` (read)

**용도**: 한국 주식 종목의 OHLCV 캔들 시계열을 조회한다 (수정주가 default).

**input_schema**:
```json
{
  "type": "object",
  "properties": {
    "code":     { "type": "string", "pattern": "^[0-9]{6}$", "description": "KRX 6자리 종목코드" },
    "interval": { "type": "string", "enum": ["D1","M1","M5","M15","M30","H1","DAY"], "default": "D1" },
    "from":     { "type": "string", "format": "date-time", "description": "ISO-8601 (옵션, default: 봉별 적정 lookback)" },
    "to":       { "type": "string", "format": "date-time", "description": "ISO-8601 (옵션, default: now)" },
    "adjusted": { "type": "boolean", "default": true, "description": "수정주가 여부 (룩어헤드 차단 위해 기본 true)" }
  },
  "required": ["code"]
}
```

**output_schema**:
```json
{
  "type": "object",
  "properties": {
    "code":     { "type": "string" },
    "interval": { "type": "string" },
    "candles": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "timestamp": { "type": "string", "description": "D1=KST yyyy-MM-dd, 분봉=epoch second" },
          "open":      { "type": "number" },
          "high":      { "type": "number" },
          "low":       { "type": "number" },
          "close":     { "type": "number" },
          "volume":    { "type": "integer" }
        }
      }
    },
    "count": { "type": "integer" }
  }
}
```

**security_note**: 공개 시세 데이터, user_id 무관. rate limit만 적용.

### §4-2. `get_quote` (read)

**용도**: 종목의 현재가를 3-tier fallback (LIVE → CACHED → LAST_DAY_CLOSE)로 조회한다.

**input_schema**:
```json
{
  "type": "object",
  "properties": { "code": { "type": "string", "pattern": "^[0-9]{6}$" } },
  "required": ["code"]
}
```

**output_schema**:
```json
{
  "type": "object",
  "properties": {
    "code":      { "type": "string" },
    "price":     { "type": "number" },
    "source":    { "type": "string", "enum": ["LIVE","CACHED","LAST_DAY_CLOSE"] },
    "stale":     { "type": "boolean" },
    "session":   { "type": "string" },
    "asOf":      { "type": "string", "format": "date-time" }
  }
}
```

### §4-3. `search_instruments` (read)

**용도**: 한글 이름·코드·alias로 종목을 검색한다.

**input_schema**:
```json
{
  "type": "object",
  "properties": {
    "query": { "type": "string", "minLength": 1, "description": "한글·영문·종목코드 일부" },
    "limit": { "type": "integer", "minimum": 1, "maximum": 100, "default": 20 }
  },
  "required": ["query"]
}
```

**output_schema**:
```json
{
  "type": "object",
  "properties": {
    "instruments": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "code":   { "type": "string" },
          "name":   { "type": "string" },
          "market": { "type": "string", "description": "KOSPI·KOSDAQ·ETF·ETN 등" }
        }
      }
    },
    "count": { "type": "integer" }
  }
}
```

### §4-4. `run_screener` (analysis)

**용도**: 유니버스 × DSL 전략으로 매칭 종목을 발굴한다 (룰 기반, AI 0).

**input_schema**:
```json
{
  "type": "object",
  "properties": {
    "universe":    { "type": "string", "enum": ["KOSPI200","KOSDAQ150","ETF","ETN","ALL_LISTED","CUSTOM"] },
    "customCodes": { "type": "array", "items": { "type": "string", "pattern": "^[0-9]{6}$" }, "description": "universe=CUSTOM 일 때 필수" },
    "dslYaml":     { "type": "string", "description": ".kis.yaml superset 전략 DSL" },
    "asOfDate":    { "type": "string", "format": "date", "description": "기준일 (default 오늘)" }
  },
  "required": ["universe", "dslYaml"]
}
```

**output_schema**:
```json
{
  "type": "object",
  "properties": {
    "runId":        { "type": "string", "description": "감사 trail용 — 이후 REST /api/screener/{id} 조회 가능" },
    "totalScanned": { "type": "integer" },
    "matchCount":   { "type": "integer" },
    "matches": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "code":         { "type": "string" },
          "name":         { "type": "string" },
          "entryReasons": { "type": "array", "items": { "type": "object" } }
        }
      }
    }
  }
}
```

**security_note**: read·analysis. 매매 발주 X. 결과는 read-only screen 결과만.

### §4-5. `get_journal` (read)

**용도**: 사용자의 주문·체결 이력을 조회한다 (1번 트랙 orders + order_events).

**input_schema**:
```json
{
  "type": "object",
  "properties": {
    "userId": { "type": "string", "format": "uuid", "description": "사용자 UUID (1차 default 시드 사용자)" },
    "from":   { "type": "string", "format": "date-time" },
    "to":     { "type": "string", "format": "date-time" },
    "code":   { "type": "string", "pattern": "^[0-9]{6}$", "description": "종목 필터 (옵션)" },
    "limit":  { "type": "integer", "minimum": 1, "maximum": 200, "default": 100 }
  }
}
```

**output_schema**:
```json
{
  "type": "object",
  "properties": {
    "userId": { "type": "string" },
    "orders": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "orderId":      { "type": "string" },
          "code":         { "type": "string" },
          "side":         { "type": "string" },
          "qty":          { "type": "integer" },
          "price":        { "type": "number" },
          "orderType":    { "type": "string" },
          "status":       { "type": "string" },
          "filledQty":    { "type": "integer" },
          "avgFillPrice": { "type": "number" },
          "createdAt":    { "type": "string", "format": "date-time" }
        }
      }
    },
    "count": { "type": "integer" }
  }
}
```

**security_note**: user_id scope 필수. 1차는 단일 시드 사용자만 — 멀티테넌트는 인증(post-MVP)과 함께 도입.

### §4-6 ~ §4-10 (R2 — 후속 라운드)

| name | 용도 | 의존 |
|---|---|---|
| `explain_indicator` | 80지표 메타·계산식·해석 (정적 catalog) | `IndicatorCatalog` (T5 산출물) |
| `backtest_strategy` | DSL × 단일 종목 백테스트 → 성과 카드 | `BacktestEngine` (T9) |
| `list_strategy_presets` | KIS preset YAML 10종 | static yaml resources |
| `evaluate_strategy_at` | 특정 시점 entry/exit 조건 평가 trace | `StrategyDslEvaluator` |
| `get_performance` | 사용자 P&L·MDD·승률 | `PerformanceQueryService` (T14) |

---

## §5. 인증·인가·rate limit

### §5-1. 인증 (R1)

| 모드 | 조건 |
|---|---|
| **API key** | 헤더 `X-MCP-Key: <env MAGICJAR_MCP_API_KEY>` 일치 |
| **localhost-only fallback** | API key 미설정 시 + remote address ∈ {127.0.0.1, ::1} 만 허용 |
| **둘 다 실패** | -32002 Authentication required |

dev profile은 fallback default 동작 — 사용자가 API key 발급 X여도 localhost client는 호출 가능.
prd profile은 API key 필수 (CI에서 보강).

### §5-2. 인가

- 모든 R1 tool은 `read_only: true`
- write tool 호출 시도 → -32004 Forbidden (R1에선 등록 자체를 안 하므로 -32001 Tool not found 가 먼저 발생)
- user-scoped tool (`get_journal`)은 1차 default 시드 사용자만. 다른 userId 명시 시 동일 응답 (멀티테넌트 인증 도입 시 반려)

### §5-3. Rate limit

| 윈도우 | 한도 |
|---|---|
| 1초 | 10 req per client (X-MCP-Key 또는 IP) |
| 1분 | 100 req |

초과 시 -32003 Rate limit exceeded + HTTP 429. R1은 in-memory bucket (단일 인스턴스 가정), R2부터 Redis bucket.

### §5-4. CORS

`/mcp/**`은 CORS 미허용 (브라우저 직접 호출은 차단). MCP client는 native app·CLI·서버사이드만 가정.

---

## §6. Audit — `mcp_call_log` (V18)

```sql
CREATE TABLE mcp_call_log (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    client_key_hash VARCHAR(64),         -- API key의 sha256 prefix 16자 (raw 저장 X)
    client_address VARCHAR(45),
    method VARCHAR(64) NOT NULL,         -- 'tools/call' · 'tools/list' · 'initialize'
    tool_name VARCHAR(64),               -- method='tools/call' 일 때만
    params JSONB,
    response_size_bytes INT,
    latency_ms INT,
    error_code INT,                      -- JSON-RPC error.code (정상은 NULL)
    error_message TEXT,
    called_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mcp_call_time ON mcp_call_log(called_at DESC);
CREATE INDEX idx_mcp_call_tool ON mcp_call_log(tool_name, called_at DESC);
```

**기록 시점**: 모든 JSON-RPC 호출 — 성공·실패·인증 실패·rate limit 초과 모두.
**민감정보**: API key는 hash prefix만. params에는 client가 보낸 원문 그대로 — code·날짜·dslYaml 등은 민감 X (KIS 키·secret은 절대 params에 들어오지 않음).

---

## §7. 디렉터리 구조 (api 모듈 내)

```
api/src/main/kotlin/com/mj/api/mcp/
├── McpServerController.kt          # JSON-RPC endpoint
├── McpDispatcher.kt                # method → handler 분기
├── McpToolRegistry.kt              # tool 카탈로그 + JSON Schema validation
├── McpAuth.kt                      # API key + localhost fallback 검증
├── McpRateLimiter.kt               # in-memory bucket (R1)
├── McpAuditLogger.kt               # mcp_call_log INSERT
├── McpJsonRpc.kt                   # envelope DTO + 에러 코드 enum
├── tool/
│   ├── McpTool.kt                  # tool 인터페이스 (name·schema·invoke)
│   ├── GetCandlesTool.kt
│   ├── GetQuoteTool.kt
│   ├── SearchInstrumentsTool.kt
│   ├── RunScreenerTool.kt
│   └── GetJournalTool.kt
└── infrastructure/
    └── McpCallLogJpa.kt            # mcp_call_log JPA entity + repo
```

---

## §8. 운영 (사용자가 Claude Desktop·Cursor 등록)

`_workspace/operations/mcp-server-guide.md` 참조. 핵심:

```jsonc
// claude_desktop_config.json
{
  "mcpServers": {
    "magicjar": {
      "transport": { "type": "http", "url": "http://localhost:8080/mcp/v1/jsonrpc" },
      "headers":   { "X-MCP-Key": "<MAGICJAR_MCP_API_KEY>" }
    }
  }
}
```

자연어 → AI client → magicJar 호출 예:
- "삼성전자 일봉 60개 보여줘" → `get_candles({ code: "005930", interval: "D1" })`
- "KOSPI200에서 골든크로스 종목 찾아줘" → `run_screener({ universe: "KOSPI200", dslYaml: "..." })`
- "내 오늘 주문 체결 이력" → `get_journal({ from: "...", to: "..." })`

---

## §9. 비범위 (post-MVP·Wave 5+)

- write/매매 트리거 tool 영원히 X (ADR 0045 절대 원칙)
- SSE notification (R2)
- stdio transport (Claude Desktop 직접 spawn) (R2)
- OAuth2 (R3)
- Multi-tenant API key + per-user 권한 (R3)
- Resource·Prompt 기능 (MCP 표준 capability — R2)
- Public 인터넷 노출 시 HTTPS·rate limit Redis 분산 (실서비스 진입 시)
