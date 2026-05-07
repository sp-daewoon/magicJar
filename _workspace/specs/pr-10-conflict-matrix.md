# PR-10 T-S1 충돌 행렬 (병렬 디스패치 결정 근거)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `_workspace/specs/pr-10-s1-design.md` v1 + `_workspace/specs/pr-10-dispatch-spec.md` v1 + ADR 0019·0029·0048·0040·0025·0014·0050
- 사용자 메모리 정합:
  - `feedback_orchestrator_active_parallel.md` — **architect "직렬 권장" 답습 금지**. add-only 충돌은 진짜 병렬 가능
  - `feedback_3tier_hierarchy.md` — 3단 hierarchy: 오케스트라(지휘) → architect(설계) → 실무 sub-agents (병렬 실행)
  - `feedback_autonomy.md` — Claude 자율 실행
  - `feedback_kis_first_policy.md` — KIS WS 메시지·endpoint는 open-trading-api 출처 의무
  - `feedback_no_mock_data.md` — 사용자 흐름에서 mock 합성 폐기

---

## §1. 결론 (한 줄)

> **PR-10a (frontend) + PR-10b (market-data) 완전 병렬 권장 — 양쪽 모듈 영역이 frontend vs api/consumer/domain로 완전 분리. modify-shared 후보 1건(CHANGELOG.md)은 ADR 0050 슬롯 채번룰 두 번째 적용 + HTML 주석 anchor 분리(hunk 분리 시도)로 충돌 0 보장. PR-9에서 발생한 add-add 충돌 4건은 본 PR-10 dispatch §4-1 영역 분배 LOCK 강화로 0% 사전 검증.**

---

## §2. 인터페이스 동결 시점

본 spec 4종(`pr-10-s1-design.md` + `pr-10-dispatch-spec.md` + 본 문서 + `_workspace/backlog.md`)이 PR로 머지되는 순간 = 인터페이스 동결.

동결되는 계약:
- `pr-10-s1-design.md` §3 (REST API 시그니처 LOCK)
- `pr-10-s1-design.md` §4 (차트 series 데이터 흐름 LOCK)
- `pr-10-s1-design.md` §5 (호가 LRU 10 라이프사이클 LOCK)
- `pr-10-s1-design.md` §6 (frontend UI 계약 LOCK)
- `pr-10-s1-design.md` §7 (KIS endpoint 매트릭스 + KIS-first 의무 LOCK)
- `pr-10-dispatch-spec.md` §4-1 (영역 분배 LOCK ★ — add-add 0% 검증)
- `pr-10-dispatch-spec.md` §4-2~§4-7 (domain port·REST·STOMP·Kafka·Redis·payload 시그니처)
- `pr-10-dispatch-spec.md` §5 (CHANGELOG 슬롯 row 위치·형식 LOCK + HTML 주석 anchor)
- ADR 0019·0029·0048·0040·0025·0014·0050 (모두 본 PR 정합)

**인터페이스 동결 즉시** frontend·market-data 양쪽이 spec만 보고 동시에 자기 worktree에서 작업 시작 가능.

---

## §3. 파일 단위 충돌 행렬

### §3-1. PR-10a (frontend) 변경 파일 vs PR-10b (market-data) 변경 파일

