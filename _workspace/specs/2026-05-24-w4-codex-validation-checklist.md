# W4 통합 사이클 Codex 외부 review 통과 prereq 체크리스트 (4 lens 격자)

> **상태**: architect R0 spec (사용자 명시 게이트 "Codex가 봐도 손댈 게 없는 수준" 정합)
> **권위**: 각 sub-agent dispatch 시 본 spec §sub-agent 격자 self-check 의무. v3.9.0 cut 직전 Codex 외부 review 통과 prereq.
> **소유**: architect → 6 sub-agent
> **작성일**: 2026-05-24

---

## §0. 4 lens 정의 (사용자 명시)

| lens | 의미 |
|---|---|
| **세심함** | inline KDoc/JSDoc · null guard · edge case · error path · 한국어 message · timing-safe 패턴 · 변수 치환 missing key 등 |
| **영향도** | 기존 ADR·컴포넌트·DB·Redis·STOMP·KIS endpoint·자본 path 영향 매트릭스 사전 발행 + grep evidence |
| **연결** | 신규 컴포넌트가 활용하는 기존 port·event·패턴 매트릭스 명시 + reuse 우선 + 패턴 1:1 정합 |
| **확장성** | 1차 LOCK + post-MVP 자연 확장 슬롯 (특히 `user_preference.extra JSONB` activation path) + interface 추상화로 add-only 흡수 path |

---

## §1. architect R0 (본 PR — 이미 진행 중)

### 1-1. 세심함 체크

- [ ] ADR 0100 §2-3 KIS endpoint 5 매트릭스 — endpoint URL · tr_id · 모듈 위치 · 호출 빈도 모두 명시
- [ ] ADR 0100 §3-2 raw 출처 5건 인용 — GitHub blob URL + 필드 매트릭스 + sample call evidence
- [ ] ADR 0101 §2-1 TrendPreference require 5건 본문 — size 1~5 · 각 1~252 · sorted ASC · rsPeriod 1~252 · crossPair invariant
- [ ] ADR 0101 §2-2 DDL CHECK 제약 2건 + 한국어 COMMENT
- [ ] ADR 0026·0027 amend — V20·V21 예약 폐기 → V42·V43·V45 LOCK 명시
- [ ] ADR 0094 §4-4-B AI KillSwitch `MAGICJAR_AI_KILLSWITCH` env 명시
- [ ] ADR 0095 §3-2 amend 3 신규 row (S-AI-1 · S-MCP-1 · S-MARKET-1) — 임계 + Slack severity 명시
- [ ] 각 ADR 끝 변경 이력 row 작성자 (architect, opus 4.7 1M) 명시
- [ ] R0 design spec §2 한 화면 다이어그램 + brainstorming §1-1 정합
- [ ] CHANGELOG slot 7 anchor + sub-agent row format `(작성: <agent>)` 마커 정합

### 1-2. 영향도 체크

- [ ] ADR 0100 §4-1 기존 ADR 영향 12 row + §4-2 영역 침범 매트릭스 + §4-3 연결 매트릭스
- [ ] ADR 0101 §3-1 영향 6 row + §3-2 영역 침범 + §3-3 연결
- [ ] ADR 0004 amend — 7 → 8 모듈 매트릭스 + 의존 방향 정합 보존
- [ ] ADR 0037 amend — INGESTION_LOW 사유 본문 (drop 우선순위 + user-facing latency 허용폭)
- [ ] ADR 0094 §4-4 W4 3 항목 진입 결재 매트릭스 + §4-4-A T-MARKET cron SLO 영향 + §4-4-B AI 비용 통제
- [ ] master-architecture §10 인덱스 0100·0101 row + §13 변경 이력 row

### 1-3. 연결 체크

- [ ] ADR 0100 §4-3 신규 → 기존 reuse 9 row (KisRateLimiterPort · MarketSession FSM · indicator-impl 패턴 · user_account FK · OnboardingGuardFilter · KafkaTopicConfig · WebSocketConfig · useMarketIndex hook · TrendPreference port)
- [ ] ADR 0101 §3-3 신규 → 기존 reuse 6 row
- [ ] ADR 0026 amend — prompt_template seed 5건 (market_v1 신규) + `{{maPeriods}}`·`{{rsPeriod}}` 변수 치환 → ADR 0101 TrendPreference 사용자 mental model 반영
- [ ] ADR 0027 amend — get_market_trend tool input source ADR 0100 · periods 생략 시 user pref echo ADR 0101

