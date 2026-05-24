# W4 통합 사이클 R1·R2·R3 sub-agent dispatch spec

> **상태**: architect R0 dispatch spec (사용자 brainstorming `_workspace/specs/2026-05-24-w4-t-market-t-ai-t-mcp-design.md` cec6a4d6 정합)
> **권위**: R0 architect 머지 후 orchestrator가 본 spec §R1·§R2·§R3 정합으로 sub-agent dispatch trigger
> **소유**: architect → orchestrator → 6 sub-agent (market-data · backend-core · ai-engineer · frontend · qa)
> **작성일**: 2026-05-24
> **release 타깃**: v3.9.0

---

## §0. 사용자 명시 게이트

> "Codex가 봐도 손댈 게 없는 수준" + "세심함·영향도·연결·확장성 lens 4 모두 충족"

각 sub-agent는 본인 영역에서 4 lens 충족 의무. 격자 본문 `_workspace/specs/2026-05-24-w4-codex-validation-checklist.md`.

---

## §R0. architect R0 (본 PR — 이미 진행 중)

(본 PR 본체 — `_workspace/specs/2026-05-24-w4-r0-design.md` §1-1 정합)

---

## §R1. R1 3 병렬 dispatch (R0 머지 후)

R0 architect 머지 → 헌법 §2 정리 4단계 완료 → orchestrator R1 3 sub-agent 동시 dispatch.

### §R1-1. market-data — T-MARKET Phase 1 (KIS adapter 5 + cron Job 2)

#### 영역 LOCK
- api 모듈 `api/src/main/kotlin/kr/spdaewoon/magicjar/api/infrastructure/kis/index/` (신규 디렉토리)
- batch 모듈 `batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/job/market/` (신규 디렉토리)
- Flyway **V40·V41 LOCK**
- 영역 침범 0 — 기존 KIS adapter / batch Job / domain port 0 touch

#### 산출 파일 매트릭스

| # | 파일 | 책임 | 예상 LOC |
|---|---|---|---|
| 1 | `api/.../kis/index/KisDomesticIndexPriceAdapter.kt` | tr_id FHPUP02100000 어댑터. inline KDoc raw URL 의무 | ~80 |
| 2 | `api/.../kis/index/KisDomesticIndexDailyPriceAdapter.kt` | tr_id FHPUP02120000 어댑터. inline KDoc raw URL 의무 | ~90 |
| 3 | `api/.../kis/index/KisDomesticSectorCategoryAdapter.kt` | tr_id FHPUP02140000 어댑터. pagination tr_cont=M/F 재귀 처리 | ~120 |
| 4 | `api/.../kis/index/KisOverseasIndexPriceAdapter.kt` | tr_id HHDFS00000300 어댑터. .IXIC/.SPX symbol query | ~80 |
| 5 | `api/.../kis/index/KisOverseasIndexDailyPriceAdapter.kt` | tr_id HHDFS76240000 어댑터. pagination tr_cont 재귀 | ~90 |
| 6 | `domain/.../market/index/MarketIndexFetcherPort.kt` | domain port (5 adapter 공통 interface) | ~30 |
| 7 | `batch/.../job/market/MarketIndexSyncJob.kt` | 1분 cron · 장중 한정 · MarketSession FSM 게이트 · 26 req/min | ~150 |
| 8 | `batch/.../job/market/MarketIndexDailyCloseJob.kt` | 16:30 KST + 06:00 KST 2회 일봉 backfill | ~120 |
| 9 | `api/src/main/resources/db/migration/V40__market_index_snapshot.sql` | ADR 0100 §2-7 DDL 정합 | ~30 |
| 10 | `api/src/main/resources/db/migration/V41__sector_index_snapshot.sql` | ADR 0100 §2-7 DDL 정합 | ~25 |
| 11 | `domain/.../market/ratelimit/KisCallPriority.kt` | INGESTION_LOW enum value 추가 (ADR 0037 amend 정합) | +~5 |
| 12 | `_workspace/specs/2026-05-24-r1-market-data-kis-raw-verification.md` | KIS-first 5단계 §3 검증 보고서 (raw response sample 첨부 의무 — `.IXIC`·`.SPX` symbol query 실측 evidence) | ~150 |
| 13 | 테스트 fixture 4건 (어댑터 4 + Job 2 부분) | mock response 기반 unit test | ~250 |