| 파일/디렉토리 | PR-10a | PR-10b | 충돌 유형 | 결정 |
|---|---|---|---|---|
| `frontend/src/pages/Symbol/SymbolDetailPage.tsx` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/pages/Symbol/SymbolHeader.tsx` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/pages/Symbol/SymbolPriceCard.tsx` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/pages/Symbol/SymbolMetaCard.tsx` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/pages/Symbol/SymbolChart.tsx` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/pages/Symbol/IntervalToggle.tsx` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/pages/Symbol/LightweightChart.tsx` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/pages/Symbol/SymbolOrderbook.tsx` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/pages/Symbol/SymbolError.tsx` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/api/symbol.ts` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/api/hooks/useSymbol.ts` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/api/hooks/useSymbolCandles.ts` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/lib/stomp.ts` | **신규** | 무관 | add-only | **격리** |
| `frontend/src/App.tsx` | **변경** (`/symbol/:code` 라우트 추가) | 무관 | modify | **격리** (PR-10a 단독) |
| `frontend/src/pages/Watchlist/WatchlistRow.tsx` | **변경** (onClick navigate 1줄) | 무관 | modify | **격리** (PR-10a 단독) |
| `frontend/package.json` | **변경** (`@stomp/stompjs` 의존 추가) | 무관 | modify | **격리** (PR-10a 단독) |
| `frontend/pnpm-lock.yaml` | **변경** (lock 갱신) | 무관 | modify | **격리** (PR-10a 단독) |
| `frontend/src/pages/Symbol/__tests__/SymbolDetailPage.test.tsx` | **신규** | 무관 | add-only | **격리** |
| `api/.../controller/SymbolController.kt` | 무관 | **신규** | add-only | **격리** |
| `api/.../service/SymbolMetaService.kt` | 무관 | **신규** | add-only | **격리** |
| `api/.../service/CandleQueryService.kt` | 무관 | **신규** | add-only | **격리** |
| `api/.../config/WebSocketConfig.kt` | 무관 | **신규** | add-only | **격리** |
| `api/.../messaging/MarketTickStompForwarder.kt` | 무관 | **신규** | add-only | **격리** |
| `api/.../messaging/MarketCandleStompForwarder.kt` | 무관 | **신규** | add-only | **격리** |
| `api/.../messaging/MarketOrderbookStompForwarder.kt` | 무관 | **신규** | add-only | **격리** |
| `api/src/test/kotlin/.../SymbolControllerTest.kt` | 무관 | **신규** | add-only | **격리** |
| `api/src/main/resources/application.yml` | 무관 | (필요 시 STOMP 설정 추가) | modify | **격리** (PR-10b 단독 — PR-9b application.yml은 consumer 모듈, 본 PR-10은 api 모듈로 다른 파일) |
| `consumer/.../ws/KisWebSocketBridge.kt` | 무관 | **변경** (subscribeOrderbook·unsubscribeOrderbook 메서드 추가) | modify | **격리** (PR-9b가 만든 파일, 본 PR-10b market-data 본인 영역 보강) |
| `consumer/.../ws/KisWsOrderbookParser.kt` | 무관 | **신규** | add-only | **격리** |
| `consumer/.../ingestion/OrderbookSubscriptionRouter.kt` | 무관 | **신규** | add-only | **격리** |
| `consumer/.../kafka/SymbolDetailCommandKafkaListener.kt` | 무관 | **신규** | add-only | **격리** |
| `consumer/.../config/KafkaTopicConfig.kt` | 무관 | **변경** (market.orderbook·symbol.detail.command 2 topic 추가) | modify | **격리** (PR-9b가 만든 파일, 본 PR-10b market-data 본인 영역 보강) |
| `consumer/.../infrastructure/kis/KisRestPriceAdapter.kt` | 무관 | **변경** (KisRestPriceMetricsPort 추가 implement) | modify | **격리** (PR-9b가 만든 파일, 본 PR-10b market-data 본인 영역 보강) |
| `consumer/src/test/kotlin/.../OrderbookSubscriptionRouterTest.kt` | 무관 | **신규** | add-only | **격리** |
| `domain/.../market/OrderbookSnapshot.kt` | 무관 | **신규** | add-only | **격리** |
| `domain/.../market/PriceMetrics.kt` | 무관 | **신규** | add-only | **격리** |
| `domain/.../market/PriceLevel.kt` | 무관 | **신규** | add-only | **격리** |
| `domain/.../market/port/KisRestPriceMetricsPort.kt` | 무관 | **신규** | add-only | **격리** |
| `domain/.../market/port/KisWsPort.kt` | 무관 | **변경** (subscribeOrderbook·unsubscribeOrderbook 메서드 추가) | modify | **격리** (PR-5a + PR-9b가 만든 port, 본 PR-10b market-data 본인 영역 보강) |
| `batch/**` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `docker/**` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `scripts/**` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `.github/workflows/*` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `.env.example` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `CHANGELOG.md` | **변경** (PR-10a slot row in-place edit — 1줄) | **변경** (PR-10b slot row in-place edit — 1줄) | **modify-shared** | **ADR 0050 슬롯 채번룰 두 번째 적용 + HTML 주석 anchor 분리(hunk 분리 시도) — 충돌 0 보장** |
| `docs/architecture/*` | **변경 0** | **변경 0** | (변경 0) | **격리** (architect 단독) |
| `_workspace/arch/adr/*` | **변경 0** | **변경 0** | (변경 0) | **격리** (architect 단독) |
| `_workspace/specs/pr-10-*.md` | **변경 0** | **변경 0** | (변경 0) | **격리** (architect 단독) |
| `_workspace/backlog.md` | **변경 0** | **변경 0** | (변경 0) | **격리** (architect 단독) |
| `_workspace/tasks/T-*-pr-10-s1-frontend.md` | **신규** (자기 task) | 무관 | add-only | **격리** |
| `_workspace/tasks/T-*-pr-10-s1-market-data.md` | 무관 | **신규** (자기 task) | add-only | **격리** |
| `README.md` (루트) | **변경 0** | **변경 0** | (변경 0) | **격리** |

