-- T17 (W4) — MCP 서버 호출 audit 로그.
-- ADR 0045 §2-5. 모든 JSON-RPC 호출(성공·실패·인증실패·rate limit) 기록.
-- API key 원문 저장 X — sha256 prefix 16자만(client_key_hash).

CREATE TABLE mcp_call_log (
    id                  BIGSERIAL PRIMARY KEY,
    request_id          VARCHAR(64) NOT NULL,
    client_key_hash     VARCHAR(64),
    client_address      VARCHAR(45),
    method              VARCHAR(64) NOT NULL,
    tool_name           VARCHAR(64),
    params              JSONB,
    response_size_bytes INT,
    latency_ms          INT,
    error_code          INT,
    error_message       TEXT,
    called_at           TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mcp_call_time ON mcp_call_log (called_at DESC);
CREATE INDEX idx_mcp_call_tool ON mcp_call_log (tool_name, called_at DESC);
CREATE INDEX idx_mcp_call_request ON mcp_call_log (request_id);