**예상 합계**: ~1220 LOC (코드 ~820 + 테스트 ~250 + spec ~150)

#### CHANGELOG slot 책임
- `<!-- pr-N-slot-market-data -->` row in-place edit (TBD → 실제 사유)

#### 검증 명령 의무 (sub-agent self-check)
1. `./gradlew :api:compileKotlin :batch:compileKotlin GREEN`
2. `./gradlew :api:test --tests "*KisDomesticIndex*" --tests "*KisOverseasIndex*" --tests "*MarketIndex*" GREEN`
3. `./gradlew ktlintCheck GREEN`
4. 한 어댑터 실측 — `curl` 또는 통합 테스트로 `.IXIC` symbol query 응답 sample raw response 첨부 (KIS-first 5단계 §3 의무)
5. KisCallPriority enum 4 value (USER_PATH·INGESTION·BACKFILL·INGESTION_LOW) 모두 grep evidence

#### Codex 4 lens 체크리스트
- 세심함: 각 어댑터 inline KDoc raw URL + tr_id 명시 · null guard · error path · pagination 안전 limit
- 영향도: ADR 0037 INGESTION_LOW 활용 + 26 req/min 안전 검증 + MarketSession 게이트로 휴장일 0 req
- 연결: KisRateLimiterPort.acquire(INGESTION_LOW) + 기존 KIS auth path reuse + WebClient 기존 패턴 1:1
- 확장성: 향후 dailyprice MODP=0/1 옵션 · pagination 깊이 env 조정 가능 (`MAGICJAR_KIS_OVERSEAS_DAILY_MAX_PAGES`)

#### 관련 ADR cross-ref
- ADR 0100 (T-MARKET 본문) · ADR 0037 (KisCallPriority amend) · ADR 0013 (MarketSession FSM) · ADR 0017 (Instrument Master 패턴) · ADR 0057 (KIS-only)

---

### §R1-2. backend-core — T-MARKET Phase 2 (market-trend-engine 모듈 + TrendPreference + REST 6 endpoint)

#### 영역 LOCK
- **신규 모듈 `market-trend-engine/`** (root 직속 — indicator-impl 패턴 1:1)
- domain 모듈 `domain/.../trend/` (신규 디렉토리)
- api 모듈 `api/.../controller/market/` + `api/.../controller/user/` (신규)
- Flyway **V44 LOCK**
- settings.gradle.kts 갱신 (market-trend-engine include)
- 영역 침범 0 — 기존 모듈/controller 0 touch

#### 산출 파일 매트릭스

| # | 파일 | 책임 | 예상 LOC |
|---|---|---|---|
| 1 | `settings.gradle.kts` | `include("market-trend-engine")` add | +~1 |
| 2 | `market-trend-engine/build.gradle.kts` | indicator-impl 패턴 1:1 (Kotlin + Spring Context + domain 의존) | ~20 |
| 3 | `domain/.../trend/TrendPreference.kt` | data class + require 5건 + TrendPreferenceDefaults (ADR 0101 §2-1) | ~70 |
| 4 | `domain/.../trend/TrendPreferenceRepository.kt` | domain port | ~15 |
| 5 | `domain/.../trend/IndexTrend.kt` | DTO — code · ma Map · crossSignal · trendLabel · periodsUsed · isUserCustom · dataInsufficient | ~30 |
| 6 | `domain/.../trend/SectorRanking.kt` | DTO — todayTop · longTop · periodsUsed · rsPeriod | ~25 |
| 7 | `domain/.../trend/CrossSignal.kt` + `TrendLabel.kt` | enum 3·3 | ~10 |
| 8 | `market-trend-engine/.../calculator/TrendCalculator.kt` | interface (pure math) | ~15 |
| 9 | `market-trend-engine/.../calculator/SmaTrendCalculator.kt` | SMA 1차 default 구현 | ~80 |
| 10 | `market-trend-engine/.../calculator/CrossSignalCalculator.kt` | GOLDEN·DEAD·NEUTRAL 표준 정의 정합 | ~60 |
| 11 | `market-trend-engine/.../calculator/RsRatingCalculator.kt` | Mansfield RS · 섹터 vs KOSPI | ~70 |
| 12 | `market-trend-engine/.../service/TrendPreferenceService.kt` | user pref 조회·검증·적용 | ~80 |
| 13 | `market-trend-engine/.../service/IndexTrendComposer.kt` | DTO 조립 | ~70 |
| 14 | `market-trend-engine/.../service/SectorRankingComposer.kt` | DTO 조립 + ranking 가중치 0.7+0.3 | ~90 |
| 15 | `api/.../infrastructure/persistence/TrendPreferenceJpaAdapter.kt` | JPA adapter + JPA entity | ~120 |
| 16 | `api/src/main/resources/db/migration/V44__user_preference.sql` | ADR 0101 §2-2 DDL 정합 + 시드 SQL idempotent | ~30 |
| 17 | `api/.../controller/market/MarketIndexController.kt` | GET /api/market/index/snapshot + /index/{code}/trend | ~120 |
| 18 | `api/.../controller/market/MarketSectorController.kt` | GET /api/market/sectors/snapshot + /sectors/ranking | ~100 |
| 19 | `api/.../controller/user/UserPreferenceController.kt` | GET·PUT /api/users/me/preferences/trend | ~100 |
| 20 | `api/.../dto/market/*` (5 DTO) | Swagger 4단 어노테이션 + 한국어 description | ~150 |
| 21 | `api/.../dto/user/TrendPreferenceUpdateRequest.kt` | Bean Validation + 한국어 message | ~50 |
| 22 | `api/.../stomp/MarketIndexStompPublisher.kt` | 3 topic publish (market.index.{code} · market.sector.rank · market.user.{userId}.pref) | ~60 |
| 23 | `consumer/.../listener/MarketIndexUpdatedListener.kt` | Kafka consumer + STOMP fanout | ~50 |
| 24 | 테스트 fixture | SmaTrendCalculatorTest · CrossSignalCalculatorTest · RsRatingCalculatorTest · TrendPreferenceTest (require 5건 edge case) · IndexTrendComposerTest · SectorRankingComposerTest · MarketIndexControllerTest · UserPreferenceControllerTest | ~600 |