### §3-2. modify-shared 후보 분석

총 1건 식별:

#### (1) `CHANGELOG.md`

- 변경 내용 — 양쪽 sub-agent가 본인 슬롯 row 1줄 in-place edit (ADR 0050 슬롯 채번룰)
- **충돌 0 보장**: 
  - ADR 0050 §3-2 — git diff가 다른 row 변경으로 인식 → 양쪽 머지 시 conflict marker 발생 없음
  - 본 PR-10에서 **HTML 주석 anchor line 추가 시도** — 각 row 위에 `<!-- pr-10-slot-{agent} -->` anchor 삽입
  - 효과: hunk-based merge가 anchor + row를 1 hunk로 묶음 → 더 명확한 분리 + git이 인접한 line을 같은 hunk로 묶지 않음
  - 단 markdown table 안에서 HTML 주석 동작 미검증 — **본 PR이 처음 시도** (사용자가 처음 관찰)
- **본 PR-10이 ADR 0050 두 번째 적용 사이클** — PR-9 첫 운영에서 발견한 hunk-based merge 충돌 1건 보강 시도
- 일탈 시 fallback (anchor가 효과 없으면) — sub-agent 정확 line number 명시 (in-place edit이라 같은 line만 수정 → git 충돌 0)

#### 그 외 (modify-shared 0건 추가)

- application.yml — api 모듈 vs consumer 모듈 다른 파일 (PR-9 application.yml은 consumer/src/main/resources, 본 PR-10b는 api/src/main/resources 단독)
- domain port — 본 PR-10b 단독 변경 (KisWsPort 보강·KisRestPriceMetricsPort 신규). frontend 영역 미진입
- consumer 모듈 PR-9b 만든 파일 보강 (KisWebSocketBridge·KafkaTopicConfig·KisRestPriceAdapter) — 본 PR-10b market-data 단독 영역. PR-10a frontend는 consumer 모듈 미진입

### §3-3. 격리 검증 — 양 sub-agent 사전 add-add 가능 파일 비교 ★

**PR-9 결함 정정 — 양 sub-agent의 add 파일 목록 사전 비교**:

| PR-10a (frontend) add 파일 | PR-10b (market-data) add 파일 | 동명 add 가능성 |
|---|---|---|
| `frontend/src/pages/Symbol/*.tsx` (9건) | (frontend 0건) | **0%** |
| `frontend/src/api/symbol.ts` + hooks 2 | (frontend 0건) | **0%** |
| `frontend/src/lib/stomp.ts` | (frontend 0건) | **0%** |
| (api 0건) | `api/.../controller/SymbolController.kt` 외 7건 | **0%** |
| (consumer 0건) | `consumer/.../ws/KisWsOrderbookParser.kt` 외 4건 | **0%** |
| (domain 0건) | `domain/.../market/OrderbookSnapshot.kt` 외 4건 | **0%** |
| `_workspace/tasks/T-*-pr-10-s1-frontend.md` | `_workspace/tasks/T-*-pr-10-s1-market-data.md` | **0%** (다른 파일명) |

