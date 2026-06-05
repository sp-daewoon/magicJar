# ADR 0112 — AI 종목발굴 Claude CLI bridge + 전략 리서치 후보 탐색

- 상태: **Accepted (2026-06-06)**
- 작성자: Codex
- 결정자: 사용자(최고 관리자) — "AI API 사용안할거고 claude -p를 이용"
- 영역: 전략 리서치 + AI 옵션 보조 + UI 후보 탐색
- 관계:
  - ADR 0001(룰 본체 + AI 옵션 + MCP 서버 미래)
  - ADR 0026(AI 옵션 보조 — 사용자 트리거 한정)
  - ADR 0071(T-SC Screener — 룰 기반 종목 발굴)
  - ADR 0107(Scenario Replay Sandbox — 실주문 경로 분리 원칙)

## 1. Context

전략 리서치 화면은 이미 후보 전략을 보여주고 기존 차트/리서치 흐름으로 연결된다. 사용자는 종목 코드를 직접 고르는 것보다 종목명·테마·상황 문장으로 찾는 일이 많고, "AI가 종목을 찾아주는" 탐색 경험을 원했다.

단, magicJar의 본체 원칙은 변하지 않는다. AI는 자동매매 의사결정권이 없고, 매수/매도 추천이나 주문 트리거를 만들면 안 된다. 이번 기능은 **전략 리서치로 검증할 후보를 찾는 사용자 트리거 옵션**이다.

또한 이번 사이클은 Anthropic API key를 붙이지 않고 `claude -p` CLI를 사용한다. Docker api 컨테이너는 host의 CLI를 직접 실행할 수 없으므로 local bridge가 필요하다.

## 2. Decision

전략 리서치에 `POST /api/strategy-research/ai-discovery`를 신설하고, 프론트엔드 `/strategy/research` 상단에 **AI 종목발굴** 패널을 둔다.

### 2-1. 호출 흐름

```
사용자 자연어 조건
  -> Strategy Research UI
  -> POST /api/strategy-research/ai-discovery
  -> StrategyResearchAiDiscoveryService
  -> StrategyResearchAiTextClient
  -> claude -p 직접 실행 또는 local bridge /discover
  -> JSON 후보 파싱
  -> instrument master code/name/market 매핑
  -> matched/unmatched 후보 카드
  -> 사용자가 선택한 matched 후보만 기존 리서치 form/chart로 이동
```

### 2-2. Provider 선택

- IDE/host bootRun: `MAGICJAR_STRATEGY_RESEARCH_AI_DISCOVERY_BRIDGE_URL`이 비어 있으면 api가 `ProcessBuilder(command, "-p", prompt)`로 직접 실행한다.
- Docker dev: `MAGICJAR_STRATEGY_RESEARCH_AI_DISCOVERY_BRIDGE_URL=http://host.docker.internal:8787`로 host bridge를 호출한다.
- bridge: `scripts/claude-discovery-bridge.mjs`가 `POST /discover`를 받아 `claude -p`를 실행하고 stdout을 돌려준다.
- provider 표시는 직접 실행이면 `claude-cli`, bridge면 `claude-cli-bridge`.

### 2-3. 종목 마스터 매핑 필수

Claude가 반환한 후보는 그대로 사용하지 않는다.

- code가 있으면 `instrument` primary key로 1차 조회한다.
- code가 없거나 실패하면 name/code query로 `InstrumentJpaRepository.searchByNameOrCode`를 사용한다.
- `active=true`, `securitiesGroup == "ST"` 또는 null, market filter 통과 후보만 matched 처리한다.
- matched=false 후보는 카드로 표시하되 "선택"할 수 없다.

### 2-4. 안전 경계

- KIS 주문 adapter 호출 0.
- `order.intent.v1` 발행 0.
- 전략 자동 저장 0.
- Watchlist 자동 등록 0.
- 실시간 수요 자동 등록 0.
- AI 후보는 "검증 후보"이며, 사용자가 명시적으로 선택해야 기존 전략 리서치 form에 들어간다.

### 2-5. 설정