**예상 합계**: ~2050 LOC (코드 ~1450 + 테스트 ~600)

#### CHANGELOG slot 책임
- `<!-- pr-N-slot-backend-core-trend -->` row in-place edit

#### 검증 명령 의무
1. `./gradlew :market-trend-engine:test GREEN`
2. `./gradlew :domain:test GREEN`
3. `./gradlew :api:test --tests "*TrendPreference*" --tests "*MarketIndex*" --tests "*MarketSector*" --tests "*UserPreference*" GREEN`
4. `./gradlew :api:bootRun` 또는 통합 부팅 smoke — Flyway V44 적용 + endpoint 6 응답 200 + Swagger UI 한국어 description 렌더링 evidence
5. TrendPreference require 5건 edge case 테스트 모두 GREEN (size 0/6 · 0/253 · sorted ASC 위반 · crossPair short>=long · crossPair not in maPeriods)

#### Codex 4 lens 체크리스트
- 세심함: TrendPreference require 5건 모두 한국어 에러 메시지 · Bean Validation @Min·@Max·@Size · `dataInsufficient` flag (`candles.size < longMa` 시 frontend 데이터 부족 한국어) · null safe (crossPair? Pair)
- 영향도: 신규 모듈 1개 + 신규 표 1건 + DashboardPage·SettingsModal 영향 0 (frontend는 R2)
- 연결: user_account FK + TrendPreferenceRepository port + indicator-impl 패턴 1:1 + WebSocketConfig reuse + OnboardingGuardFilter pattern
- 확장성: TrendCalculator interface 1차 SMA 구현 (post-MVP EMA·WMA·DEMA add-only) · TrendPreference `extra JSONB` 확장 슬롯 (ADR 0101 §2-5 12+ 후보)

#### 관련 ADR cross-ref
- ADR 0100 (T-MARKET) · ADR 0101 (TrendPreference) · ADR 0004 (모듈 경계 amend) · ADR 0083 (indicator-impl 패턴 1:1) · ADR 0010 (Multi-tenant — user_id FK)

#### Spring Data JPA Repository default fun 함정 회피 (ADR 0051 + 헌법 §6)
- `TrendPreferenceJpaRepository` interface 안에 body 가진 fun 0건 LOCK — UPSERT 헬퍼는 caller class (`TrendPreferenceJpaAdapter`)의 private fun으로 이동

---

### §R1-3. ai-engineer — T-AI Phase 1 (3 adapter + AiCommentProvider + 5 prompt template)