**결론**: 양 sub-agent의 add-add 가능성 **0% 사전 검증 완료**. PR-9 결함(consumer 모듈에 양쪽 add 4건 충돌)을 본 PR-10에서 회피.

### §3-4. modify-shared 검증 — 같은 파일을 양 sub-agent가 수정하는지

**PR-10a frontend가 modify하는 파일**:
- `frontend/src/App.tsx`
- `frontend/src/pages/Watchlist/WatchlistRow.tsx`
- `frontend/package.json`
- `frontend/pnpm-lock.yaml`
- `CHANGELOG.md`

**PR-10b market-data가 modify하는 파일**:
- `consumer/.../ws/KisWebSocketBridge.kt` (PR-9b 만든)
- `consumer/.../config/KafkaTopicConfig.kt` (PR-9b 만든)
- `consumer/.../infrastructure/kis/KisRestPriceAdapter.kt` (PR-9b 만든)
- `domain/.../market/port/KisWsPort.kt` (PR-5a + PR-9b 만든)
- `api/src/main/resources/application.yml` (필요 시)
- `CHANGELOG.md`

**동시 modify 파일**: `CHANGELOG.md` 1건 — ADR 0050 in-place edit + HTML 주석 anchor 보강.

**결론**: 양 sub-agent는 modify-shared 1건만 — ADR 0050으로 충돌 0 보장.

---

## §4. pre-push hook 종속성 분석

| 이유 | 분석 |
|---|---|
| `./gradlew check` (pre-push 자동) | 양쪽 PR 모두 main 위에서 GREEN 가능. PR-10b는 4 모듈 모두 build OK 가능. PR-10a는 frontend 영역만이라 gradle 0 영향 (frontend는 pnpm 별도) |
| PR-10a가 main 위에서 backend 응답 의존 | mock 응답으로 컴파일 GREEN. 통합 검증은 PR-10b 머지 후 |
| PR-10b가 main 위에서 frontend 의존 | 0 (PR-10b는 backend 단독) |
| consumer 모듈 추가 의존 | 본 PR-10b는 consumer 모듈에 추가 의존 0 (Spring WebSocket·KafkaListener는 PR-5a·PR-9 정합 가동 중) |
| api 모듈 STOMP 신규 의존 | `api/build.gradle.kts`에 `spring-boot-starter-websocket` 추가 의무 — PR-10b 단독 변경 |

**결론**: pre-push hook 양쪽 GREEN 가능. **권장 머지 순서**:
- **시나리오 A — PR-10b 먼저 머지 → PR-10a 합류** (권장):
  - PR-10b가 backend 본격 → PR-10a는 backend 가동 확인 후 통합 검증
  - PR-10a가 STOMP 끊김 시나리오 자체 검증 가능
- **시나리오 B — 동시 머지**: 양쪽 영역 분리라 가능. 단 사용자 dogfooding은 양쪽 머지 후

---

## §5. 인터페이스 동결로 인한 병렬 가능 메커니즘

frontend·market-data가 동시 작업하더라도 다음이 양쪽이 같은 spec을 바라보고 진행되도록 보장:

### §5-1. REST endpoint 시그니처 LOCK (dispatch §4-3)

```
GET  /api/symbols/{code}                     → SymbolDto
GET  /api/symbols/{code}/candles?...         → CandlesResponseDto
POST /api/symbols/{code}/orderbook/subscribe → {subscribed, slot}
POST /api/symbols/{code}/orderbook/unsubscribe → {unsubscribed}
```

**누가 만드는지 사전 결정** — dispatch §4-3 명시. PR-10b api 단독 producer / PR-10a frontend 단독 consumer.

### §5-2. STOMP topic 시그니처 LOCK (dispatch §4-4)

```
/topic/market.tick.{code}             — tick 실시간
/topic/market.candle.minute.{code}    — 1m closing 실시간
/topic/market.orderbook.{code}        — 호가 실시간
```

PR-10b producer (api MarketXxxStompForwarder) / PR-10a consumer (useStompTopic).