| 설정 | 기본값 | 의미 |
|---|---|---|
| `MAGICJAR_STRATEGY_RESEARCH_AI_DISCOVERY_ENABLED` | `true` | endpoint 활성화 |
| `MAGICJAR_STRATEGY_RESEARCH_AI_DISCOVERY_COMMAND` | `claude` | 직접 실행 CLI |
| `MAGICJAR_STRATEGY_RESEARCH_AI_DISCOVERY_BRIDGE_URL` | empty | 설정 시 bridge 호출 |
| `MAGICJAR_STRATEGY_RESEARCH_AI_DISCOVERY_TIMEOUT_SECONDS` | `45` | CLI/bridge timeout |
| `CLAUDE_DISCOVERY_BRIDGE_HOST` | `127.0.0.1` | bridge listen host |
| `CLAUDE_DISCOVERY_BRIDGE_PORT` | `8787` | bridge listen port |
| `CLAUDE_DISCOVERY_COMMAND` | `claude` | bridge가 실행할 command |
| `CLAUDE_DISCOVERY_BRIDGE_TOKEN` | empty | 선택 인증 token |

## 3. Consequences

- 사용자는 코드보다 종목명·테마·조건 문장으로 전략 리서치 후보를 빠르게 탐색할 수 있다.
- 기존 룰 기반 Screener(ADR 0071)는 유지된다. AI 종목발굴은 데이터가 부족하거나 관점 탐색이 필요할 때 쓰는 보조 path다.
- Claude CLI/bridge가 죽어도 본체 자동매매, 차트, 기존 리서치 후보 생성은 영향 없다. endpoint만 503 계열로 실패하고 UI는 에러 문구를 표시한다.
- Docker dev는 bridge 프로세스가 별도이므로 재기동/health 확인 절차가 README에 남아야 한다.

## 4. 운영 ADR 양식 3 슬롯

### 4-1. SLO 영향

자동매매 S-1~S-5 직접 영향 0. 사용자 클릭형 endpoint라 장애 시 리서치 탐색 UX만 영향받는다. timeout 기본 45초로 장시간 request 점유를 제한한다.

### 4-2. rollback plan

- frontend AI 종목발굴 패널 제거 또는 endpoint call 비활성화.
- `MAGICJAR_STRATEGY_RESEARCH_AI_DISCOVERY_ENABLED=false`로 endpoint 즉시 비활성화.
- Docker bridge URL env 제거 시 host bootRun 직접 CLI path만 남는다.
- DB migration, Kafka topic, Redis key, STOMP topic 신설 0이므로 revert 부담이 작다.

### 4-3. 관측 매트릭스

| 측정 항목 | 목표 | 측정 방법 |
|---|---|---|
| bridge health | `UP` | `GET http://127.0.0.1:8787/health` |
| 후보 매핑률 | unmatched 과다 방지 | 응답 `matched` count와 WARN 문구 |
| timeout/failure | 사용자에게 정직 표시 | endpoint 503/504 + UI error |
| 자본 path 오염 | 0 | 주문 topic·KIS adapter 호출 0 확인 |

## 5. 트레이드오프

| 결정 | 채택 사유 | 거부 대안 |
|---|---|---|
| `claude -p` CLI | 사용자가 API key를 원하지 않음 | Anthropic API 직접 호출 |
| local bridge | Docker 컨테이너에서 host CLI 실행 불가 | 컨테이너에 Claude CLI 설치 |
| 종목 마스터 매핑 필수 | hallucination 후보의 직접 사용 차단 | AI code/name 그대로 선택 허용 |
| Strategy Research 내부 패널 | 발견 즉시 기존 리서치 form으로 연결 | 별도 독립 페이지 |
| 자동 저장/등록 0 | AI 결정권 0 원칙 보존 | AI 후보를 watchlist/전략에 자동 반영 |

## 6. 변경 이력

| 날짜 | 변경 | 결정자 |
|---|---|---|
| 2026-06-06 | **ADR 0112 Accepted** — Strategy Research AI 종목발굴 신설. Claude CLI(`claude -p`) 직접 실행 또는 local bridge, 종목 마스터 매핑 필수, matched 후보만 기존 리서치/차트로 연결. 주문·전략 저장·watchlist 자동 등록 0. | 사용자 + Codex |