#### 영역 LOCK
- api 모듈 `api/.../ai/` (신규 패키지)
- consumer 모듈 `consumer/.../listener/AiCommentDispatcherListener.kt` (신규)
- Flyway **V42·V43 LOCK**
- 영역 침범 0 — 기존 controller / consumer listener 0 touch

#### 산출 파일 매트릭스

| # | 파일 | 책임 | 예상 LOC |
|---|---|---|---|
| 1 | `domain/.../ai/AiCommentProvider.kt` | interface (이미 ADR 0026 §2-3에 존재 — 본 PR에서 V20→V42 정합으로 코드 신설) | ~25 |
| 2 | `domain/.../ai/AiCommentRequest.kt` + `AiCommentResponse.kt` + `AiCommentType.kt` | data class + enum (MARKET 신규 포함) | ~50 |
| 3 | `api/.../ai/adapter/AnthropicCommentAdapter.kt` | Anthropic SDK 호출 + Resilience4j circuit 30s + 3 retry exponential | ~150 |
| 4 | `api/.../ai/adapter/GeminiCommentAdapter.kt` | Google Gemini API 호출 (free tier — dev 1차 무료) | ~120 |
| 5 | `api/.../ai/adapter/OpenAiCommentAdapter.kt` | OpenAI GPT API 호출 (옵션) | ~120 |
| 6 | `api/.../ai/AiProviderFactory.kt` | env `MAGICJAR_AI_PROVIDER` 기반 adapter 선택 | ~40 |
| 7 | `api/.../ai/AiPromptCache.kt` | Redis `ai:prompt:cache:{templateHash}` 5분 TTL | ~50 |
| 8 | `api/.../ai/AiTokenLimitGuard.kt` | 1일 1사용자 토큰 한도 (default 1000) | ~60 |
| 9 | `api/.../ai/AiKillSwitchGuard.kt` | `MAGICJAR_AI_KILLSWITCH=true` 시 graceful refuse (ADR 0094 §4-4-B amend) | ~40 |
| 10 | `api/.../controller/ai/AiExplainController.kt` | POST /api/ai/explain — Swagger 4단 한국어 | ~100 |
| 11 | `api/.../ai/AiCostTracker.kt` | `ai_comment_log.cost_usd` 월 누적 + ADR 0095 $10 임계 alert | ~70 |
| 12 | `consumer/.../listener/AiCommentDispatcherListener.kt` | Kafka ai.comment.requested.v1 consume → adapter 호출 → ai.comment.responded.v1 publish | ~120 |
| 13 | `api/src/main/resources/db/migration/V42__ai_comment_log.sql` | ADR 0026 §2-5 amend 정합 DDL | ~30 |
| 14 | `api/src/main/resources/db/migration/V43__prompt_template.sql` + seed 5건 | ADR 0026 §2-7 amend 정합 — symbol_v1·chart_v1·strategy_v1·signal_v1·market_v1 | ~80 |
| 15 | `api/.../ai/PromptTemplateResolver.kt` | type+version 조회 + 변수 치환 (`{{code}}`·`{{maPeriods}}`·`{{rsPeriod}}` 등) | ~60 |
| 16 | `api/.../stomp/AiExplainStompPublisher.kt` | /topic/ai.explain.{requestId} fanout | ~40 |
| 17 | 테스트 fixture | 3 adapter mock + cache hit/miss + token limit + KillSwitch + dispatcher consume·publish + prompt 변수 치환 | ~500 |

**예상 합계**: ~1655 LOC (코드 ~1155 + 테스트 ~500)

#### CHANGELOG slot 책임
- `<!-- pr-N-slot-ai-engineer -->` row in-place edit

#### 검증 명령 의무
1. `./gradlew :api:test --tests "*Ai*"` GREEN
2. `./gradlew :consumer:test --tests "*AiComment*"` GREEN
3. 통합 — `MAGICJAR_AI_ENABLED=false` (default) 시 POST `/api/ai/explain` 200 graceful refuse 한국어 "AI 비활성 상태" 응답
4. `MAGICJAR_AI_ENABLED=true` + key 없음 → mock mode 응답 evidence
5. AI 호출 grep — W1·W2·W3 코드 영역 (W4 외 모든 영역) LLM provider import 0건 grep evidence (ADR 0026 §3)