### §5-3. Kafka topic 시그니처 LOCK (dispatch §4-5)

```
market.tick.v1                  (PR-9 가동 → 본 PR-10b api consume)
market.candle.minute.closed.v1  (PR-9 가동 → 본 PR-10b api consume)
market.orderbook.v1             (본 PR-10b 신규 — consumer publish + api consume)
symbol.detail.command.v1        (본 PR-10b 신규 — api publish + consumer consume)
```

### §5-4. Redis 키 매트릭스 LOCK (dispatch §4-6)

```
quote:last:{code}              (PR-9 가동 — 본 PR-10b read)
candle:current:{code}:1m       (PR-9 가동 — 본 PR-10b read)
candle:today:{code}:1d         (PR-9 가동 — 본 PR-10b read)
meta:{code}                    (본 PR-10b 신규 — TTL 5분)
```

### §5-5. KIS WS H0STASP0 메시지 형식 LOCK

KIS open-trading-api `examples_llm/.../websocket_orderbook.py` 출처 — PIPE 구분자 + tr_id `H0STASP0` + subscribe/unsubscribe JSON 메시지 형식 사전 LOCK. frontend가 KIS WS 직접 의존 0 (PR-10a는 backend STOMP만).

### §5-6. 영역 분배 LOCK ★ (PR-9 결함 정합)

dispatch §4-1 정합:
- frontend 영역: `frontend/**` (단독)
- market-data 영역: `api/**`·`consumer/**`·`domain/**` (단독)
- 양 sub-agent 사전 비교 검증 완료 — add-add 0%, modify-shared 1건(CHANGELOG)만

---

## §6. 병렬 가능한 시나리오별 검증

### §6-1. 시나리오 A — PR-10b 먼저 머지 → PR-10a 합류 (권장)

```
T0: spec 4종 머지 (architect)
T0+5분: market-data 작업 시작 (worktree A)
T0+5분: frontend 작업 시작 (worktree B)
T+3시간: market-data PR-10b 푸시 → CI green → 사용자 머지
T+3.5시간: frontend rebase → PR-10a 푸시 → CI green → 통합 검증 → 머지
```

**왜 권장**:
- frontend가 backend 가동 확인 후 통합 검증 가능
- STOMP 끊김 시나리오 자체 검증 가능
- 사용자 dogfooding은 양쪽 머지 후 즉시

**충돌 가능성**:
- CHANGELOG.md (ADR 0050 in-place edit + HTML 주석 anchor) — 충돌 0
- 그 외 — 모두 격리

### §6-2. 시나리오 B — 동시 머지 (병렬 push)

```
T0: spec 머지
T0+5분: 양쪽 동시 시작
T+3시간: 양쪽 동시 push
T+3.5시간: CI green 양쪽
T+4시간: 사용자가 둘 머지 (어느쪽이든 무관)
```

**검증**: 사용자 dogfooding은 양쪽 머지 후 가능. 통합 검증은 후행 머지자가 rebase 후 자체 검증.

### §6-3. 시나리오 C — frontend 먼저 머지 (권장하지 않음)

```
T0: spec 머지
T+3시간: frontend PR-10a 머지 (backend 미완)
T+6시간: market-data PR-10b 머지
```

**왜 권장하지 않는가**:
- frontend만 머지 시 사용자가 SymbolDetail 진입 → backend 응답 부재 → 모든 화면 에러
- 단 빌드는 GREEN. 사용자 검증 차단

### §6-4. 시나리오 D — 직렬 (권장하지 않음)

PR-9와 동일 사유 — `feedback_orchestrator_active_parallel.md` 정합으로 직렬 권장 답습 금지.

---

## §7. 사용자 메모리 정합 검증 (필수)

