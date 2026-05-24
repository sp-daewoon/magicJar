# magicJar v3.9.0 Baseline — W4 마감 baseline (T-MARKET + T-AI + T-MCP 3 트랙 동시 진입 + ADR 0100·0101·0102·0103 신설 + 8 → 9 JVM 모듈 + R0 결함 fix evidence + Codex 검증 게이트 통과)

> **태그 컷**: 2026-05-24 v3.9.0 (release commit `<TBD-release-commit>`, `release/v3.9.0` immutable LOCK)
> **사이클**: v3.8.0 → v3.9.0 minor (v3.8.1 patch 1 cut + PR-1·PR-2·PR-3·PR-4 4 R0~R3 round + W4 P0·P1·P2 hotfix 사이클 + ADR 0100·0101·0102·0103 신설 + 6 ADR amend + Codex 외부 review 4 lens 검증 게이트 통과)
> **상태**: PR-2 architect 영역(README + 01 + 06 + 07) 본격 작성. backend-core(02·04) · market-data(03 — KIS-first raw) · frontend(05) 영역은 sub-agent 별도 디스패치(orchestrator 통합).
> **권위**: 본 baseline 폴더는 v3.9.0 시점 **종합 기능 정의서**. SoT는 `docs/architecture/master-architecture.md` v2 (ADR 0030).

---

## 한 장 요약

magicJar v3.9.0은 **W4 마감 baseline (T-MARKET 신규 트랙 + T-AI 5 진입점 + T-MCP 6 tool 동시 진입 + market-trend-engine 8번째 모듈 신설 + ADR 0100·0101·0102·0103 신설 + 6 ADR amend + R0 spec audit 결함 fix evidence + Codex 검증 게이트 4 lens 통과 + W4 P0·P1·P2 hotfix 사이클 LOCK)** 미러스톤이다. v3.8.0 (운영 단계 진입 + 7 actor reshuffle + qa Visual 격상 + P0 OPS 6/6) → v3.8.1 patch (PR-1 KIS .block() subscribeOn hotfix + PR-2 ADR 0097·0098·0099 + reactor 컨벤션 + PR-4 trade breakdown 화면) → v3.9.0 (W4 3 트랙 동시 진입 + ADR 0102 spec audit 5단계 절차 신설 본문화 + ADR 0103 JpaScan 패키지 누락 차단 신설 본문화 + Codex 외부 review 4 lens 정합 verify gate 통과). **W4 마감 LOCK** — 사용자 명시 "T-MARKET + T-AI + T-MCP" + brainstorming 통합 design (cec6a4d6) 결재 정합 + R0 PR #674 (1411d392) Flyway V40~V45 LOCK 결함 발견 → R0 amend PR #675 (60449432) V47~V52 shift + ADR 0102 신설 → R1·R2·R3 + W4 P1 사이클 일괄 진행 + W4 P0·P1·P2 hotfix 마감.

**v3.9.0 시점 동작하는 핵심 흐름** (v3.8.0 baseline + v3.8.1 patch + v3.9.0 W4 마감 + W4 P0·P1·P2 hotfix):

1. **W3 자동매매 본체 종착 노드 가동 LOCK 보존** — boundary 마감 → 시그널 → risk verdict → order intent → KIS REST → ACK → H0STCNI0/H0STCNI9 체결 → STOMP 4 event 토스트 chain 보존 (ADR 0066·0067·0068 baseline). 3중 게이트(env × env × `user_account.real_trading_consent_at`) 자본 보호 LOCK 보존. T-MAN(수동 — ADR 0082) + T-O(자동) 양쪽 모두 `OrderRouterKafkaListener` 12 step LOCK 단일 자본 path. v3.8.1 patch에서 PR-1 KIS `.block()` subscribeOn hotfix (ADR 0099 정합) + PR-2 ADR 0097·0098·0099 + reactor 컨벤션 + PR-4 trade breakdown 화면 + PR-3 ExecutionPositionUpdatePort (ADR 0097) + KillSwitchQueryPort (ADR 0098) 마감.