#### Codex 4 lens 체크리스트
- 세심함: Resilience4j circuit 30s + 3 retry exponential 1s/2s/4s · null safe · graceful refuse 한국어 message · 변수 치환 missing key error path
- 영향도: 본체 자본 path 영향 0 grep 검증 + `MAGICJAR_AI_ENABLED=false` default + 비용 KillSwitch path
- 연결: Redis client 기존 reuse · Kafka topic 패턴 1:1 · OnboardingGuardFilter pattern · ADR 0044 notification_outbox reuse 옵션 (alert path)
- 확장성: AiCommentProvider interface로 4번째 provider 추가 시 본체 변경 0 · PromptTemplate version 컬럼으로 A/B 비교 path · `ai_comment_log` 추가 컬럼 add-only

#### 관련 ADR cross-ref
- ADR 0026 (AI Option amend) · ADR 0094 §4-4-B (AI 비용 통제 + KillSwitch) · ADR 0095 §3-2 amend (S-AI-1 $10/월 임계)

---

## §R2. R2 2 병렬 dispatch (R1 3 PR 머지 후)

R1 3 PR 모두 머지 + 헌법 §2 정리 4단계 완료 → orchestrator R2 2 sub-agent 동시 dispatch.

### §R2-1. frontend — T-MARKET·T-AI UI 통합

#### 영역 LOCK
- frontend `frontend/src/components/market/` (신규)
- frontend `frontend/src/pages/MarketTrendPage.tsx` (신규)
- frontend `frontend/src/components/dashboard/MarketIndexStrip.tsx` (신규)
- frontend `frontend/src/components/settings/TrendPreferenceSection.tsx` (신규)
- frontend `frontend/src/components/ai/AiCard.tsx` + 5 진입점 통합
- frontend `frontend/src/hooks/useTrendPreference.ts` + `useMarketTrend.ts` + `useAiExplain.ts` (3 신규)
- 영역 침범 0 — 기존 page/component 0 touch (add-only)

#### 산출 파일 매트릭스

| # | 파일 | 책임 | 예상 LOC |
|---|---|---|---|
| 1 | `pages/MarketTrendPage.tsx` | 좌측 차트 + 중앙 추세 라벨 + 우측 섹터 ranking + 우상단 프리셋 토글 | ~250 |
| 2 | `components/dashboard/MarketIndexStrip.tsx` | 4 카드 (KOSPI · KOSDAQ · NASDAQ · S&P) + 추세 라벨 배지 + MA 자막 | ~150 |
| 3 | `components/market/IndexChartPanel.tsx` | LWC v4 일봉 차트 + MA overlay (user pref periods 색상 분리) | ~180 |
| 4 | `components/market/SectorRankingTable.tsx` | todayTop 5 / longTop 5 탭 + 섹터 클릭 종목 펼침 | ~200 |
| 5 | `components/settings/TrendPreferenceSection.tsx` | SettingsModal "추세 설정" section — MA periods 입력 4 row + rsPeriod readonly + crossPair toggle + 미리보기 + PUT submit | ~220 |
| 6 | `components/ai/AiCard.tsx` | 5 진입점 공통 컴포넌트 — markdown render + 출처 + token 비용 | ~150 |
| 7 | `components/ai/AiExplainButton.tsx` | 5 진입점 공통 trigger button (Symbol/Chart/Strategy/Signal/Market) | ~80 |
| 8 | `hooks/useTrendPreference.ts` | TanStack Query GET·PUT mutation + STOMP subscribe /topic/market.user.{userId}.pref + 다중 탭 sync | ~100 |
| 9 | `hooks/useMarketTrend.ts` | TanStack Query polling 30s + STOMP subscribe (race-safe merge) | ~80 |
| 10 | `hooks/useAiExplain.ts` | TanStack Query mutation POST /api/ai/explain + STOMP subscribe /topic/ai.explain.{requestId} | ~100 |
| 11 | `api/marketApi.ts` + `userPreferenceApi.ts` + `aiApi.ts` | api client 3건 | ~150 |
| 12 | `types/market.ts` + `types/preference.ts` + `types/ai.ts` | TypeScript type 정합 (backend DTO 1:1) | ~120 |
| 13 | DashboardPage.tsx amend | MarketIndexStrip 상단 add-only mount (기존 위젯 0 touch) | +~10 |
| 14 | SettingsModal.tsx amend | TrendPreferenceSection mount (기존 section 0 touch) | +~10 |
| 15 | SymbolDetailPage.tsx + StrategyPage.tsx + DashboardPage.tsx + MarketTrendPage.tsx amend | AiExplainButton mount (5 진입점) | +~30 |
| 16 | `Routes` 갱신 | /market/trend route 추가 | +~5 |
| 17 | `AppHeader` 갱신 | "시장 추세" 메뉴 1개 add | +~10 |