### 1-4. 확장성 체크

- [ ] ADR 0101 §2-5 `extra JSONB` 확장 슬롯 12+ 후보 매트릭스 (MA type · RSI · MACD · 차트 default · 알림 임계 · 다크모드 · AI 메모 · MCP 권한 etc) — 모두 add-only 마이그레이션 0
- [ ] ADR 0100 §8 post-W4 후속 사이클 매트릭스 (MA type · RS UI · 섹터 RSI · sentiment fusion · KIS WS · 알림 임계)
- [ ] ADR 0101 §7 post-MVP 10+ 슬롯
- [ ] AiCommentProvider interface — 4번째 provider 추가 시 본체 변경 0
- [ ] McpToolRegistry — 7번째 tool 추가 시 본체 변경 0
- [ ] TrendCalculator interface — 1차 SMA · post-MVP EMA·WMA·DEMA add-only

---

## §2. market-data — T-MARKET Phase 1 (R1)

### 2-1. 세심함 체크

- [ ] 각 KIS adapter 5 파일 상단 KDoc raw URL + tr_id 명시 의무 (R0 spec §5 정합)
- [ ] sample call inline 주석 (예: `inquire_index_price("U", "0001")` → KOSPI)
- [ ] null guard — KIS 응답 raw_payload JSONB 보존 + 필드 missing 시 데이터 부족 graceful (한국어)
- [ ] EGW00201 / HTTP 429 backoff 경로 — ADR 0037 §2-2 매트릭스 정합 (1초 backoff + 1회 재시도)
- [ ] pagination tr_cont=M/F 재귀 safety — max depth 10 (default · env override `MAGICJAR_KIS_OVERSEAS_DAILY_MAX_PAGES`)
- [ ] `MarketSession FSM` (ADR 0013) 게이트 — CLOSED·HOLIDAY 시 skip + SystemEvent emit (P4 INFO)
- [ ] MarketIndexSyncJob cron 발사 빈도 metric — Micrometer counter `magicjar.batch.market.index.sync.invocations` (ADR 0100 §6-3 정합)

### 2-2. 영향도 체크

- [ ] KIS rate limit 26 req/min = 0.43 req/s (한도 4.3% 점유) 실측 evidence 의무
- [ ] grep — 자본 path (`OrderService` · `OrderRouterKafkaListener` · `KillSwitchAutoToggler`) 0 touch 검증
- [ ] grep — 기존 KIS adapter 0 touch (신규 디렉토리 add-only 검증)
- [ ] Flyway V40·V41 외 신규 마이그레이션 0건 검증
- [ ] KisCallPriority enum 4 value (USER_PATH·INGESTION·BACKFILL·INGESTION_LOW) 모두 grep evidence

### 2-3. 연결 체크

- [ ] 각 KIS adapter — `KisRateLimiterPort.acquire(INGESTION_LOW)` 호출 정합 (ADR 0037 amend)
- [ ] WebClient 기존 패턴 1:1 reuse (`KisCandleAdapter` · `KisInquireBalanceHttpAdapter` 패턴 정합)
- [ ] 기존 KIS auth path reuse (`KisAuthValidator` · `KisOAuthTokenStore` 호출)
- [ ] MarketSession FSM (ADR 0013) reuse
- [ ] `KisRestErrorMapper` 기존 reuse (EGW00201 → 표준 throw)

### 2-4. 확장성 체크

- [ ] dailyprice MODP=0/1 옵션 외부화 (`MAGICJAR_KIS_OVERSEAS_MODP` env)
- [ ] pagination max depth env 조정 (`MAGICJAR_KIS_OVERSEAS_DAILY_MAX_PAGES` default 10)
- [ ] 향후 EXCD `NYS`·`AMS`·`HKS`·`TSE` 추가 시 adapter 변경 0 (configuration only)
- [ ] 신규 지수 (다우 `.DJI` 등) 추가 시 application.yml `magicjar.market.overseas.symbols` 만 추가