2. **v3.9.0 미러스톤 — W4 마감 (T-MARKET + T-AI + T-MCP 3 트랙 동시 진입)**:
   - **T-MARKET 트랙 신설 (ADR 0100)** — 지수·섹터 추세 분석 모듈 + `market-trend-engine` 8번째 JVM 모듈 신설 (ADR 0004 7→8 amend) + KIS endpoint 5 LOCK (`inquire-index-price` · `inquire-index-daily-price` · `inquire-index-category-price` · `price` overseas · `dailyprice` overseas) + cron 2 (1분 + 16:30/06:00 KST 일봉) + REST 6 endpoint + STOMP 3 topic + Flyway V47·V48 (market_index_snapshot + sector_index_snapshot) + KIS-first raw fetch 5 evidence 인용 (open-trading-api 공식 SDK examples_llm/ path · 자체 추론 0)
   - **TrendPreference 1급 도메인 (ADR 0101)** — `user_preference` 표 (V51) + `extra JSONB` forward-compat 확장 슬롯 단일 진입점 + Bean Validation (1~5개·각 1~252일·sorted ASC) + REST 2 endpoint Swagger 4단 한국어 의무 + STOMP fanout + 시드 SQL idempotent (`INSERT FROM user_account ON CONFLICT DO NOTHING`) + W4 P1 amend (user_id BIGINT → VARCHAR(20) 실 schema 정합 정정 · ADR 0046 자연 키 정합)
   - **T-AI 5 진입점 active dispatch (ADR 0026 amend)** — SYMBOL · CHART · STRATEGY · SIGNAL · MARKET (신규) 5 진입점 + 3 provider 추상화 (Anthropic Claude · OpenAI GPT · Mock) + `AiOptionDispatcher` 단일 분기 + 5 prompt seed (`symbol_v1` · `chart_v1` · `strategy_v1` · `signal_v1` · `market_v1`) + Flyway V49 (`ai_comment_log`) + V50 (`prompt_template`) + W4 P0 hotfix (`@Transactional` 추가 — INSERT 0/5 → 5/5 회복)
   - **T-MCP 6 tool active dispatch (ADR 0027 amend)** — `get_candles` · `run_screener` · `get_journal` · `explain_indicator` · `backtest_strategy` · `get_market_trend` (신규) 6 tool + `McpController` + API key auth filter + Flyway V52 (`mcp_audit`) + JSON Schema 본문 + 분당 100 초과 alert + W4 P1 graceful 핸들러 (backtest_strategy 422)

3. **ADR 신설 4건 (0100·0101·0102·0103) + amend 6건 (0004·0026·0027·0037·0046·0100·0101)**:
   - **0100 T-MARKET 트랙** (Accepted, 2026-05-24) — 지수·섹터 추세 분석 + market-trend-engine 모듈 + KIS endpoint 5 LOCK + cron 2 + REST 6 + STOMP 3 + Flyway V47·V48 + 운영 ADR 양식 3 슬롯 첫 적용 사례 (v3.9.0)
   - **0101 TrendPreference 1급 도메인** (Accepted, 2026-05-24, W4 P1 amend) — `user_preference` 표 (V51) + extra JSONB 확장 슬롯 + Bean Validation + REST 2 + STOMP fanout + 시드 SQL + 후속 사이클 10+ 슬롯 + W4 P1 amend (user_id VARCHAR(20) 실 schema 정합)
   - **0102 spec audit 5단계 절차 LOCK** (Accepted, 2026-05-24, W4 P1 amend) — R0 PR #674 Flyway V40~V45 결함 fix 본문화 + 영속 자원 6 영역 (Flyway·Kafka·Redis·STOMP·REST·ENV) + 면제 조건 3 + escalation 경로 5단계 + W4 P1 amend (§5-4 내부 도메인 모델 inventory 영역 확장 후보 cross-ref 1건 — ADR 0101 user_id BIGINT 초안 결함 evidence)
   - **0103 JpaScanConfiguration 패키지 누락 차단** (Accepted, 2026-05-24, W4 P1 R3 qa 부팅 차단 hotfix 직후) — 옵션 A (basePackages 단일화) + 옵션 C-lite (smoke test) 하이브리드 + 임시 baseline (R3 hotfix 화이트리스트 3건 명시 유지) + 후속 사이클 6 슬롯

