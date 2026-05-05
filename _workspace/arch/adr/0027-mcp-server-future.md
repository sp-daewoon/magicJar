# ADR 0027: MCP 서버 미래 노출 (W4)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M) — 사용자 비전(2026-05-02) 명시 정합
- master §위치: `docs/architecture/master-architecture.md` v2 §1-5·§D.18·§3-4
- 트랙: T17 (W4)
- 선행: ADR 0001, 0046

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
├── src/main/kotlin/com/mj/api/mcp/
│   ├── McpController.kt           # MCP protocol HTTP/SSE endpoint
│   ├── McpToolRegistry.kt         # tool 5종 등록·라우팅
│   ├── tool/
│   │   ├── GetCandlesTool.kt
│   │   ├── RunScreenerTool.kt
│   │   ├── GetJournalTool.kt
│   │   ├── ExplainIndicatorTool.kt
│   │   └── BacktestStrategyTool.kt
│   └── auth/
│       └── ApiKeyAuthFilter.kt    # 1차 — API key
```

### 2-2. tool 5종 (1차)

| tool | 입력 | 출력 | 권한 |
|---|---|---|---|
| `get_candles` | `code`·`interval`·`from`·`to` | OHLCV array (adjusted default) | read |
| `run_screener` | `universe`·`dslYaml` | code list + 강도 랭킹 | read |
| `get_journal` | `from`·`to`·`code?` | 매매일지 entries + summary | read |
| `explain_indicator` | `name`·`params`·`context?` | 지표/차트/패턴의 정의·계산식·해석 (정적 catalog — LLM 호출 X) | read |
| `backtest_strategy` | `dslYaml`·`period`·`universe` | 성과 카드(Sharpe·MDD·승률) + trades | read |

**모두 read 전용**. write tool (주문 발행 등)은 안전성 검증 후 post-MVP.

### 2-3. JSON Schema

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

### 2-4. 인증

| 단계 | 방식 |
|---|---|
| 1차 (W4) | API key (env `MAGICJAR_MCP_API_KEY`) — localhost 한정 |
| 2차 (post-MVP) | OAuth2 (외부 client) |
| 3차 (실서비스) | Multi-tenant API key + per-user 권한 |

API key는 `mcp_audit` 테이블에 `client_id` 매핑.

### 2-5. audit (V21)

```sql
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
```

호출 로그·비용·드리프트 추적.

### 2-6. 오프라인 동작

- 모든 tool은 **read 전용** — DB·캐시 직접 접근, AI provider 의존 0.
- LLM 호출 0 — `explain_indicator`도 정적 catalog (지표 정의·계산식 catalog 영속, post-W4 보강).
- magicJar 본체가 down이면 MCP server도 down — 별도 sidecar 분리 시 가용성 분리 가능 (post-MVP).

## 3. 결과·영향

- T17 (W4) 트랙 진입 시 본 ADR 직접 의존.
- W4 = T16 (AI Option Console) + T17 (MCP server) — 둘 다 W4 안에 포함 (사용자 결정 default 12).
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