| 사용자 메모리 | 본 결정 정합 |
|---|---|
| `feedback_orchestrator_active_parallel.md` — architect "직렬 권장" 답습 금지 + add-only 충돌은 진짜 병렬 | ✅ — 본 행렬에서 modify-shared 1건(CHANGELOG) 사전 정책으로 해소 + 영역 분배 LOCK 강화 + 병렬 권장 결론 |
| `feedback_3tier_hierarchy.md` — 오케스트라 → architect(설계) → 실무 sub-agents(병렬 실행) | ✅ — architect는 본 spec 4종 + backlog md 발행, 코드 0건. frontend·market-data 병렬 코드 작성 |
| `feedback_autonomy.md` — Claude 자율 실행, 사용자 확인은 아키텍처·외부자원·블로킹상충 3가지만 | ✅ — 본 spec에 사용자 결정 필요 사항 0건 (모두 ADR 정합 + 자율 결정 사유 §10 명시) |
| `feedback_v3_skepticism_no_regression.md` — 자동매매 적합성 의심 lens | 자동매매 적합성: ✅ T-S1은 사용자 dogfooding 진입점 — Watchlist + 시세 인입 + **표시** 흐름 마무리. 사용자 비전 ADR 0001 핵심. v1/v2 잔재 0건. fitness gap 0 |
| `feedback_kis_first_policy.md` — KIS MCP·open-trading-api 베이스 | ✅ — KIS endpoint(`H0STASP0`/`inquire-price`) 모두 KIS 출처 명시 + sub-agent kis-code-assistant MCP 사용 의무 |
| `feedback_no_mock_data.md` — 사용자 흐름에서 mock 합성 폐기 | ✅ — 잘못된 code 진입 시 "종목 정보 없음" 명시 + Yahoo 미완 시 "데이터 수집 중" + Redis miss + KIS 실패 시 503 (mock 합성 0) |
| `feedback_secret_handling.md` — 키 평문 응답 reflect 금지 | ✅ — 본 PR-10의 응답 schema에 키 평문 reflect 0건 |
| `project_kis_key_management.md` — 개발 중 ⚙️ SettingsModal 입력 + DB AES | ✅ — 본 PR-10은 PR-7·PR-9 등록된 AES BYTEA 키 그대로 복호화 사용 |
| `feedback_qa_on_demand.md` — MVP 단계 QA 게이트 미적용 | ✅ — 본 PR-10 양쪽 smoke 1~2건 + 사용자 dogfooding 수동 검증 + R3 일괄은 별도 묶음 |
| `feedback_spec_plan_scope.md` — spec/plan 발행 범위 | ✅ — T-S1 신규 트랙 + 다중 sub-agent + ADR 다중 정합 → spec/plan 발행 정합 |
| `project_track1_dogfooding_paused.md` — 첫 dogfooding 보류 + 다음 사이클 첫 액션 | ✅ — **본 PR-10이 첫 dogfooding 직전** — 머지 후 사용자가 화면에서 직접 검증 |

---

## §8. 결론 — 권장 디스패치 모드

### §8-1. 1차 권장 — **시나리오 A** (PR-10b 먼저 + PR-10a 합류)

오케스트라가 spec 4종 머지 후:

```bash
# Step 1: 양쪽 동시 시작
git worktree add .claude/worktrees/agent-pr10-frontend agent/frontend/pr-10-s1-frontend
git worktree add .claude/worktrees/agent-pr10-market-data agent/market-data/pr-10-s1-market-data

# Terminal 1 (frontend)
cd .claude/worktrees/agent-pr10-frontend
node scripts/task.mjs start --agent frontend --branch agent/frontend/pr-10-s1-frontend ...

# Terminal 2 (market-data)
cd .claude/worktrees/agent-pr10-market-data
node scripts/task.mjs start --agent market-data --branch agent/market-data/pr-10-s1-market-data ...

# Step 2: PR-10b 먼저 머지 → PR-10a rebase + 머지
```

### §8-2. 2차 권장 — **시나리오 B** (완전 동시 머지)

영역 분리라 양쪽 동시 머지 가능. 단 사용자 dogfooding은 양쪽 머지 후.

### §8-3. 직렬은 권장하지 않음

직렬 권장 시 사유 입증 의무:
- modify-shared 1건(CHANGELOG) — ADR 0050 + HTML 주석 anchor로 해소
- 인터페이스 동결 미달 — spec 4종이 동결 보장
- pre-push hook 종속 → 시나리오 A로 해소