**예상 합계**: ~1845 LOC (신규 ~1780 + amend 기존 ~65)

#### CHANGELOG slot 책임
- `<!-- pr-N-slot-frontend -->` row in-place edit

#### 검증 명령 의무
1. `cd frontend && pnpm typecheck` GREEN
2. `cd frontend && pnpm build` GREEN
3. `cd frontend && pnpm lint` GREEN
4. 통합 부팅 smoke — 4 모듈 docker compose up · `http://localhost:5173/market/trend` 진입 200 evidence
5. SettingsModal "추세 설정" PUT submit → 다중 탭 STOMP sync evidence
6. mock 합성 0 검증 — backend 0건 시 frontend "데이터 없음" 한국어 표시 grep evidence (사용자 메모리 feedback_no_mock_data 정합)

#### Codex 4 lens 체크리스트
- 세심함: TanStack Query stale time · refetchInterval · race-safe merge (polling + STOMP 동시) · empty state 한국어 · loading state · error boundary
- 영향도: 기존 화면 0 touch (add-only) · feature flag `VITE_FEATURE_MARKET_TREND` 환경별 toggle 가능 (rollback path)
- 연결: 기존 LWC v4 차트 컴포넌트 reuse · 기존 STOMP client wrapper reuse · 기존 TanStack Query QueryClient reuse · 기존 toast/알람센터 reuse
- 확장성: AiCard 컴포넌트 공통화 → 6번째 진입점 추가 시 hook + button mount만 · TrendPreferenceSection extra JSONB UI 노출 path (ADR 0101 §2-5)

#### qa Visual 자동 dispatch trigger (헌법 §9-2)
frontend 핵심 화면 변경 (DashboardPage · SettingsModal · /market/trend page) → R3 qa Visual 회귀 의무 dispatch

---

### §R2-2. backend-core — T-MCP Phase 1 (McpController + 6 tool + JSON Schema + Flyway V45)

#### 영역 LOCK
- api 모듈 `api/.../mcp/` (신규 패키지 — ADR 0027 §2-1 정합)
- Flyway **V45 LOCK**
- 영역 침범 0 — 기존 controller / service 0 touch

#### 산출 파일 매트릭스

| # | 파일 | 책임 | 예상 LOC |
|---|---|---|---|
| 1 | `api/.../mcp/McpController.kt` | MCP protocol HTTP/SSE endpoint · JSON Schema discover | ~150 |
| 2 | `api/.../mcp/McpToolRegistry.kt` | tool 6종 등록·라우팅 | ~80 |
| 3 | `api/.../mcp/tool/GetCandlesTool.kt` | OHLCV array 응답 (adjusted default) — 기존 CandleService reuse | ~80 |
| 4 | `api/.../mcp/tool/RunScreenerTool.kt` | universe + dslYaml → code list + 랭킹 — 기존 ScreenerService reuse | ~80 |
| 5 | `api/.../mcp/tool/GetJournalTool.kt` | journal entries + summary — 기존 JournalService reuse | ~70 |
| 6 | `api/.../mcp/tool/ExplainIndicatorTool.kt` | 정적 catalog 응답 (LLM 호출 0) — `mcp_indicator_catalog.json` resource seed | ~70 |
| 7 | `api/.../mcp/tool/BacktestStrategyTool.kt` | 성과 카드 + trades — 기존 BacktestService reuse | ~100 |
| 8 | `api/.../mcp/tool/GetMarketTrendTool.kt` | scope (index/sector/both) + periods + rsPeriod → T-MARKET service reuse | ~120 |
| 9 | `api/.../mcp/auth/ApiKeyAuthFilter.kt` | env `MAGICJAR_MCP_API_KEY` 1차 인증 (localhost 한정) | ~80 |
| 10 | `api/.../mcp/McpAuditLogger.kt` | mcp_audit V45 insert (호출 직후) | ~60 |
| 11 | `api/.../mcp/McpRateLimitGuard.kt` | 분당 호출 100 초과 시 SystemEvent emit (ADR 0095 amend S-MCP-1) | ~70 |
| 12 | `api/src/main/resources/db/migration/V45__mcp_audit.sql` | ADR 0027 §2-5 amend 정합 | ~25 |
| 13 | `api/src/main/resources/mcp/indicator_catalog.json` | explain_indicator 정적 catalog seed (RSI·MACD·MA 등 20+ 지표) | ~300 |
| 14 | `api/.../mcp/dto/*` | JSON Schema DTO 6 tool 입력 + 응답 | ~250 |
| 15 | 테스트 fixture | McpController 통합 + 6 tool 단위 + ApiKeyAuthFilter + McpAuditLogger + McpRateLimitGuard | ~400 |