---

## §3. backend-core — T-MARKET Phase 2 (R1)

### 3-1. 세심함 체크

- [ ] TrendPreference require 5건 본문 (ADR 0101 §2-1 정합) + 모두 한국어 에러 메시지
- [ ] Bean Validation `@Size(min=1, max=5)` · `@Min(1)` · `@Max(252)` · 한국어 default message resolver
- [ ] DDL CHECK 제약 2건 (`jsonb_array_length` · `trend_rs_period BETWEEN`)
- [ ] `dataInsufficient` flag — candles.size < longMa 시 `crossSignal=NEUTRAL`·`trendLabel=SIDEWAYS`·ma 부분 채움 + true → frontend "데이터 부족" 한국어 표시 (mock 합성 0 — 사용자 메모리 feedback_no_mock_data 정합)
- [ ] crossPair null safe — `null` 시 maPeriods 최소/최대 auto
- [ ] CrossSignal 표준 정의 정합 (ADR 0100 §2-2-D — GOLDEN·DEAD·NEUTRAL `[t-1]` vs `[t]` 비교)
- [ ] TrendLabel UP/SIDEWAYS/DOWN ±0.5% 임계 LOCK
- [ ] SectorRanking todayTop 가중치 0.7+0.3 LOCK (1차 fixed · post-MVP user-settable backlog)
- [ ] Mansfield RS 계산 — 결측 시 graceful skip + RS=1.0 fallback
- [ ] Swagger 4단 한국어 description 의무 (사용자 메모리 feedback_swagger_pattern 정합)

### 3-2. 영향도 체크

- [ ] 신규 모듈 `market-trend-engine` 1개 추가 — settings.gradle.kts +1 라인 amend
- [ ] 신규 표 `user_preference` 1건 (V44) — 시드 SQL idempotent 검증
- [ ] grep — 기존 controller / service 0 touch
- [ ] grep — 자본 path 0 touch
- [ ] grep — frontend 영역 0 touch (frontend는 R2)

### 3-3. 연결 체크

- [ ] TrendPreferenceRepository port (domain) + JpaAdapter (api 단독 — ADR 0101 §2-1-C 정합)
- [ ] indicator-impl 패턴 1:1 reuse (ADR 0083) — pure math 모듈 + Spring Context + domain 의존
- [ ] user_account (V8) FK reference + ON DELETE CASCADE
- [ ] WebSocketConfig 기존 reuse (3 STOMP topic publish)
- [ ] `KafkaTopicConfig` 패턴 1:1 — `market.index.updated.v1` · `market.sector.updated.v1`
- [ ] OnboardingGuardFilter pattern reuse (사용자 인증)

### 3-4. 확장성 체크

- [ ] TrendCalculator interface — 1차 SmaTrendCalculator만 구현 · post-MVP EmaTrendCalculator·WmaTrendCalculator·DemaTrendCalculator add-only
- [ ] `user_preference.extra JSONB` 확장 슬롯 (ADR 0101 §2-5 — 12+ 후보) — Kotlin Map<String, Any?> adapter
- [ ] crossPair · rsPeriod 1차 readonly UI · API 측 user-settable (post-MVP UI unlock)
- [ ] SectorRanking 가중치 1차 fixed · post-MVP user-settable (backlog §)
- [ ] sentiment fusion path (T-NEWS news.collected.v1 → trend lens 합류) post-MVP

### 3-5. Spring Data JPA Repository default fun 함정 (헌법 §6 + ADR 0051)

- [ ] `TrendPreferenceJpaRepository` interface 안 body 가진 fun 0건 LOCK 검증 — UPSERT 헬퍼는 caller class (`TrendPreferenceJpaAdapter`)의 private fun으로 이동

---

## §4. ai-engineer — T-AI Phase 1 (R1)

### 4-1. 세심함 체크