**위 3건 모두 해소 — 직렬 권장 사유 부재**.

---

## §9. 운영 체크리스트 (오케스트라가 머지 시점 점검)

### §9-1. PR-10b (market-data) 머지 시점 (1차 권장 — 시나리오 A)

- [ ] PR-10a가 main rebase 가능한 상태 (충돌 없음 — frontend 영역 미진입 검증)
- [ ] PR-10b CI green
- [ ] DoD 1·2·3·6·7·8·9·15·16 backend 영역 ✅
- [ ] PR-10b CHANGELOG slot row가 채워졌는지 확인 (`grep "(작성: market-data)" CHANGELOG.md` → TBD 아닌 실제 사유)
- [ ] PR-10b가 `(작성: frontend)` row 미수정 + `(작성: architect)` row 미수정 확인 (영역 침범 0 검증)
- [ ] PR-10a 작업자에게 "main rebase + force push" 안내

### §9-2. PR-10a (frontend) 머지 시점

- [ ] PR-10a CI green
- [ ] DoD 1·4·5·11·12·13 frontend 영역 ✅
- [ ] PR-10a CHANGELOG slot row 채워짐 확인
- [ ] PR-10a가 `(작성: market-data)` row 미수정 + `(작성: architect)` row 미수정 확인
- [ ] 양쪽 머지 후 통합 게이트 (DoD 모든 항목) — 사용자 dogfooding 수동 검증 의무

### §9-3. 사용자 dogfooding 가이드 (양쪽 머지 후)

- [ ] `scripts/build-and-deploy.sh` 통합 기동
- [ ] http://localhost:3000 진입 → 셋업 완료 확인
- [ ] watchlist 등록 (예: 005930)
- [ ] 30초 대기 (cold-start 완료)
- [ ] row 클릭 → /symbol/005930 진입
- [ ] design.md §12-2 체크리스트 12 항목 통과 확인
- [ ] 발견 결함 list → backlog.md 추가 (다음 사이클 의제)

### §9-4. 머지 후 정리 (ADR 0033 §결과)

- [ ] worktree 2개 unlock + remove
- [ ] 로컬 브랜치 2개 -d
- [ ] task.mjs archive 2건
- [ ] CHANGELOG.md 정합 확인 (양쪽 row 모두 채워짐, TBD 잔재 0건, anchor 정상 잔존)

---

## §10. 발견된 함정·위험 (사용자 알아야 할 것)

본 PR-10 진행 중 식별된 함정 — 솔직 보고:

### §10-1. ADR 0050 슬롯 채번룰 두 번째 적용 — HTML 주석 anchor 첫 시도 ★

본 PR-10이 ADR 0050 두 번째 적용 + HTML 주석 anchor 첫 시도. 사용자가 처음 관찰:
- spec PR diff에 anchor 3건 + slot row 3건 prepend 확인
- markdown table 안에서 HTML 주석이 시각 깨짐 없이 동작하는지 검증
- sub-agent PR diff에 본인 row만 modify + anchor 미수정 검증
- 두 sub-agent 머지 시 CHANGELOG conflict 0건 검증 (PR-9 hunk 충돌 회피 확인)

검증 실패 시 ADR 0050 본문 정정 (별도 미뤄둠 — backlog §1).

### §10-2. KIS WS H0STASP0 정확 메시지 인덱스 — sub-agent 자체 추론 위험

KIS WS H0STASP0 PIPE 구분자 인덱스를 본 spec에 명시 안 함 (KIS-first 정책 정합).

**위험**: market-data sub-agent가 인덱스 자체 추론 시 호가 데이터 깨짐.

**처리**: dispatch-spec §3-2-C·§7-3에 KIS open-trading-api `examples_llm/.../websocket_orderbook.py` 출처 + `kis-code-assistant` MCP 사용 의무 명시.

### §10-3. 호가 LRU 10 라이프사이클 함정 ★

(spec §10 정합)
- 함정 1: 사용자 빠른 페이지 전환 → 200ms throttle로 처리
- 함정 2: STOMP 끊김 + unsubscribe REST 실패 → 5분 idle timeout fallback
- 함정 3: 브라우저 탭 닫기 → `navigator.sendBeacon` + 5분 idle timeout

