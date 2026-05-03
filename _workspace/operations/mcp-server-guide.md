# magicJar MCP 서버 운영 가이드

> 사용자가 Claude Desktop · Cursor · 기타 AI client에 magicJar MCP 서버를 등록해 자연어로 호출하는 방법.

---

## §1. 서버 켜기 (R1)

magicJar `api` 컨테이너가 떠 있으면 MCP endpoint도 자동 활성화된다.

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up -d
# 또는 mock 모드
docker compose -f docker/docker-compose.yml up -d
```

엔드포인트:
- JSON-RPC 2.0 POST: `http://localhost:8080/mcp/v1/jsonrpc`
- Health 확인: `curl http://localhost:8080/actuator/health`

---

## §2. API key 발급 (선택, 권장)

```bash
# 32바이트 랜덤 hex 생성
openssl rand -hex 32 | tr -d '\n' > /tmp/mcp_key

# .env 등록 (sample)
echo "MAGICJAR_MCP_API_KEY=$(cat /tmp/mcp_key)" >> .env

# api 컨테이너 재기동 — 환경변수 반영
docker compose -f docker/docker-compose.yml restart api
```

**API key 없이도 동작 (localhost 한정)**:
`MAGICJAR_MCP_API_KEY` 미설정 시 127.0.0.1·::1 client는 인증 없이 호출 가능. **prd profile에서는 반드시 설정**.

---

## §3. Claude Desktop 등록

`~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) 편집:

```jsonc
{
  "mcpServers": {
    "magicjar": {
      "transport": {
        "type": "http",
        "url": "http://localhost:8080/mcp/v1/jsonrpc"
      },
      "headers": {
        "X-MCP-Key": "<MAGICJAR_MCP_API_KEY 값 그대로>"
      }
    }
  }
}
```

저장 후 Claude Desktop 재시작. 채팅창 하단 도구 아이콘에 `magicjar` 표기되면 정상.

> **주의**: Claude Desktop의 일부 버전은 stdio transport만 지원. 그 경우 R2의 stdio 어댑터가 추가될 때까지 Cursor·CLI·서버사이드 client를 권장.

---

## §4. Cursor 등록

Cursor 설정 → MCP servers → Add:

```json
{
  "name": "magicjar",
  "type": "http",
  "url": "http://localhost:8080/mcp/v1/jsonrpc",
  "headers": { "X-MCP-Key": "<key>" }
}
```

---

## §5. curl로 동작 확인 (디버깅)

### §5-1. tools/list

```bash
curl -s http://localhost:8080/mcp/v1/jsonrpc \
  -H "Content-Type: application/json" \
  -H "X-MCP-Key: $MAGICJAR_MCP_API_KEY" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq .
```

기대 응답:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      { "name": "get_candles", "description": "...", "inputSchema": { ... } },
      { "name": "get_quote", ... },
      { "name": "search_instruments", ... },
      { "name": "run_screener", ... },
      { "name": "get_journal", ... }
    ]
  }
}
```

### §5-2. tools/call — get_candles

```bash
curl -s http://localhost:8080/mcp/v1/jsonrpc \
  -H "Content-Type: application/json" \
  -H "X-MCP-Key: $MAGICJAR_MCP_API_KEY" \
  -d '{
    "jsonrpc":"2.0","id":2,"method":"tools/call",
    "params":{"name":"get_candles","arguments":{"code":"005930","interval":"D1"}}
  }' | jq .
```

### §5-3. tools/call — search_instruments

```bash
curl -s http://localhost:8080/mcp/v1/jsonrpc \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0","id":3,"method":"tools/call",
    "params":{"name":"search_instruments","arguments":{"query":"삼성","limit":5}}
  }' | jq .
```

---

## §6. 자연어 사용 예시 (AI client에서)

| 사용자 요청 | AI client가 호출할 tool |
|---|---|
| "삼성전자 일봉 60개" | `get_candles({code:"005930", interval:"D1"})` |
| "현재 KOSPI 가격 흐름 어때?" | `get_quote({code:"069500"})` (KODEX 200) |
| "카카오 종목코드 찾아줘" | `search_instruments({query:"카카오"})` |
| "골든크로스 매수 후보 KOSPI200에서" | `run_screener({universe:"KOSPI200", dslYaml: "..."})` |
| "오늘 내 주문 체결 이력" | `get_journal({from:"2026-05-03", to:"now"})` |

> AI client는 사용자 자연어를 받아 자동으로 적절한 tool과 argument를 선택한다. magicJar는 **순수 응답만** — AI는 magicJar의 client일 뿐 본체에 영향 0.

---

## §7. 매매 트리거가 노출되지 않는 이유 (ADR 0045 절대 원칙)

- AI는 룰을 보조하는 옵션 — 매매 결정권 0
- MCP는 read·analysis only — `place_order`·`cancel_order`·`modify_order` 등 write tool 영원히 노출 X
- AI client가 매매를 하고 싶다면 사용자가 magicJar UI에서 직접 발주 (자동매매 권한 분리)

---

## §8. 트러블슈팅

### §8-1. -32002 Authentication required
- `X-MCP-Key` 헤더 누락 또는 값 불일치
- API key 미설정 + non-localhost client (예: 다른 호스트의 Cursor)

해결: `.env`에 `MAGICJAR_MCP_API_KEY` 등록 + 헤더 설정.

### §8-2. -32003 Rate limit exceeded
- 1초 10 req / 1분 100 req 초과 — backoff 필요

해결: 호출 간격 조정. 도구가 자동 재시도 시 exponential backoff 권장.

### §8-3. -32602 Invalid params
- JSON Schema 검증 실패 (예: code 6자리 아님, interval enum 위반)

해결: tool 응답 message 확인 → arguments 수정.

### §8-4. -32601 Method not found
- 미지원 method (예: `tools/legacy_call`)

해결: `initialize`·`tools/list`·`tools/call`·`notifications/initialized`만 지원.

### §8-5. -32001 Tool not found
- name 오타 또는 미등록 tool 호출

해결: `tools/list` 결과 확인.

---

## §9. Audit 조회

```bash
docker compose exec postgres psql -U magicjar -d magicjar \
  -c "SELECT called_at, tool_name, error_code, latency_ms
      FROM mcp_call_log ORDER BY called_at DESC LIMIT 20;"
```

dev 환경 디버깅·prd 환경 운영 모니터링 양쪽에 활용.

---

## §10. 다음 라운드 (R2 — 후속)

- `explain_indicator` (정적 지표 catalog)
- `backtest_strategy` (DSL × 단일 종목 시뮬)
- `list_strategy_presets` (KIS preset YAML 10종)
- `evaluate_strategy_at` (특정 시점 entry/exit trace)
- `get_performance` (P&L·MDD·승률)
- SSE notification (subscription)
- stdio transport (Claude Desktop 직접 spawn)