**예상 합계**: ~1935 LOC (코드 ~1535 + 테스트 ~400)

#### CHANGELOG slot 책임
- `<!-- pr-N-slot-backend-core-mcp -->` row in-place edit

#### 검증 명령 의무
1. `./gradlew :api:test --tests "*Mcp*"` GREEN
2. 통합 부팅 + `curl -H "X-Api-Key: ${KEY}" http://localhost:8080/mcp/tools` → 6 tool list 응답 evidence
3. `get_market_trend` periods 생략 시 user pref echo evidence (TrendPreferenceService reuse)
4. `explain_indicator` LLM 호출 0 grep evidence (정적 catalog only)
5. ApiKeyAuthFilter localhost 한정 검증 — `curl -H "X-Forwarded-For: 1.2.3.4"` 시 403 evidence

#### Codex 4 lens 체크리스트
- 세심함: JSON Schema 각 tool 입력/응답 정밀 정합 · params JSONB null safe · response_size_bytes 계측 · error_message graceful · API key timing attack 방어 (constant-time compare)
- 영향도: 모든 tool read-only LOCK + write tool 0건 grep 검증 · 기존 service reuse + 신규 service 0건
- 연결: TrendPreferenceService reuse (get_market_trend) · 기존 CandleService·ScreenerService·JournalService·BacktestService reuse · OnboardingGuardFilter skip (인증 분리)
- 확장성: McpToolRegistry로 7번째 tool 추가 시 본체 변경 0 · OAuth2 인증 path (post-MVP — ADR 0027 §2-4) · multi-tenant API key path

#### 관련 ADR cross-ref
- ADR 0027 (MCP amend) · ADR 0100 (T-MARKET — get_market_trend input source) · ADR 0101 (TrendPreference — periods echo) · ADR 0095 amend (S-MCP-1)

---

## §R3. R3 qa 직렬 dispatch (R2 2 PR 머지 후)

R2 2 PR 모두 머지 + 헌법 §2 정리 4단계 완료 → orchestrator R3 qa 단독 dispatch.

### §R3-1. qa — E2E Visual 회귀 7 케이스

#### 영역 LOCK
- frontend Playwright `frontend/tests/e2e/w4/` (신규 디렉토리)
- 영역 침범 0 — 기존 e2e 테스트 0 touch

#### 산출 파일 매트릭스

| # | 파일 | 책임 | 예상 LOC |
|---|---|---|---|
| 1 | `tests/e2e/w4/dashboard-market-index-strip.spec.ts` | 케이스 1 — DashboardPage MarketIndexStrip 4 카드 노출 + 추세 라벨 (default pref + 사용자 pref [10,30,90] 양쪽) | ~100 |
| 2 | `tests/e2e/w4/market-trend-page.spec.ts` | 케이스 2 — /market/trend 진입 + LWC v4 MA overlay user pref 색상 분리 + 섹터 ranking todayTop·longTop 탭 전환 | ~150 |
| 3 | `tests/e2e/w4/settings-trend-preference.spec.ts` | 케이스 3 — SettingsModal "추세 설정" → MA periods [3,7,21] 저장 → 다른 탭 STOMP push 자동 sync + 차트 즉시 재계산 | ~120 |
| 4 | `tests/e2e/w4/ai-5-entries.spec.ts` | 케이스 4 — 5 AI 진입점 click → mock mode "AI 응답 없음" + 활성 모드 응답 evidence | ~150 |
| 5 | `tests/e2e/w4/mcp-curl-6-tools.spec.ts` | 케이스 5 — MCP curl 6 tool 200 응답 + get_market_trend periods 생략 시 user pref echo | ~100 |
| 6 | `tests/e2e/w4/preference-validation.spec.ts` | 케이스 6 — 잘못된 periods 입력 (0개·6개·중복·253) → Bean Validation 400 한국어 응답 | ~80 |
| 7 | `tests/e2e/w4/kis-rate-limit-realtime.spec.ts` | 케이스 7 — KIS Rate Limit 실측 — MarketIndexSyncJob 1분 cron 60회 운영 후 KisRateLimiterPort 통계 26 req/min ± 5% · EGW00201 0건 | ~120 |
| 8 | `tests/e2e/w4/screenshots/` | 케이스 1·2·3 baseline screenshot 풀세트 | (binary) |
| 9 | `_workspace/reports/2026-05-??-w4-qa-evidence.md` | docker logs 4 모듈 (api·batch·consumer·frontend) grep "ERROR\|FATAL" 0건 검증 + 7 케이스 결과 요약 | ~250 |