- [ ] Resilience4j circuit 30s open + 3 retry exponential 1s/2s/4s (ADR 0026 §2-4 baseline)
- [ ] graceful refuse 한국어 message ("AI 비활성 상태" · "AI 키 누락" · "1일 한도 초과" · "AI KillSwitch 활성")
- [ ] Redis prompt cache key `ai:prompt:cache:{templateHash}` 5분 TTL · MISS 시 LLM 호출 path
- [ ] 1일 사용자 토큰 한도 default 1000 (env override `MAGICJAR_AI_USER_DAILY_TOKEN_LIMIT`)
- [ ] AI 비용 임계 — `ai_comment_log.cost_usd` 월 누적 $10 초과 시 Slack CRITICAL (ADR 0095 amend S-AI-1)
- [ ] AI KillSwitch — `MAGICJAR_AI_KILLSWITCH=true` env toggle 시 모든 AI 호출 graceful refuse (ADR 0094 §4-4-B amend)
- [ ] PromptTemplate 변수 치환 — missing key 시 명확한 error throw (silent skip 금지)
- [ ] prompt_template seed 5건 SQL — symbol_v1·chart_v1·strategy_v1·signal_v1·market_v1 모두 한국어 prompt template
- [ ] ai_comment_log 모든 컬럼 null safe + cached BOOL 정합

### 4-2. 영향도 체크

- [ ] grep — W1·W2·W3 코드 영역 (W4 외 모든 영역) LLM provider import 0건 검증 (ADR 0026 §3)
- [ ] grep — 자본 path 0 touch (signal·order·risk·killswitch path 모두 격리)
- [ ] `MAGICJAR_AI_ENABLED=false` default 검증 (application.yml + .env.example)
- [ ] Flyway V42·V43 외 신규 마이그레이션 0건 검증

### 4-3. 연결 체크

- [ ] AiCommentProvider interface — 3 adapter (Anthropic·Gemini·OpenAI) 모두 구현
- [ ] Redis client 기존 reuse (`RedisCacheConfig` reuse)
- [ ] Kafka topic 패턴 1:1 (`ai.comment.requested.v1` · `ai.comment.responded.v1`)
- [ ] OnboardingGuardFilter pattern reuse
- [ ] ADR 0044 notification_outbox reuse 옵션 (alert path — AI 비용 임계 Slack)

### 4-4. 확장성 체크

- [ ] AiCommentProvider interface 추상화 → 4번째 provider 추가 시 본체 변경 0
- [ ] PromptTemplate version 컬럼 활용 → A/B 비교 path (post-MVP backlog)
- [ ] `ai_comment_log` 추가 컬럼 add-only 흡수
- [ ] AiCommentType enum — 6번째 진입점 추가 시 + prompt_template seed 1건 add-only
- [ ] provider failover (Anthropic 실패 → Gemini 자동 fallback) post-MVP backlog

---

## §5. frontend — T-MARKET·T-AI UI (R2)

### 5-1. 세심함 체크

- [ ] TanStack Query stale time · refetchInterval · race-safe merge (polling 30s + STOMP push 동시)
- [ ] empty state 한국어 — "데이터 없음" · "AI 응답 없음" · "사용자 설정 없음" (mock 합성 0 — 사용자 메모리 feedback_no_mock_data 정합)
- [ ] loading state · error boundary 모든 hook + component
- [ ] LWC v4 MA overlay — user pref periods 색상 분리 + legend 한국어
- [ ] SettingsModal "추세 설정" Bean Validation 정합 client side preview + backend 400 한국어 응답 표시
- [ ] AiCard markdown render + 인용 출처 + token 비용 표시 (사용자 비용 가시성)
- [ ] STOMP subscribe error recovery (reconnect path)
- [ ] feature flag `VITE_FEATURE_MARKET_TREND` 환경별 toggle (rollback path)

### 5-2. 영향도 체크

- [ ] grep — 기존 page/component 0 touch (add-only LOCK · DashboardPage·SettingsModal mount만 amend +10~30 라인)
- [ ] grep — feature flag default true · false 시 정상 렌더링 검증 (rollback evidence)
- [ ] 기존 LWC v4 차트 컴포넌트 0 touch (신규 IndexChartPanel 별도)

### 5-3. 연결 체크