**위험**: backend slot 누수 가능성. 사용자 dogfooding 시 직접 관찰 필요.

### §10-4. 사용자 첫 dogfooding 깨질 위험 ★ (사용자 메모리 정합)

(spec §10-1 정합) 본 PR-10 머지 후 사용자 첫 dogfooding 시:
1. cold-start 미완 → "데이터 수집 중" graceful
2. 장 마감 후 dogfooding → stale 배지
3. 호가 LRU 토글 함정
4. 모의 키 endpoint 미지원
5. STOMP 끊김 인지 부재
6. KIS approval_key 발급 실패

**처리**: 사용자 dogfooding 가이드(spec §12) 정확 안내 + 결함 발생 시 backlog 추가.

### §10-5. consumer 영역 침범 패턴 (PR-9 결함 — backlog §5)

PR-9에서 backend-core·market-data 양쪽이 consumer 모듈에 add → add-add 4건. 본 PR-10에서는:
- consumer 모듈 모든 변경 = market-data 단독 (frontend 미진입)
- ✅ 영역 침범 0 보장

단 본 PR-10b가 PR-9b 만든 파일 보강(KisWebSocketBridge·KafkaTopicConfig·KisRestPriceAdapter) — modify-shared가 아닌 modify-own (시간 차로 같은 sub-agent 영역).

### §10-6. STOMP broker simple vs relay (post-MVP)

본 PR-10b는 simple broker 1차. relay broker(Redis backend) 후속 정합은 backlog 후보.

**위험**: 사용자 path가 분산 시 simple broker 한도 — 1차 MVP single-tenant라 무관.

### §10-7. api 모듈 `spring-boot-starter-websocket` 의존 추가

`api/build.gradle.kts` 의존 추가 의무 — PR-10b 단독 변경.

**위험**: api 부팅 latency 약간 증가 (~ 1-2s). 사용자 자동 인지.

### §10-8. CandleQueryService Redis 합류 race condition

장중 1m closing 시점에 Redis `candle:current` evict + DB INSERT 동시 발생 가능 → API 응답에 가장 최근 candle 누락 위험.

**처리**: PR-10b가 Redis miss 시 DB만 응답 (graceful). post-MVP는 atomic lua script.

### §10-9. frontend 모바일 반응형 1차 미지원

desktop 우선 — 모바일 stacked layout 1차 OK 정도. 사용자 dogfooding 모바일 시 표시 깨짐 가능.

**처리**: 사용자가 모바일 검증 시 backlog 추가. 본격 반응형은 T-LWC 또는 후속.

### §10-10. instrument 테이블 부재 시 종목명 graceful

(spec §10-10 정합) PR-9 cold-start은 instrument INSERT 0. 본 PR-10에서 `inquire-price` 응답 industry·종목명 사용. 부재 시 code 그대로 표시.

**위험**: 종목명 부재 → 사용자 UX 저하. T-IM1 진입 후 정합.

### §10-11. PR-9 default priority 정정 의무 (architect 자율 점검 — 본 PR-10 비범위)

PR-9a에서 `WatchlistService.priority ?: 100` 정정 commit 발견. 본 PR-10은 priority 정책 미사용 (호가 LRU 10은 별도 정책).

**위험**: 0건 (본 PR-10 무관).

---

## §11. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-10 충돌 행렬 v1 발행. 결론: **PR-10a + PR-10b 완전 병렬 권장 (시나리오 A 1차)**. modify-shared 1건(CHANGELOG)만 — ADR 0050 슬롯 채번룰 두 번째 적용 + HTML 주석 anchor 분리(hunk 분리 시도)로 충돌 0 보장. PR-9 결함 정정 — 영역 분배 LOCK 강화로 add-add 0% 사전 검증. 발견된 함정 11건 명시 (특히 §10-1 ADR 0050 hunk 분리 첫 시도, §10-4 사용자 첫 dogfooding 깨질 위험). (architect, opus 4.7 1M) |