**예상 합계**: ~1070 LOC (테스트 ~820 + 보고서 ~250)

#### CHANGELOG slot 책임
- `<!-- pr-N-slot-qa -->` row in-place edit

#### 검증 명령 의무
1. `cd frontend && pnpm test:e2e tests/e2e/w4/` 7 케이스 모두 GREEN
2. Visual 회귀 baseline screenshot 정합 (diff 임계 < 1%)
3. docker logs 4 모듈 0건 검증 evidence (cut 직전 의무 — 사용자 메모리 feedback_docker_log_verify_before_cut)
4. KIS rate limit 실측 evidence — 1분 cron 60회 운영 후 26 req/min ± 5% (안전 4.3% 점유)
5. AI mock mode evidence + 활성 mode evidence (key 있을 시)
6. MCP 6 tool 응답 evidence (curl 직접 확인)

#### Codex 4 lens 체크리스트
- 세심함: 각 케이스 multi-step 시나리오 + 한국어 assertion 메시지 + screenshot diff threshold · timing-safe wait + stable selector (data-testid)
- 영향도: Playwright headless mode · CI 통합 path (post-MVP)
- 연결: 기존 e2e 셋업 reuse · backend mock mode env reuse
- 확장성: 새 시나리오 추가 시 spec 파일 1건 add-only · screenshot baseline 갱신 절차 명시

#### 관련 ADR cross-ref
- ADR 0094 §9-2 (qa Visual 자동 dispatch 의무) · ADR 0096 §6-2 (DR drill 자동 재현)

---

## §R4. 머지 후 정리 의무 (헌법 §2 — 모든 R 단계 공통)

각 R 단계 머지 직후 orchestrator가 4단계 실행:

1. `cd <repo-root> && git pull --ff-only origin main`
2. `node scripts/task.mjs archive T-<id>`
3. `git worktree unlock <path> && git -C <path> restore _workspace/tasks/ && git worktree remove <path>`
   - `rmdir <path>/.gradle 2>/dev/null` + `.idea` + `node_modules` + `<path>` 빈 wrapper 정리
4. `git branch -d agent/<agent>/<task>` + `git branch -D worktree-agent-<id>`

---

## §R5. v3.9.0 release 게이트 (brainstorming §9-4 정합)

R3 머지 + 외부 검증 통과 후:

1. R3 qa 7 케이스 GREEN
2. `./gradlew check` GREEN + `scripts/smoke.sh` 통과
3. docker logs 4 모듈 (api·batch·consumer·frontend) 0건 (R3 qa evidence)
4. **Codex 외부 review 통과** (사용자 명시 게이트)
5. `/baseline init v3.9.0` — 4 sub-agent 병렬 → 7 md + 8 html
6. baseline 8 PR 머지 완료 → `git tag v3.9.0` + `release/v3.9.0` brunch push (ADR 0031 immutable)
7. `scripts/release.sh v3.9.0` — release notes + `gh release create` + README badge sync

---

## §R6. References

- R0 design: `_workspace/specs/2026-05-24-w4-r0-design.md`
- brainstorming 통합본: `_workspace/specs/2026-05-24-w4-t-market-t-ai-t-mcp-design.md`
- 충돌 매트릭스: `_workspace/specs/2026-05-24-w4-conflict-matrix.md`
- Codex 검증 checklist: `_workspace/specs/2026-05-24-w4-codex-validation-checklist.md`
- ADR 0100 · 0101 신설 + ADR 0026·0027·0004·0037·0094·0095 amend
- 헌법 `_workspace/arch/agent-charter.md` §1·§2·§3·§4·§5·§6·§9
- KIS-first 정책 `docs/architecture/policies/kis-first-raw-fetch.md`