- [ ] 기존 LWC v4 차트 컴포넌트 import + 패턴 1:1 reuse
- [ ] 기존 STOMP client wrapper reuse
- [ ] 기존 TanStack Query QueryClient reuse
- [ ] 기존 toast/알람센터 reuse (AI 응답 도착 시 toast)
- [ ] backend DTO 1:1 정합 (TypeScript type — Swagger schema mirror 의무)

### 5-4. 확장성 체크

- [ ] AiCard 컴포넌트 공통화 → 6번째 진입점 추가 시 hook + button mount만
- [ ] TrendPreferenceSection extra JSONB UI 노출 path (post-MVP — ADR 0101 §2-5)
- [ ] IndexChartPanel timeframe toggle (1d default · post-MVP 1m·5m·1w add-only)
- [ ] SectorRankingTable 가중치 사용자 설정 UI (post-MVP)

### 5-5. qa Visual 자동 dispatch trigger (헌법 §9-2)

- [ ] frontend 핵심 화면 (DashboardPage·SettingsModal·신규 `/market/trend` page) 변경 → R3 qa Visual 회귀 의무 dispatch

---

## §6. backend-core — T-MCP Phase 1 (R2)

### 6-1. 세심함 체크

- [ ] JSON Schema 6 tool 입력/응답 정밀 정합 (ADR 0027 §2-3 amend 정합 — `get_market_trend` JSON Schema 본문 정확 인용)
- [ ] params JSONB null safe + 한국어 error response
- [ ] response_size_bytes 계측 (`mcp_audit` insert 시)
- [ ] error_message graceful — stacktrace 노출 0 (외부 client 보안)
- [ ] API key timing-safe compare (constant-time 의무) — `MessageDigest.isEqual` 사용
- [ ] McpRateLimitGuard — 분당 호출 100 초과 시 SystemEvent emit (ADR 0095 amend S-MCP-1)
- [ ] explain_indicator 정적 catalog seed — `indicator_catalog.json` resource 20+ 지표 (RSI·MACD·MA·BBands·ATR·OBV·VWAP 등)

### 6-2. 영향도 체크

- [ ] grep — write tool 0건 (모든 tool read-only LOCK 검증 — ADR 0027 §2-2 정합)
- [ ] grep — 기존 controller / service 0 touch
- [ ] grep — Flyway V45 외 신규 마이그레이션 0건
- [ ] OnboardingGuardFilter skip (인증 분리) — McpController는 ApiKeyAuthFilter 단독

### 6-3. 연결 체크

- [ ] TrendPreferenceService reuse (`get_market_trend`)
- [ ] 기존 CandleService reuse (`get_candles`)
- [ ] 기존 ScreenerService reuse (`run_screener`)
- [ ] 기존 JournalService reuse (`get_journal`)
- [ ] 기존 BacktestService reuse (`backtest_strategy`)
- [ ] OnboardingGuardFilter skip (인증 분리)

### 6-4. 확장성 체크

- [ ] McpToolRegistry로 7번째 tool 추가 시 본체 변경 0
- [ ] OAuth2 인증 path (post-MVP — ADR 0027 §2-4)
- [ ] multi-tenant API key path (post-MVP)
- [ ] write tool (주문 발행 등) 안전성 검증 후 post-MVP (ADR 0027 §2-2 정합)
- [ ] explain_indicator catalog 추가 시 JSON resource add-only

---

## §7. qa — E2E Visual 회귀 7 케이스 (R3)

### 7-1. 세심함 체크

- [ ] 각 케이스 multi-step 시나리오 + 한국어 assertion 메시지
- [ ] screenshot diff threshold < 1% LOCK
- [ ] timing-safe wait + stable selector (data-testid 의무)
- [ ] mock mode vs 활성 mode 양 path 모두 검증
- [ ] Bean Validation 400 한국어 응답 정밀 정합 검증 (0개·6개·중복·253)

### 7-2. 영향도 체크