4. **R0 결함 fix evidence + Codex 검증 게이트 통과 (사용자 메모리 `feedback-codex-validation-gate` 4 lens 정합)**:
   - **R0 결함 발생** — 2026-05-24 R0 PR #674 (1411d392)는 W4 통합 사이클 (T-MARKET·T-AI·T-MCP) spec/ADR/dispatch 매트릭스 발행. Flyway V40~V45 6건 sub-agent별 LOCK 분배. 그러나 ai-engineer R1 dispatch가 main HEAD audit 결과 **V40~V46 모두 점유 사실** 발견 (V46 = backtest_run_strategy_id) → 헌법 §7 escalation 가동 → orchestrator R1 dispatched 2 sub-agent (market-data + backend-core T-MARKET) 즉시 kill + ai-engineer 자체 escalation으로 작업 0 발행
   - **R0 amend PR #675 (60449432)** — architect R0 amend로 V47~V52 재분배 LOCK 발행 (`V47=market_index_snapshot`·`V48=sector_index_snapshot`·`V49=ai_comment_log`·`V50=prompt_template`·`V51=user_preference`·`V52=mcp_audit`) + **ADR 0102 신설** (spec audit 5단계 절차 LOCK + 면제 조건 3 + escalation 경로 5단계 + 발생 evidence 본문 + 운영 ADR 양식 3 슬롯 + 후속 사이클 6 슬롯)
   - **Codex 검증 게이트 4 lens 통과** — 세심함(spec/plan 발행 직전 main 영속 자원 audit grep 1회면 회피 가능했던 결함 + ADR 0102 5단계 절차 LOCK으로 영구 차단) · 영향도(R1·R2 sub-agent 5명 동시 무효화 + orchestrator dispatch 비용 손실 + 사용자 신뢰 손실 → 본 fix로 재발 0) · 연결(헌법 §3 KIS-first raw fetch 외부 lens ↔ ADR 0102 내부 영속 자원 lens 양면 보완 — 동일 lens "자체 추론 0") · 확장성(6 영역 LOCK + 내부 도메인 모델 inventory 영역 확장 후보 cross-ref — ADR 0101 user_id BIGINT 초안 결함 evidence 1건 누적)
   - **W4 P1 R3 qa 부팅 차단 hotfix evidence (PR #681 062abecd)** — R2 backend-core T-MCP Phase 1 (PR #679) 신규 `api.mcp.persistence` 패키지 신설 시 `JpaScanConfiguration.kt` 화이트리스트 갱신 누락 → `UnsatisfiedDependencyException` → exit code 1 → api·batch·consumer 3 컨테이너 dependency RED → R3 qa 자율 hotfix 1줄 add-only + KDoc 7줄 사유 → 본 사이클 ADR 0103 신설로 장기 fix 옵션 A + 옵션 C-lite 하이브리드 채택 LOCK
   - **W4 P0·P1·P2 hotfix 매트릭스 마감** — W4 P0 ai-engineer `@Transactional` (PR #685, ce2405db) · W4 P1 backend-core graceful 핸들러 (PR #686, e13f7dcb) · W4 P2 market-data NoopReader 진단 (PR #684, 9b976901)

5. **모듈 매트릭스 8 → 9 JVM (ADR 0004 amend 동반)**:
   - **신설 9번째 모듈**: `market-trend-engine` (ADR 0100 정합) — Kotlin + Spring Context (Boot main 0). TrendCalculator (pure math · Spring 의존 0) + SmaTrendCalculator + CrossSignalCalculator + RsRatingCalculator + TrendPreferenceService + IndexTrendComposer + SectorRankingComposer + TrendPreferenceRepository port. ADR 0083 indicator-impl · ADR 0088 persistence-shared · ADR 0092 strategy-engine 패턴 1:1 reuse
   - 기존 8 모듈 보존: `domain` · `pattern-engine` · `indicator-impl` · `persistence-shared` · `strategy-engine` · `api` · `batch` · `consumer`

6. **AI 호출 0 LOCK 해제 + 5 진입점 active (사용자 옵션 트리거만 — 자동매매 본체 호출 0)**:
   - v3.8.0까지 W4 진입 0 LOCK · v3.9.0에서 W4 마감 → ai-engineer active dispatch 시작. **사용자 옵션 트리거만 호출** — 자동매매 본체 (시그널 → risk → 주문) AI 호출 0 LOCK 보존 (ADR 0023·0066·0067·0068·0080·0094 baseline)
   - 진입점 5: SYMBOL (종목 상세 페이지 사용자 클릭) · CHART (LWC 차트 사용자 클릭) · STRATEGY (DSL 자연어 helper · 기존 v3.3.0 NaturalLanguageDslTranslator amend) · SIGNAL (시그널 카드 사용자 클릭) · **MARKET (신규 — T-MARKET 페이지 진입점)**
   - 3 provider: Anthropic Claude · OpenAI GPT · Mock (auto fallback)
   - 5 prompt seed: `symbol_v1` · `chart_v1` · `strategy_v1` · `signal_v1` · `market_v1` (Flyway V50 prompt_template 영속)
   - W4 P0 hotfix: `@Transactional` 추가 (INSERT 0/5 → 5/5 회복 evidence) — ai_comment_log JPA persist 결함 fix

**v3.9.0 누적 ADR 103건** (v3.8.0 baseline 96건 + v3.8.1 patch 3건 0097·0098·0099 + v3.9.0 신규 4건 0100·0101·0102·0103 + amend 6건 0004·0026·0027·0037·0046·0100·0101 — 자세한 인덱스는 `_workspace/arch/adr/README.md`).

**v3.9.0 시점 자동매매 본체 정합 핵심 충족**:

1. **자동매매 본체 종착 노드 가동 LOCK 유지** — T-SIG → T-RISK → T-O chain 보존 + 3중 게이트 자본 보호 LOCK + persistence-shared 운영 흐름 작동률 100% 유지 + ADR 0097 ExecutionPositionUpdatePort (체결 직후 내부 position 갱신 — reconcile 5분 cron "사이" 빈틈 cover) + ADR 0098 KillSwitchQueryPort + Strategy 활성화 게이트 (fail-closed default=true · 신규 전략 PAUSED default · 백테스트 통과 의무) + ADR 0099 Reactor `.block()` non-blocking 계약 (2단 방어 + 5단 진단 체크리스트)
2. **W4 마감 + 3 트랙 동시 진입 LOCK** — T-MARKET (지수·섹터 추세) + T-AI (5 진입점 active) + T-MCP (6 tool active) 모두 R3 qa Visual 회귀 GREEN + docker logs evidence 통과 + W4 P0·P1·P2 hotfix 마감 + ADR 0103 부팅 차단 재발 방지 본문화
3. **모듈 매트릭스 8 → 9 (market-trend-engine 신설)** — domain · pattern-engine · indicator-impl · persistence-shared · strategy-engine · **market-trend-engine (신설 ADR 0100)** · api · batch · consumer (ADR 0004·0054·0083·0088·0092·0100 정합)
4. **운영 헌법 §9·§10 LOCK 유지** — SLO 5건 + severity 4단 routing + on-call rotation (사용자 1차 + backend-core 2차 + architect+영역 sub-agent 3차) + 분기 DR drill 의무 + qa Visual E2E 자동 재현 + 운영 ADR 양식 3 슬롯 (SLO 영향·rollback plan·관측 매트릭스 — 본 사이클 ADR 0100·0101·0102·0103 모두 적용)
5. **회귀 0** — v3.8.0 baseline + v3.8.1 patch + v3.9.0 W4 마감 변경 모두 add-only 또는 1:1 정합 변환 (R0 결함 fix는 spec 본문 V40~V45 → V47~V52 shift이며 실 운영 코드 0 touch)
6. **AI 호출 0 LOCK 해제 (사용자 옵션 트리거만)** — W4 마감으로 ai-engineer active dispatch 시작. 자동매매 본체 (시그널 → risk → 주문) AI 호출 0 LOCK 보존. 사용자 트리거 진입점 5건만 호출 (ADR 0023·0026 정합 보존)

---

## 인덱스

| 파일 | 작성자 | 내용 |
|------|--------|------|
| [01-architecture.md](01-architecture.md) | architect | Mermaid 9 JVM 모듈 + 외부 통합 + market-trend-engine 8번째 모듈 신설 명시 + Flyway V1~V52 시계열 + ADR 0100·0101·0102·0103 흐름 + 5 진입점 + 6 tool 도식 |
| [02-data-flow.md](02-data-flow.md) | backend-core | 모듈별 데이터 흐름 + T-MARKET cron Job 흐름 + T-AI AiOptionDispatcher 흐름 + T-MCP API key auth filter 흐름 + Kafka 토픽 매트릭스 +4 (market.index.updated · market.sector.updated · ai.comment.requested · ai.comment.completed) |
| [03-use-sequences.md](03-use-sequences.md) | market-data | 사용자 유즈 시퀀스 + KIS-first raw 출처 갱신 + KIS Overseas API 첫 진출 evidence + T-MARKET cron 발사 시나리오 + NXT resolver 보존 + AES-256-CBC H0STCNI9 복호화 |
| [04-feature-spec.md](04-feature-spec.md) | backend-core | 트랙 기능 명세 + T-MARKET (지수·섹터) + T-AI (5 진입점) + T-MCP (6 tool) + TrendPreference (V51 + extra JSONB) + W4 P0·P1·P2 hotfix 매트릭스 |
| [05-ui-inventory.md](05-ui-inventory.md) | frontend | 화면·메뉴·버튼 인벤토리 + DashboardPage MarketIndexStrip add-only + `/market/trend` 신규 페이지 + SettingsModal "추세 설정" section + 5 AiCard 진입점 + qa Visual 회귀 baseline 갱신 |
| [06-history-traceability.md](06-history-traceability.md) | architect | PR·ADR·기능 매핑 (v3.8.0 → v3.8.1 → v3.9.0 시계열 + W4 3 트랙 동시 진입 + ADR 0100·0101·0102·0103 신설 + R0 결함 fix evidence + W4 P0·P1·P2 hotfix 매트릭스) |
| [07-policy-evolution.md](07-policy-evolution.md) | architect | 운영 헌법 §1~§10 진화 누적 + ADR 0100·0101·0102·0103 결정 어필 + Codex 검증 게이트 4 lens 통과 evidence + 운영 ADR 양식 3 슬롯 (SLO 영향·rollback·관측) v3.9.0 첫 4회 적용 사례 |

---

## v3.8.0 → v3.9.0 통계 매트릭스 (W4 마감 baseline)

| 지표 | v3.8.0 (2026-05-22 KST) | v3.9.0 (2026-05-24 KST) | 증감 |
|------|---------------------|---------------------|------|
| PR # 누적 | #661+ (v3.8.0 cut) | #686+ (v3.9.0 cut 직전) | +25+ |
| Patch 누적 (minor 사이) | 1 (v3.7.1) | 1 (v3.8.1) | +1 |
| Minor 누적 | 8 (v3.0~v3.8) | 9 (v3.9.0 cut) | +1 |
| ADR 수 | 96 | **103** | **+7** (신규 0097·0098·0099 v3.8.1 patch + 0100·0101·0102·0103 v3.9.0 W4 마감) |
| ADR amend (v3.9.0 사이클) | — | **6** (0004·0026·0027·0037·0046·0100·0101 — ADR 0100 R0 결함 amend + ADR 0101 W4 P1 amend + ADR 0102 W4 P1 amend) | +6 |
| ADR Superseded (v3.9.0 사이클) | — | 0 | 0 |
| JVM 모듈 | 8 | **9** (+ market-trend-engine ADR 0100) | +1 |
| sub-agent 수 | 7 | 7 (보존 — ADR 0094 7 actor hierarchy LOCK 유지) | 0 |
| sub-agent active dispatch | 6 (ai-engineer 미가동) | **7 (ai-engineer W4 마감 active dispatch 시작)** | +1 |
| 페르소나 매트릭스 | 5 | 5 (보존) | 0 |
| Flyway 버전 | V44+ | **V52** (V45·V46 v3.8.1 patch + V47·V48·V49·V50·V51·V52 v3.9.0) | +8 |
| 외부 통합 (활성) | 6 (KIS REST·WS + DART + Naver + Anthropic + Yahoo dep) | **8** (+ KIS Overseas REST endpoint 2 신규 신설 = .IXIC + .SPX + OpenAI provider 신규) | +2 |
| **운영 헌법 §** | 10 (§9 운영 단계 룰 + §10 운영 메모) | 10 (보존) | 0 |
| 운영 ADR 양식 3 슬롯 적용 사례 | 2 (ADR 0095·0096) | **6** (+ ADR 0100·0101·0102·0103) | +4 |
| P0 OPS 마감 | 6/6 LOCK | 6/6 LOCK 보존 | 0 |
| SLO 정의 | 5 baseline | **5 baseline + S-OPS-1 신규 후보** (api·batch·consumer 3 컨테이너 부팅 성공률 = 100% — ADR 0103 §4-1) | +1 후보 |
| Prometheus alert rule | 10 (5 SLO × 2 임계) | 10 보존 + S-OPS-1 후보 amend backlog | 0 |
| W4 ★ 트랙 마감 누적 | 0 (W4 진입 0 LOCK) | **3 (T-MARKET + T-AI + T-MCP 동시 진입 + W4 P0·P1·P2 hotfix 마감)** | +3 |
| Kafka 토픽 (활성) | 25+ | **29+** (+ market.index.updated.v1 + market.sector.updated.v1 + ai.comment.requested.v1 + ai.comment.completed.v1) | +4 |
| Redis 키 namespace | 다수 | + market:index:{code} TTL 30s + ai:comment:cache | +2+ |
| REST endpoint 누적 | 약 90+ | **약 97+** (+ /api/market/index/snapshot + /api/market/index/{code}/trend + /api/market/sectors/snapshot + /api/market/sectors/ranking + /api/users/me/preferences/trend GET/PUT + /api/mcp/tools + /api/mcp/tools/{toolName}/invoke) | +7+ |
| STOMP 토픽 | (기존 다수) | + /topic/market.index.{code} + /topic/market.sector.rank + /topic/market.user.{userId}.pref + /topic/ai.comment.{userId} | +4 |
| frontend 라우트 | 17+ | **18+** (+ `/market/trend` 신규 페이지) | +1 |
| domain port 누적 | 38+ | **40+** (+ TrendPreferenceRepository + AiOptionDispatcher port) | +2+ |
| ai-engineer 상태 | 유지 (W4 진입 0) | **active dispatch (W4 마감 + 5 진입점 + 3 provider)** | 활성 |
| W4 진입 | 0 | **마감 (T-AI + T-MCP + T-MARKET 동시 진입)** | LOCK |
| AI 호출 (사용자 옵션 트리거) | 0 | **5 진입점 active (자동매매 본체 호출 0 LOCK 보존)** | +5 |
| AI 호출 (자동매매 본체) | 0 LOCK | 0 LOCK 보존 (ADR 0023·0066·0067·0068·0080·0094 정합) | 0 |
| **운영 흐름 작동률** | 100% LOCK | **100% LOCK 유지** + W4 P0·P1·P2 hotfix evidence + R3 qa 부팅 차단 hotfix evidence | LOCK |
| Living Policy 문서 | 6 | 6 (보존) | 0 |
| GitHub Actions workflow | 0 | 0 (보존) | 0 |
| 외부 코드 리뷰 흡수 누적 | 13 + Stage 2 audit 11 | **13 + Stage 2 audit 11 + Codex 검증 게이트 4 lens 통과 1 사이클** | +1 |
| R0 결함 fix evidence | 0 | **1 (R0 PR #674 Flyway V40~V45 LOCK 결함 → R0 amend PR #675 V47~V52 shift + ADR 0102 신설 본문화)** | +1 |
| 헌법 §7 escalation 가동 | 0 누적 | **1 (R0 결함 fix dispatch — sub-agent escalation → orchestrator kill → architect amend → 정합 머지 → 재 dispatch 5단계 완주)** | +1 |
| Codex 검증 게이트 lens 적용 | 0 | **4 lens 통과 (세심함·영향도·연결·확장성)** | +4 |

---

## 베이스라인 정합 ADR 35건 (v3.9.0 시점 핵심)

v3.9.0 baseline은 다음 ADR로 권위 추적 가능 (v3.8.0 30건 + v3.8.1 patch 3건 + v3.9.0 신규 4건 + amend 6건).

| ADR | 상태 | 본 v3.9.0 정합 |
|---|---|---|
| **0030** | Accepted | **마스터 아키텍처 SoT** — v3.9.0 baseline 사이클 §10 ADR 인덱스 0097·0098·0099·0100·0101·0102·0103 prepend + 본문 §10·§13 변경 이력 row 추가 + §6 모듈 매트릭스 8→9 amend (market-trend-engine 추가) |
| 0031 | Accepted | `release/v3.9.0` immutable LOCK 진입 |
| **0004** | Accepted (v3.9.0 amend) | **모듈 경계 8 → 9** — market-trend-engine 신규 추가 (ADR 0100 정합) |
| **0026** | Accepted (v3.9.0 amend) | **AI 옵션 보조 — 진입점 4 → 5 (MARKET 신규)** + V49·V50 LOCK (R0 결함 fix amend V42·V43 → V49·V50 shift · ADR 0102 정합) + AI 비용 통제 + KillSwitch + prompt_template:market_v1 seed + W4 P0 hotfix `@Transactional` |
| **0027** | Accepted (v3.9.0 amend) | **MCP 서버 — tool 5 → 6 (get_market_trend 신규)** + JSON Schema 본문 + V52 LOCK (R0 결함 fix amend V45 → V52 shift · ADR 0102 정합) + MCP 분당 100 초과 alert + W4 P1 graceful 핸들러 (backtest_strategy 422) |
| **0037** | Accepted (v3.9.0 amend) | **KIS Rate Limit — `INGESTION_LOW` 신규 priority 추가** (T-MARKET cron 26 req/min 4.3% 점유 안전) |
| **0046** | Accepted | **user_account 자연 키 — `user_id` VARCHAR(20) KIS 계좌번호** (ADR 0101 W4 P1 amend evidence) |
| 0048 | Accepted | KIS WS 41 구독 한도 baseline 보존. v3.9.0 신규 구독 추가 0 |
| 0052 | Accepted | Baseline 사이클 정형화. v3.9.0 baseline 본 사이클 적용 |
| 0066·0067·0068 | Accepted | T-SIG·T-RISK·T-O 자동매매 본체 chain. v3.9.0 변경 0 (보존). 운영 흐름 작동률 100% LOCK 유지 |
| **0080** | Accepted (amended by 0094) | 전체 에이전트 Opus 4.7 통일 — ADR 0094 amend 7 actor LOCK 보존. opus 4.7 LOCK 유지 |
| 0083·0088·0092 | Accepted | indicator-impl · persistence-shared · strategy-engine 라이브러리 모듈 패턴 — v3.9.0 market-trend-engine 9번째 모듈 1:1 reuse evidence |
| **0094** | Accepted | 운영 단계 transition + 7 actor hierarchy LOCK 보존. v3.9.0 ai-engineer active dispatch 시작 (W4 마감) |
| 0095·0096 | Accepted | P0-OPS-3 SLO/SLA + P0-OPS-4 DR/backup. v3.9.0 변경 0 (보존). S-OPS-1 신규 SLO 후보 backlog amend (ADR 0103 §4-1) |
| **0097** | Accepted (v3.8.1 patch) | **ExecutionPositionUpdatePort** — 체결 직후 내부 position 갱신 도메인 port (reconcile 5분 cron "사이" 빈틈 cover + WS H0STCNI / REST inquire-ccnl 양 path 일관성 LOCK) — 코덱스 협업 P0 4건 사이클 PR-2 |
| **0098** | Accepted (v3.8.1 patch) | **KillSwitchQueryPort + Strategy 활성화 게이트** — fail-closed default=true · 신규 전략 status=PAUSED default · PAUSED→ACTIVE 백테스트 통과 의무 (`BacktestRunJpaRepository.existsAutoActivationReadyRun`) — 코덱스 협업 P0 4건 사이클 PR-2 |
| **0099** | Accepted (v3.8.1 patch) | **Reactor `.block()` non-blocking 계약 LOCK** — callee `subscribeOn(boundedElastic)` + caller `isInNonBlockingThread()` 2단 방어 + 5단 진단 체크리스트 + code-convention §reactor 등재 — v3.8.0 hotfix root cause 정정 |
| **0100** | **Accepted (2026-05-24)** | **T-MARKET 트랙 — 지수·섹터 추세 분석 모듈 (market-trend-engine 신설) + KIS endpoint 5 LOCK + cron 매트릭스 + Flyway V47·V48 + REST 6 + STOMP 3 + 운영 ADR 양식 3 슬롯 첫 적용 사례 (v3.9.0) + KIS-first raw fetch 5 evidence + R0 결함 fix amend (V40·V41 → V47·V48 shift)** |
| **0101** | **Accepted (2026-05-24, W4 P1 amend)** | **TrendPreference 1급 도메인 + `user_preference` 표 (V51) + extra JSONB 확장 슬롯 단일 진입점 + Bean Validation + REST 2 endpoint Swagger 4단 + STOMP fanout + 시드 SQL idempotent + 후속 사이클 10+ 슬롯 + R0 결함 fix amend (V44 → V51 shift) + W4 P1 amend (user_id BIGINT → VARCHAR(20) 실 schema 정합)** |
| **0102** | **Accepted (2026-05-24 신설, W4 P1 amend)** | **spec/plan 발행 시 main 영속 자원 audit 5단계 절차 LOCK** — R0 PR #674 Flyway V40~V45 결함 fix 본문화 + 영속 자원 6 영역 (Flyway·Kafka·Redis·STOMP·REST·ENV) + 면제 조건 3건 (md only·hotfix·기존 LOCK amend) + escalation 경로 5단계 + 발생 evidence 본문 + 운영 ADR 양식 3 슬롯 + 후속 사이클 6 슬롯 + W4 P1 amend (§5-4 영역 확장 후보 cross-ref 1건) |
| **0103** | **Accepted (2026-05-24 신설)** | **JpaScanConfiguration 신규 패키지 누락 차단** — 옵션 A (basePackages 단일화) + 옵션 C-lite (smoke test) 하이브리드 + 임시 baseline (R3 hotfix 화이트리스트 3건 명시 유지) + 운영 ADR 양식 3 슬롯 + 후속 사이클 6 슬롯 + S-OPS-1 신규 SLO 후보 |

> **ADR 0030 master-architecture §10 갱신**: 본 v3.9.0 baseline 시점 ADR 0030 §10 인덱스에 0100·0101·0102·0103 row + amend 마커 정합 완료(개별 ADR 발행 PR이 동반 갱신). 본 baseline PR은 §10 + §13 변경 이력 row 추가 + §6 모듈 매트릭스 9 갱신 + §10-4 메타 카운트 갱신.

---

## v3.8.0 → v3.9.0 사이 1 patch 사이클 인덱스 (요약)

| 버전 | 일자 | 미러스톤 | 관련 ADR |
|------|------|----------|----------|
| v3.8.0 | 2026-05-22 | 운영 단계 진입 baseline (개발 단계 종착 + 7 actor reshuffle + qa Visual 격상 + P0 OPS 6/6 LOCK) | 0091·0092·0093·0094·0095·0096 + 운영 헌법 §9·§10 신설 |
| **v3.8.1** | 2026-05-23 | **코덱스 협업 P0 4건 사이클** — PR-1 KIS `.block()` subscribeOn hotfix + PR-2 ADR 0097·0098·0099 reactor 컨벤션 + PR-3 ExecutionPositionUpdatePort + KillSwitchQueryPort + PR-4 trade breakdown 화면 | 0097·0098·0099 (v3.8.1 patch sequential 3건) |
| **v3.9.0** | **2026-05-24** | **W4 마감 baseline (T-MARKET + T-AI + T-MCP 3 트랙 동시 진입 + market-trend-engine 8번째 모듈 신설 + ADR 0100·0101·0102·0103 신설 + R0 결함 fix evidence + Codex 검증 게이트 4 lens 통과)** | **0100·0101·0102·0103 + 6 ADR amend (0004·0026·0027·0037·0046·0100·0101)** |

---

## Codex 검증 게이트 통과 evidence (사용자 메모리 `feedback-codex-validation-gate` 4 lens 정합)

본 v3.9.0 사이클은 Codex 외부 review 4 lens (세심함·영향도·연결·확장성) 정합 검증 게이트 통과 evidence 첫 사이클이다. 본 baseline PR은 본 lens별 통과 evidence를 본문화한다.

| lens | v3.9.0 사이클 evidence | 본 baseline 본문화 위치 |
|------|---------|---------|
| **세심함** | R0 PR #674 (1411d392)는 W4 통합 사이클 spec/ADR 발행 시점에 main HEAD audit 누락 — Flyway V40~V45 LOCK 발행 후 main HEAD V40~V46 점유 사실 발견 → grep 1회면 회피 가능했던 결함 → **ADR 0102 5단계 절차 LOCK 신설로 영구 차단** | 01-architecture §Flyway V1~V52 시계열 본문 + 07-policy §운영 헌법 §3 KIS-first raw fetch 양면 본문 |
| **영향도** | R1·R2 sub-agent 5명 동시 무효화 + orchestrator dispatch 비용 손실 + 사용자 신뢰 손실 → 헌법 §7 escalation 5단계 완주 evidence (sub-agent escalation → orchestrator kill → architect amend → 정합 머지 → 재 dispatch) | 06-history §R0 결함 fix evidence + escalation 경로 매트릭스 |
| **연결** | 헌법 §3 (KIS-first raw fetch — 외부 데이터 규격) ↔ ADR 0102 (내부 영속 자원 audit) — 동일 lens "자체 추론 0"의 양면 보완 + ADR 0103 (JpaScan 패키지 누락 차단) — R3 qa 부팅 차단 hotfix → architect 후속 재발 방지 ADR 본문화 chain | 07-policy §ADR 0102·0103 본문화 어필 + 헌법 §3 lens 양면 본문 |
| **확장성** | ADR 0102 영속 자원 6 영역 (Flyway·Kafka·Redis·STOMP·REST·ENV) LOCK + 내부 도메인 모델 inventory 영역 확장 후보 cross-ref (W4 P1 amend §5-4) — 누적 evidence 3건+ 시 별 사이클 amend trigger | 07-policy §운영 ADR 양식 3 슬롯 (SLO 영향·rollback·관측 매트릭스) v3.9.0 첫 4회 적용 사례 본문 |

---

## 작성 규약

- 본문 한국어, 코드/명령은 영어 그대로
- Mermaid 다이어그램 의무
- raw 출처 인용 의무 (KIS API · DART · 코드 path)
- 추측·관념적 서술 금지
- TBD 표시 — 미작성 영역

---

## v3.9.0 사이클 핵심 메시지

**W4 마감 baseline (T-MARKET + T-AI + T-MCP 3 트랙 동시 진입 + market-trend-engine 8번째 모듈 신설 + ADR 0100·0101·0102·0103 신설 + 6 ADR amend + R0 spec audit 결함 fix evidence + Codex 검증 게이트 4 lens 통과 + W4 P0·P1·P2 hotfix 사이클 LOCK)** — v3.8.0 baseline (운영 단계 진입 + 7 actor reshuffle + qa Visual 격상 + P0 OPS 6/6) → v3.8.1 patch (코덱스 협업 P0 4건 사이클 — PR-1 KIS .block() + PR-2 ADR 0097·0098·0099 + PR-3 port + PR-4 trade breakdown) → v3.9.0 (W4 마감 + 사용자 brainstorming 통합 design cec6a4d6 결재 정합 + R0 PR #674 spec audit 결함 → R0 amend PR #675 V47~V52 shift + ADR 0102 신설 → R1·R2·R3 + W4 P1 사이클 일괄 진행 + W4 P0·P1·P2 hotfix 마감). **W4 마감 LOCK** — fitness gap 기준이 "운영 단계 진입" → "운영 단계 + W4 AI/MCP active dispatch"로 LOCK 확장. ai-engineer는 W4 마감으로 active dispatch 시작 — 자동매매 본체 (시그널 → risk → 주문) AI 호출 0 LOCK 보존 (사용자 옵션 트리거 진입점 5건만 호출). v3.9.0 = W3 자동매매 본체 안정화 LOCK + 7 sub-agent hierarchy + **9 JVM 모듈 매트릭스 (+ market-trend-engine)** + 페르소나 5 매트릭스 + 운영 헌법 §9·§10 LOCK + P0 OPS 6/6 LOCK 보존 + **Codex 검증 게이트 4 lens 통과** + ADR 0102 spec audit 5단계 절차 LOCK 신설 + ADR 0103 JpaScan 패키지 누락 차단 신설 사이클. **다음 v3.9.x patch 후보** = ADR 0103 옵션 C smoke test 적용 (P1 backend-core dispatch) + W4 P0·P1·P2 hotfix dogfooding 누적 + AI prompt seed dogfooding 누적. **다음 v3.10.0 minor 후보** = W5(T-LWC · T-LEARN) 진입 또는 운영 고도화 (사용자 결정 의제). 자동매매 본체 AI 호출 0 LOCK 지속 (ADR 0023·0066·0067·0068·0080·0094 baseline).
