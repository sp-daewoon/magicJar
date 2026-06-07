# CHART 진입점 (claude-cli provider 응답 sample)

> 발행: 2026-06-08 — 종목상세 AI 차트해석 Claude CLI provider 연동 (T-20260608-0329)
> 권위: ADR 0026 §2-2(CHART 진입점) · ADR 0112 발굴 bridge 재사용 · consumer `ClaudeCliCommentAdapter`

## 운영 전제

```bash
# .env (운영 전환 — orchestrator)
MAGICJAR_AI_ENABLED=true
MAGICJAR_AI_PROVIDER=claude-cli
# 둘 중 하나 — bridge 우선(컨테이너), 미설정 시 컨테이너 내부 claude CLI 직접 실행
MAGICJAR_AI_CLAUDE_CLI_BRIDGE_URL=http://host.docker.internal:8787
# MAGICJAR_AI_CLAUDE_CLI_BRIDGE_URL=        # (공란이면 로컬 `claude -p` CLI fallback)
```

## 요청 (frontend 무변경 — 기존 차트해석 버튼)

```bash
curl -X POST http://localhost:8080/api/ai/explain \
  -H "Content-Type: application/json" \
  -d '{
    "type": "CHART",
    "userId": 1,
    "context": {
      "code": "005930",
      "timeframe": "1d",
      "indicators": {"ma_20": 71200, "ma_60": 68400, "rsi_14": 58.2},
      "priceFlow": "최근 20봉 우상향, 60일선 위 정착"
    }
  }'
```

## 응답 (HTTP 200 · status=ACCEPTED)

```json
{
  "requestId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "status": "ACCEPTED",
  "refuseCode": null,
  "userMessage": null
}
```

## STOMP `/topic/ai.explain.{requestId}` fanout (claude-cli · bridge 경로)

```json
{
  "eventId": "f1a2b3c4-...",
  "requestId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "userId": 1,
  "commentType": "CHART",
  "provider": "claude-cli",
  "model": "claude-cli-bridge",
  "promptTemplate": "chart_v1",
  "content": "삼성전자(005930) 일봉은 20일선이 60일선 위에 정렬된 정배열 구간이며, 최근 20봉 우상향 흐름으로 추세 추종 국면입니다. RSI 14가 58 수준이라 과열은 아니고 상승 여력이 남아 있는 중립~강세 구간입니다. 다만 60일선(68,400) 이탈 시 추세 훼손 신호이므로 손절 기준선으로 참고하시기 바랍니다. (advisory only — 매매 결정권 0)",
  "tokensIn": 0,
  "tokensOut": 0,
  "latencyMs": 4820,
  "costUsd": "0",
  "cached": false,
  "graceful": false,
  "errorMessage": null,
  "occurredAt": "2026-06-08T03:45:00+09:00",
  "schemaVersion": 1
}
```

## 비고

- `provider="claude-cli"` / `model="claude-cli-bridge"`(bridge) 또는 `"claude-cli"`(로컬 CLI).
- **키 불요**: `MAGICJAR_AI_MOCK_MODE=auto` 라도 provider=claude-cli 는 mock 강등 안 됨 (`decideMockMode()` 게이트). 다른 provider 키가 전부 비어 있어도 claude-cli 는 실해석 경로.
- **비용 0**: CLI/bridge 는 토큰 카운트 미제공 → `tokensIn=0`·`tokensOut=0`·`costUsd="0"` 정직 기록 (`AiCostCalculator` claude-cli=$0 매핑). 구독 기반이라 월 임계 alert 영향 0.
- prompt_template(`chart_v1`, type=chart) 흐름 무변경 — dispatcher 가 기존대로 합성한 prompt 를 그대로 Claude 에 전달. 차트 컨텍스트 페이로드 무변경.
- `ai_comment_log` 행 1건 INSERT (provider=claude-cli · tokens 0 · cost 0).
- 실패(bridge 응답 공백 / HTTP 5xx / CLI exit≠0) → adapter 가 raw throw → dispatcher Resilience4j retry 3회 → 최종 실패 시 graceful refuse 응답(한국어). mock 합성 0.