- [ ] grep — 기존 e2e 테스트 0 touch (신규 디렉토리 `tests/e2e/w4/` add-only)
- [ ] docker logs 4 모듈 (api·batch·consumer·frontend) "ERROR\|FATAL" 0건 evidence (cut 직전 의무 — feedback_docker_log_verify_before_cut)
- [ ] KIS rate limit 실측 evidence — MarketIndexSyncJob 1분 cron 60회 운영 후 26 req/min ± 5% · EGW00201 0건

### 7-3. 연결 체크

- [ ] 기존 e2e 셋업 reuse (`playwright.config.ts` reuse)
- [ ] backend mock mode env reuse
- [ ] LWC v4 차트 렌더링 evidence (canvas screenshot)

### 7-4. 확장성 체크

- [ ] 새 시나리오 추가 시 spec 파일 1건 add-only
- [ ] screenshot baseline 갱신 절차 명시 (사용자 결재 후 git commit)
- [ ] DR drill 자동 재현 path (ADR 0096 §6-2 정합 — qa 분기별 의무)

---

## §8. 잠재 결함 사전 점검 path (Codex 외부 review 대비)

### 8-1. 자체 추론 risk (KIS-first 정책 위반 risk)

- [ ] 각 sub-agent inline KDoc raw URL + tr_id 명시 의무 — grep evidence 의무
- [ ] R1 market-data raw response sample 첨부 의무 (`_workspace/specs/2026-05-24-r1-market-data-kis-raw-verification.md`) — `.IXIC` symbol query 실측 evidence

### 8-2. 자본 path 영향 risk

- [ ] 모든 W4 PR — grep `OrderService` · `OrderRouterKafkaListener` · `KillSwitchAutoToggler` 0 touch 검증 의무

### 8-3. mock 합성 risk (사용자 메모리 feedback_no_mock_data)

- [ ] frontend empty state 한국어 표시 grep evidence
- [ ] backend graceful skip + `dataInsufficient` flag echo 의무
- [ ] AI mock mode 명시 — "AI 응답 없음" 한국어

### 8-4. 자체 결정 권한 침해 risk

- [ ] Flyway V40~V45 외 신규 마이그레이션 0건 — orchestrator escalation 의무
- [ ] 모듈 경계 변경 0 (architect 권위) — settings.gradle.kts amend는 backend-core (T-MARKET) 단독

### 8-5. 성능 risk

- [ ] KIS rate limit 26 req/min 안전 검증 (한도 4.3% 점유)
- [ ] Redis cache hit ratio target > 80% (P1 backlog SLO)
- [ ] STOMP push latency p95 < 200ms target

### 8-6. 보안 risk

- [ ] MCP API key timing-safe compare 의무
- [ ] AI 비용 KillSwitch path 명시
- [ ] secret 평문 응답 금지 (사용자 메모리 feedback_secret_handling 정합)

---

## §9. v3.9.0 release 게이트 (외부 Codex review prereq)

R3 머지 + 다음 모두 GREEN 후 Codex 외부 review 의뢰:

1. R3 qa 7 케이스 모두 GREEN
2. `./gradlew check` GREEN + `scripts/smoke.sh` 통과
3. docker logs 4 모듈 0건 (R3 qa evidence)
4. 각 sub-agent self-check 4 lens 격자 모두 ✅ 체크
5. KIS-first raw fetch 5단계 evidence 보고서 (R1 market-data 산출)
6. mock 합성 0 + 자본 path 0 touch + AI 호출 W4 외 0 grep evidence

Codex 외부 review 통과 후 v3.9.0 cut.

---

## §10. References

- 사용자 명시 게이트: "Codex가 봐도 손댈 게 없는 수준" + 4 lens (세심·영향도·연결·확장성)
- R0 design: `_workspace/specs/2026-05-24-w4-r0-design.md`
- R1·R2·R3 dispatch spec: `_workspace/specs/2026-05-24-w4-r1-r2-r3-dispatch-spec.md`
- 충돌 매트릭스: `_workspace/specs/2026-05-24-w4-conflict-matrix.md`
- brainstorming 통합본: `_workspace/specs/2026-05-24-w4-t-market-t-ai-t-mcp-design.md`
- 헌법 `_workspace/arch/agent-charter.md`
- KIS-first 정책 `docs/architecture/policies/kis-first-raw-fetch.md`
