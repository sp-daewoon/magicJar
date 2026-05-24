# W4 통합 Design — T-MARKET + T-AI + T-MCP (v3.9.0 후보)

> **상태**: brainstorming 통합본 (사용자 승인 — 2026-05-24)
> **권위**: 본 문서는 magicjar-orchestrator → architect dispatch 의 input. architect spec/plan PR 발행 시 본 문서 §1~§9 정합 의무.
> **소유**: magicjar-orchestrator (사용자 위임)
> **작성일**: 2026-05-24
> **release 타깃**: v3.9.0 (W4 완전 닫기 + Codex 검증 만점 후 cut)

---

## §0. 메타 — 사용자 결정 history + 권위 메모리

### 0-1. 사용자 결정 4건 (brainstorming 세션 2026-05-24)
| # | 의제 | 결정 |
|---|---|---|
| 1 | 지수·섹터 추세 모듈 구조 | **신규 T-MARKET 트랙 + T-AI 합류** (data layer · AI layer 분리, AI 끊겨도 본체 0 영향) |
| 2 | /goal 한번 묶음 범위 | **T-MARKET + T-AI + T-MCP** (W4 완전 닫기 · v3.9.0 cut) |
| 3 | 지수·섹터 데이터 범위 | **국내 KOSPI/KOSDAQ + 해외 NASDAQ/S&P 4 지수 + KRX 21 업종** (KIS Overseas API 점직 진출) |
| 4 | 잔재 묶음 | **잔재 0 가정** (옛 backlog § 는 Codex 외부 처리 가정 + 새 사이클 dispatch 0 · 결함 시 § 연결만 확인) |

### 0-2. 사용자 추가 의제 1건 (브레인스토밍 §2-2 갱신 시점)
- MA 기간 (5d·20d·60d) 을 시스템 hardcoded 가 아니라 **사용자 설정 가능 도메인** 으로 격상. 1~5개·1~252일·sorted ASC validation. 향후 확장 슬롯 (RS period · cross pair · MA type · indicator 추가) 까지 forward-compat.

### 0-3. 적용 메모리 (본 사이클 의무 lens)
- `feedback-codex-validation-gate.md` — 산출물은 "Codex 가 봐도 손댈 게 없는 수준" + 영향도·연결·확장성 3 lens 의무
- `feedback-backlog-codex-handled.md` — 옛 backlog § 의제 dispatch 금지
- `feedback-kis-first-policy.md` — KIS endpoint 5 raw fetch + inline KDoc 의무
- `feedback-v3-skepticism-no-regression.md` — fitness gap "자동매매에 필요한가" lens
- `feedback-release-policy-patch-first.md` — v3.9.0 = W4 마감 minor LOCK · 그 외 patch
- `feedback-no-mock-data.md` — mock 합성 0 · 데이터 없음 한국어 명시
- `project-system-vision.md` — AI 매매 결정권 0 · 사용자 트리거 한정 (ADR 0026 §1 정합)
- `feedback-docker-log-verify-before-cut.md` — v3.9.0 cut 직전 4 모듈 docker logs 0건 검증
- `feedback-honorific.md` — 모든 사용자 응답 존댓말

---

## §1. 통합 아키텍처 + 모듈 경계

### 1-1. 한 화면 다이어그램
```
W4 = 3 트랙 add-only — 본체 자본 흐름 영향 0 (ADR 0026 §2-6 정합)

┌─────────────────────────────────────────────────────────────────────────┐
│  T-MARKET (신규 모듈 후보 market-trend-engine — indicator-impl 패턴 1:1)│
│   ├─ market_index_snapshot · sector_index_snapshot 영속 (Flyway V47·V48) │
│   ├─ user_preference 영속 (Flyway V51 · extra JSONB 확장 슬롯)          │
│   ├─ KIS endpoint 5 (국내 3 + 해외 2 — raw fetch 의무)                  │
│   ├─ TrendCalculator (1d·5d·N MA·RS pure math — user periods 입력)      │
│   ├─ TrendPreference 1급 도메인 (user-settable · default [5,20,60])     │
│   ├─ Kafka market.index.updated.v1 + market.sector.updated.v1           │
│   └─ Redis cache market:index:{code} TTL 30s · sector:rank TTL 5m       │
│              ↓ input                                                    │
│  T-AI (api 모듈 ai/ 패키지 + consumer dispatcher — ADR 0026)            │
│   ├─ 진입점 5종: SYMBOL·CHART·STRATEGY·SIGNAL·MARKET (신규)             │
│   ├─ AnthropicCommentAdapter (1차 default) + Gemini·OpenAI 옵션         │
│   ├─ AiCommentProvider 추상화 + Redis ai:prompt:cache 5분 + 1일 한도   │
│   ├─ ai_comment_log V49 + prompt_template V50 (variable schema)         │
│   └─ STOMP /topic/ai.explain.{requestId} fanout                         │
│              ↓ expose                                                   │
│  T-MCP (api 모듈 mcp/ 패키지 — ADR 0027)                                │
│   ├─ McpController HTTP/SSE + JSON Schema 정합                          │
│   ├─ tool 6종: get_candles·run_screener·get_journal·explain_indicator   │
│   │              ·backtest_strategy·get_market_trend (T-MARKET 합류)    │
│   ├─ ApiKeyAuthFilter (localhost 1차) + mcp_audit V52                   │
│   └─ explain_indicator는 LLM 호출 0 (정적 catalog)                      │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1-2. 모듈 매트릭스 갱신 (ADR 0004 6 → 7)
| 모듈 | 책임 |
|---|---|
| domain | port·도메인 (Spring/JPA 0) — `TrendPreference`·`AiCommentRequest`·`McpToolInput` 추가 |
| api | REST/STOMP + 외부 어댑터 — T-AI ai/ 패키지 + T-MCP mcp/ 패키지 + TrendQueryController |
| batch | Spring Batch — MarketIndexSyncJob (1분 cron) + MarketIndexDailyCloseJob (16:00 KST) |
| consumer | Kafka 소비 + AiCommentDispatcher (5 진입점 처리) |
| persistence-shared | Outbox repository — T-AI 합류 시 ai_comment_outbox 가능 (post-MVP) |
| indicator-impl | 그대로 (영향 0) |
| strategy-engine | 그대로 (영향 0) |
| **market-trend-engine (신규)** | TrendCalculator pure math + TrendPreferenceService + sector ranking 로직 — Spring 의존 0, indicator-impl 패턴 1:1 |

→ **컨테이너 수 변동 0** (api/batch/consumer 3 + frontend 1 + 인프라 3 = 7 그대로). 모듈만 7 → 8 자연 추가.

---

## §2. T-MARKET 트랙 상세

### 2-1. 데이터 source (KIS-first raw fetch 의무)
| 출처 | endpoint | TR_ID | 용도 | 호출 빈도 |
|---|---|---|---|---|
| KIS 국내 업종 시세 | `/uapi/domestic-stock/v1/quotations/inquire-index-price` | `FHPUP02100000` | KOSPI(0001)·KOSDAQ(1001)·KOSPI200(2001)·KRX 21 업종 현재가 | 1분 cron (시장 OPEN 한정) |
| KIS 국내 업종 일봉 | `/uapi/domestic-stock/v1/quotations/inquire-index-daily-price` | `FHPUP02120000` | 추세 계산 input (최대 252일 ~ 1 거래년) | 일 1회 (16:00 KST) + 사용자 변경 시 lazy fetch |
| KIS 국내 업종 등락 | `/uapi/domestic-stock/v1/quotations/inquire-index-category-price` | `FHPUP02140000` | 오늘의 강세 sector ranking input | 1분 cron |
| KIS 해외 지수 현재가 | `/uapi/overseas-price/v1/quotations/price` | `HHDFS00000300` | NASDAQ(.IXIC)·S&P500(.SPX) | 1분 cron (해외 장 OPEN 한정 · ETKR/EUS 분리) |
| KIS 해외 지수 일봉 | `/uapi/overseas-price/v1/quotations/dailyprice` | `HHDFS76240000` | 해외 추세 계산 input | 일 1회 (해외 장 CLOSE 후) |

> **raw fetch 의무**: architect spec 단계에서 `mcp__kis-code-assistant__search_domestic_stock_api` + `search_overseas_stock_api` 호출 결과 sample response · 필드 매트릭스 · TR_ID 매핑을 spec 본문에 첨부. 자체 추론 0 (`feedback-kis-first-policy.md` 정합).

### 2-2. TrendPreference 1급 도메인 (사용자 설정)

#### 2-2-A. 도메인 정의
```kotlin
// domain/.../trend/
data class TrendPreference(
    val userId: Long,
    val maPeriods: List<Int>,        // default [5, 20, 60] · 1~5개 LOCK · 각 1~252 LOCK · sorted ASC
    val rsPeriod: Int = 60,          // RS rating 비교 기간 (1차 60 LOCK · post-MVP user-settable)
    val crossPair: Pair<Int, Int>? = null,  // null 시 maPeriods 최소·최대 auto
    val updatedAt: Instant,
) {
    init {
        require(maPeriods.size in 1..5)               { "MA periods 1~5개" }
        require(maPeriods.all { it in 1..252 })       { "각 기간 1~252일" }
        require(maPeriods == maPeriods.sorted().distinct()) { "중복 없이 오름차순" }
    }
    fun shortMa(): Int = crossPair?.first ?: maPeriods.first()
    fun longMa(): Int = crossPair?.second ?: maPeriods.last()
}

interface TrendPreferenceRepository {
    fun findByUserId(userId: Long): TrendPreference?
    fun upsert(pref: TrendPreference): TrendPreference
}

object TrendPreferenceDefaults {
    val MA_PERIODS = listOf(5, 20, 60)
    const val RS_PERIOD = 60
}
```

#### 2-2-B. TrendCalculator 인터페이스
```kotlin
// market-trend-engine/.../calculator/
interface TrendCalculator {
    fun computeIndexTrend(code: String, candles: List<Candle>, periods: List<Int>): IndexTrend
    fun computeSectorRanking(snapshots: List<SectorSnapshot>, periods: List<Int>, rsPeriod: Int): SectorRanking
}
```
- **Pure math** (Spring 의존 0)
- **periods 입력 의무** — service layer 에서 user pref 또는 query override 적용 후 호출
- **단위 테스트** — fixture 기반 expected output 비교 (5d MA · 20d MA · cross signal · trend label · sector RS rating)

#### 2-2-C. 결과 DTO (echo periods 의무)
```kotlin
data class IndexTrend(
    val code: String,
    val ma: Map<Int, BigDecimal>,           // {5: 2670.12, 20: 2685.34, 60: 2710.55}
    val crossSignal: CrossSignal,           // GOLDEN · DEAD · NEUTRAL
    val trendLabel: TrendLabel,             // UP · SIDEWAYS · DOWN
    val periodsUsed: List<Int>,             // 응답 echo — frontend default vs user 구분
    val isUserCustom: Boolean,              // false=default · true=사용자 설정
    val dataInsufficient: Boolean,          // true 시 candles.size < longMa — frontend "데이터 부족" 한국어
)
data class SectorRanking(
    val todayTop: List<SectorRankEntry>,    // 1d 등락률 + 거래대금 가중
    val longTop: List<SectorRankEntry>,     // RS rating(rsPeriod) + maPeriods 우상향 fit
    val periodsUsed: List<Int>,
    val rsPeriod: Int,
)
```

#### 2-2-D. 추세 라벨 알고리즘 (LOCK)
- **CrossSignal** (직전 봉 ↔ 현재 봉 cross 명세 — 표준 정의):
  - `GOLDEN` — `shortMa[t-1] ≤ longMa[t-1] AND shortMa[t] > longMa[t]` (상향 돌파)
  - `DEAD` — `shortMa[t-1] ≥ longMa[t-1] AND shortMa[t] < longMa[t]` (하향 돌파)
  - `NEUTRAL` — 그 외 (cross 0)
- **TrendLabel** (longMa 기준 · 직전 5 거래일 비교):
  - `UP` — 종가 > longMa[t] · `(longMa[t] − longMa[t-5]) / longMa[t-5] ≥ +0.005` (우상향 0.5% 이상)
  - `DOWN` — 종가 < longMa[t] · `(longMa[t] − longMa[t-5]) / longMa[t-5] ≤ -0.005` (우하향 -0.5% 이하)
  - `SIDEWAYS` — 그 외
- **SectorRanking todayTop 가중치** — 등락률 0.7 + 거래대금 (전일比) 0.3 (1차 fixed · post-MVP user-settable)
- **SectorRanking longTop RS rating** (Mansfield RS · 기준일 = rsPeriod 영업일 전):
  - `RS = (섹터종가[t] / 섹터종가[t-rsPeriod]) ÷ (KOSPI종가[t] / KOSPI종가[t-rsPeriod])`
  - `RS > 1.0` = KOSPI 대비 강세 / `RS < 1.0` = 약세
  - longTop 정렬: RS 내림차순 + maPeriods 우상향 fit (전 periods 모두 우상향이면 가산점 +0.1)
- **결측 처리**: candles.size < longMa 면 IndexTrend.crossSignal=NEUTRAL · trendLabel=SIDEWAYS · ma 부분 채움 + `dataInsufficient: true` 필드 echo (frontend "데이터 부족" 한국어 표시 — `feedback-no-mock-data.md` 정합 · mock 합성 0)

### 2-3. REST 표면 (preference 2 + trend 4 = 6 endpoint Swagger 4단)
| Method | endpoint | 용도 |
|---|---|---|
| GET | `/api/users/me/preferences/trend` | 현재 설정 조회 (없으면 default echo · isUserCustom=false) |
| PUT | `/api/users/me/preferences/trend` | MA periods · rsPeriod · crossPair 변경 (Bean Validation · `@Size(min=1, max=5)`·`@Min(1)`·`@Max(252)`) |
| GET | `/api/market/index/snapshot` | 4 지수 카드 (KOSPI·KOSDAQ·NASDAQ·S&P · user pref 적용 trend 라벨 포함) |
| GET | `/api/market/index/{code}/trend?periods=5,20,60` | 지수 추세 상세 (periods 생략 시 user pref) |
| GET | `/api/market/sectors/snapshot` | KRX 21 섹터 snapshot (이름 + 등락률 + 거래대금) |
| GET | `/api/market/sectors/ranking?periods=5,20,60&rsPeriod=60` | 섹터 ranking (todayTop 5 + longTop 5) |

> `?periods=...` 쿼리는 일회성 override (URL share · 테스트용). 영속 변경은 PUT 만.

### 2-4. STOMP fanout
- `/topic/market.index.{code}` — 4 지수 1분 cron 결과 push
- `/topic/market.sector.rank` — sector ranking 5분 캐시 갱신 push
- `/topic/market.user.{userId}.pref` — 사용자 pref 변경 시 즉시 push (다중 탭 sync)

### 2-5. cron + 영속

#### cron Job 2건 (batch 모듈)
- `MarketIndexSyncJob` — 1분 cron (국내 0001·1001·2001·21 업종 = 24 req + 해외 .IXIC·.SPX = 2 req = 26 req/min)
  - **시장 OPEN 한정**: MarketSession FSM 조회 후 CLOSED 면 skip
  - **KisCallPriority `INGESTION_LOW` 신규** — ADR 0037 amend (`USER_PATH > INGESTION > BACKFILL > INGESTION_LOW`)
  - **rate limit 영향**: 26 req/min ÷ 60 = 0.43 req/s. ADR 0037 한도 10 req/s 대비 4.3% 점유. 안전
- `MarketIndexDailyCloseJob` — 매일 16:30 KST (KIS 정규장 마감 + 30분 buffer)
  - 일봉 영속 + retroactive trend recompute
  - 해외 일봉은 별도 cron (한국 시간 06:00 KST · 미국 정규장 마감 + 30분)

#### Flyway 마이그레이션 6건 (api 모듈 LOCK · 본 사이클 전역 분배 · 2026-05-24 amend V47~V52 shift)
| version | 표 | 담당 sub-agent | 비고 |
|---|---|---|---|
| V47 (← V40) | `market_index_snapshot` | market-data (R1) | code · ts · price · open · high · low · close · volume · UQ(code, ts) |
| V48 (← V41) | `sector_index_snapshot` | market-data (R1) | sector_code · sector_name · ts · price · change_rate · trade_value · UQ(sector_code, ts) |
| V49 (← V42) | `ai_comment_log` | ai-engineer (R1) | §3-3 — request_id · user_id · type · provider · model · prompt_hash · tokens · cost · latency · cached · content |
| V50 (← V43) | `prompt_template` | ai-engineer (R1) | §3-3 — type · version · template_text · variables_schema · is_active (versioned) + data seed 5건 |
| V51 (← V44) | `user_preference` | backend-core (R1) | user_id PK · trend_ma_periods JSONB · trend_rs_period · trend_cross_pair JSONB null · extra JSONB · timestamps |
| V52 (← V45) | `mcp_audit` | backend-core (R2) | §4-3 — user_id · client_id · tool · params · response_size · latency_ms · error_message · called_at |

> **충돌 회피 LOCK**: 6건 모두 번호 사전 분배. R0 architect dispatch-spec §X 에 본 표 그대로 인용 의무. 사이클 도중 V47 ~ V52 외 신규 마이그레이션 발견 시 architect 우선 결정 필요.
> **2026-05-24 amend 사유**: R0 PR #674 시점 V40~V45 LOCK 발행 → ai-engineer R1 dispatch에서 main HEAD V40~V46 점유 사실 발견 → 헌법 §7 escalation → architect R0 amend로 V47~V52 재분배 (main HEAD V46 + V47 비점유 검증 evidence). 본 결함 재발 방지는 ADR 0102 신설 본문 (spec audit 5단계 절차 LOCK) 참조.

#### user_preference DDL (V51)
```sql
CREATE TABLE user_preference (
    user_id BIGINT PRIMARY KEY,
    trend_ma_periods JSONB NOT NULL DEFAULT '[5,20,60]'::jsonb,
    trend_rs_period INT NOT NULL DEFAULT 60,
    trend_cross_pair JSONB NULL,        -- null 시 maPeriods 최소·최대 auto
    extra JSONB NULL,                   -- forward-compat 확장 슬롯 (MA type · indicator · 차트 · 알림)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_pref_updated ON user_preference(updated_at DESC);
COMMENT ON COLUMN user_preference.trend_ma_periods IS 'JSONB array — MA 기간 list (1~5개 · 각 1~252일 · sorted ASC). default [5,20,60]';
COMMENT ON COLUMN user_preference.extra IS 'forward-compat 확장 슬롯 — MA type·indicator·차트·알림 등 add-only 흡수';
```

### 2-6. UI 표면

#### DashboardPage 격상
- 상단 `MarketIndexStrip` — KOSPI · KOSDAQ · NASDAQ · S&P 4 카드 가로 배치
- 각 카드: 지수 + 등락률 + 추세 라벨 (UP · SIDEWAYS · DOWN 배지) + 작은 자막 `(MA 5·20·60)` (user pref 표시)
- TanStack Query polling 30 초 + STOMP push 동시 (race-safe merge)

#### 신규 `/market/trend` 페이지
- 좌측 패널: 지수 4 선택 토글 + LWC v4 일봉 차트 + MA overlay (user pref periods 색상 분리)
- 중앙 패널: 추세 라벨 카드 + cross signal 알림 (golden / dead 발생 시 점멸)
- 우측 패널: 섹터 ranking 표 — todayTop 5 / longTop 5 탭 + 섹터 클릭 → 종목 리스트 펼침
- 우상단 인라인 토글: 빠른 변경 (프리셋 — `[5,20,60]` 전통 · `[10,30,90]` 중기 · `[20,60,120]` 장기 · `[직접 입력]`)
- 변경 시 PUT `/preferences/trend` → STOMP push → 차트 즉시 재계산

#### SettingsModal "추세 설정" 섹션 신규
- MA periods 입력 4 행 (추가 / 삭제 버튼 · 1~5개 강제)
- rsPeriod 입력 (1차는 readonly 60 · post-MVP unlock)
- crossPair 옵션 (off = auto · on = shortMa / longMa 선택)
- 미리보기 차트 (KOSPI sample · 변경 즉시 반영)
- 저장 시 PUT `/preferences/trend` + 다중 탭 STOMP sync

---

## §3. T-AI 트랙 상세

### 3-1. 5 진입점 (ADR 0026 §2-2 + MARKET 신규)
| 위치 | 버튼 | input | prompt template |
|---|---|---|---|
| `/symbol/:code` | "AI 종목 분석" | code + 지표 · 패턴 · 뉴스 · position | `prompt_template:symbol_v1` |
| `/symbol/:code` 차트 | "차트 해석" | code + timeframe + 표시 지표 | `prompt_template:chart_v1` |
| `/strategy/:id` | "전략 평가" | DSL + 최근 백테스트 | `prompt_template:strategy_v1` |
| `/dashboard` 시그널 | "시그널 해석" | signal + 종목 상태 | `prompt_template:signal_v1` |
| **`/market/trend`** | **"시장 추세 해석"** | **지수 4 + 섹터 상위 5 + 추세 라벨 + user maPeriods·rsPeriod** | **`prompt_template:market_v1`** |

### 3-2. 흐름 (ADR 0026 §2-4 정합 + 본 사이클 보강)
```
사용자 클릭 → POST /api/ai/explain (type, context)
  ↓ MAGICJAR_AI_ENABLED check → false 면 graceful refuse (한국어 "AI 비활성 상태")
  ↓ 1일 token 한도 check (ai_comment_log COUNT WHERE user_id = ? AND created_at > today)
  ↓ Redis ai:prompt:cache:{templateHash} 5분 hit check → HIT 면 즉시 STOMP
  ↓ MISS → Kafka ai.comment.requested.v1
  ↓ consumer AiCommentDispatcher → AnthropicCommentAdapter.comment()
  ↓ Resilience4j (3 retry · exponential 1s/2s/4s · 30s circuit open · 50% failure threshold)
  ↓ response → ai_comment_log INSERT (model · tokens_in · tokens_out · cost_usd · latency_ms · cached=false)
  ↓ Redis cache PUT (templateHash · 5분 TTL)
  ↓ Kafka ai.comment.responded.v1
  ↓ api STOMP /topic/ai.explain.{requestId}
  ↓ frontend AiCard 표시 (markdown render + 인용 출처 + token 비용)
```

### 3-3. 영속 (Flyway V49 · V50 — §2-5 표 정합 · 2026-05-24 amend V42·V43 shift)
| version | 표 | 비고 |
|---|---|---|
| V49 (← V42) | `ai_comment_log` | request_id UUID PK · user_id · type · provider · model · prompt_hash · tokens_in · tokens_out · cost_usd · latency_ms · cached BOOL · content TEXT · created_at |
| V50 (← V43) | `prompt_template` | type · version · template_text · variables_schema JSONB · is_active BOOL · created_at — versioned (A/B 대비) |

#### prompt_template 시드 (Flyway data 5건)
- `prompt_template:symbol_v1` — 종목 분석
- `prompt_template:chart_v1` — 차트 해석
- `prompt_template:strategy_v1` — 전략 평가
- `prompt_template:signal_v1` — 시그널 해석
- `prompt_template:market_v1` — **시장 추세 해석** (user maPeriods · rsPeriod 변수 포함 · `{{maPeriods}}`·`{{rsPeriod}}` 치환)

### 3-4. 본체 영향 0 (LOCK · grep 검증 의무)
- `MAGICJAR_AI_ENABLED=false` default + `MAGICJAR_AI_MOCK_MODE=auto` (키 없으면 자동 mock)
- LLM 실패 · timeout → frontend "AI 응답 없음" + 본체 자본 흐름 영향 0
- W1·W2·W3 코드에 AI provider import 0건 grep 검증 (ADR 0026 §3) — architect 사전 검증 의무
- 1일 1사용자 token 한도 1000 (default · env override) — 초과 시 graceful refuse 한국어
- 비용 임계 alert — ADR 0095 alert routing 보강 (월 비용 $10 초과 시 Slack)

### 3-5. AI provider 3 adapter
| provider | env | 비고 |
|---|---|---|
| Anthropic Claude | `MAGICJAR_AI_PROVIDER=anthropic` (default) | Anthropic SDK · model claude-opus-4-7 default |
| Google Gemini | `MAGICJAR_AI_PROVIDER=gemini` | free tier (dev · 1차 무료 비교) |
| OpenAI GPT | `MAGICJAR_AI_PROVIDER=openai` | 옵션 |

각 adapter 는 `AiCommentProvider` interface 구현. 신규 provider 추가 시 본체 변경 0.

---

## §4. T-MCP 트랙 상세

### 4-1. tool 6종 (read 전용 LOCK)
| tool | input | output | LLM 호출 |
|---|---|---|---|
| `get_candles` | code · interval · from · to | OHLCV array (adjusted default) | 0 |
| `run_screener` | universe · dslYaml | code list + 강도 랭킹 | 0 |
| `get_journal` | from · to · code? | journal entries + summary | 0 |
| `explain_indicator` | name · params · context? | 정적 catalog 응답 (지표 정의·계산식·해석) | 0 |
| `backtest_strategy` | dslYaml · period · universe | 성과 카드 + trades | 0 |
| **`get_market_trend`** | **scope (index\|sector\|both) + periods? + rsPeriod?** | **지수·섹터 추세 snapshot (echo periods)** | **0** |

### 4-2. JSON Schema (MCP protocol 정합)
```json
{
  "name": "get_market_trend",
  "description": "지수·섹터 추세 snapshot (사용자 MA periods 기본 적용)",
  "inputSchema": {
    "type": "object",
    "properties": {
      "scope": { "type": "string", "enum": ["index", "sector", "both"] },
      "periods": { "type": "array", "items": { "type": "integer", "minimum": 1, "maximum": 252 }, "minItems": 1, "maxItems": 5 },
      "rsPeriod": { "type": "integer", "minimum": 1, "maximum": 252, "default": 60 }
    },
    "required": ["scope"]
  }
}
```
periods 생략 시 user preference 적용 + 응답 echo. 외부 AI client (Claude / Cursor) 가 사용자 mental model 동일 조회.

### 4-3. 인증 · audit (Flyway V52 — §2-5 표 정합 · 2026-05-24 amend V45 shift)
- **인증 1차**: API key env `MAGICJAR_MCP_API_KEY` (localhost 한정)
- **mcp_audit V52 (← V45)** — `id BIGSERIAL · user_id · client_id · tool · params JSONB · response_size · latency_ms · error_message · called_at` + idx (user_id, called_at DESC)

---

## §5. 영향도·연결·확장성 lens 3 격자 (Codex 검증 게이트)

### 5-1. 영향도 매트릭스 (기존 → 신규 영향)
| 기존 ADR / 컴포넌트 | 영향 | 대응 |
|---|---|---|
| ADR 0037 KIS Rate Limit | +26 req/min (지수·섹터 cron) | `KisCallPriority.INGESTION_LOW` 신규 + ADR 0037 §X amend. 한도 4.3% 점유. 안전 |
| ADR 0048 41 stream slot | 0 (T-MARKET WS 미사용 · REST 1분 cron 충분) | slot 사용 0 |
| ADR 0084 OrderRouter | 0 (T-AI·T-MCP·T-MARKET 모두 read-only / advisory) | grep 검증 |
| ADR 0094 운영 단계 | AI 호출 = 비용 발생 risk | MAGICJAR_AI_ENABLED=false default + ADR 0095 alert routing AI 비용 임계 추가 |
| ADR 0042 STAGE 0-4 | T-AI Stage 0 (default off) | Stage 4 진입 시 사용자 동의 토글 |
| ADR 0004 모듈 매트릭스 | 7 → 8 (market-trend-engine 추가) | ADR 0004 §2 갱신 + diagram 보강 |
| 기존 SettingsModal | "추세 설정" 섹션 add-only | 기존 섹션 0 touch |
| 기존 DashboardPage | MarketIndexStrip add-only 상단 배치 | 기존 위젯 0 touch |
| 기존 ai_comment_log (ADR 0026 V20 예약) | 신규 발행 V49 (§2-5 표 LOCK · V20 예약 폐기 · 2026-05-24 amend — V42 → V49 shift) | architect R0 dispatch-spec 에서 V20 예약 명시 폐기 · ADR 0026 본문 §2-3 영속 참조 V20 → V49 갱신 |

### 5-2. 연결 매트릭스 (신규 → 기존 port · event 활용)
| 신규 컴포넌트 | 활용 기존 |
|---|---|
| `MarketIndexFetcher` | `KisRateLimiterPort` (ADR 0037) + `KisRestPort` 패턴 1:1 |
| `MarketIndexSyncJob` (batch) | `MarketSession FSM` (ADR 0013 휴장일 skip) |
| `TrendCalculator` | `Candle` 도메인 (W1) + indicator-impl 패턴 1:1 |
| `TrendPreferenceService` | `user_account` (V8) FK 정합 |
| `AiCommentDispatcher` | `notification_outbox` 패턴 (ADR 0084) · 동일 retry 정책 · Resilience4j |
| `McpController` | `OnboardingGuardFilter` skip (API key 인증 분리) · `mcp_audit` 시계열 패턴 |
| `prompt_template` | `instrument` 표 패턴 (versioned + static seed + migration) |
| 5 진입점 AiCard frontend | TanStack Query mutation + STOMP subscribe (`useAiExplain` hook) |
| `useTrendPreference` hook | TanStack Query + STOMP (`/topic/market.user.{userId}.pref`) 다중 탭 sync |

### 5-3. 확장성 슬롯 (1차 LOCK + post-MVP add-only)
| 후보 | 위치 | 마이그레이션 영향 |
|---|---|---|
| MA type (SMA·EMA·WMA·DEMA) 선택 | `TrendPreference.maType` enum (extra JSONB) | 0 |
| RS period user-settable | `TrendPreference.rsPeriod` UI 노출 | 0 (이미 컬럼 존재) |
| crossPair UI 노출 | settingsModal 토글 | 0 (이미 컬럼 존재) |
| indicator 추가 (RSI·MACD 추세) | `extra.indicators` JSONB | 0 |
| 차트 default timeframe | `extra.chartDefaultTimeframe` | 0 |
| 알림 임계 (cross 발생 시 push) | `extra.alertOnCross` boolean | 0 |
| multi-tenant team preference | `team_preference` 표 신설 | 신규 1건 |
| sentiment fusion (T-NEWS 합류) | T-AI prompt 입력에 news.collected.v1 sentiment 추가 | 0 |
| 자연어 → DSL 번역 | T-AI 진입점 6 추가 | 0 |
| MCP write tool (주문 발행) | T-MCP 7 번째 tool | 안전성 검증 후 별도 ADR |
| MCP OAuth2 인증 | ApiKeyAuthFilter → OAuth2Filter | 옵션 |

→ **`user_preference.extra JSONB` 슬롯이 post-MVP forward-compat 단일 진입점**. V51 한 번 마이그레이션으로 차후 사용자 설정 도메인 전체 흡수 (2026-05-24 amend — V44 → V51 shift).

---

## §6. Phase 분리 + dispatch 매트릭스

### 6-1. Round 매트릭스 (3 라운드 직렬 · 라운드 내 병렬)
| Round | sub-agent | scope | size | 영역 충돌 |
|---|---|---|---|---|
| **R0** | architect | spec 4건 발행 (design 동기화 + dispatch-spec + conflict-matrix + CHANGELOG slot) + ADR 0100 (T-MARKET) Proposed→Accepted + ADR 0101 (TrendPreference 도메인 + user_preference 확장 슬롯) Proposed→Accepted + ADR 0026·0027·0004·0037·0094·0095 amend + KIS endpoint 5 raw fetch evidence 첨부 + 헌법 §3 5단계 절차 정합 | L | 단독 |
| **R1 (3 병렬)** | market-data | T-MARKET Phase 1 — KIS adapter 5 (국내 3 + 해외 2) + cron Job 2 (MarketIndexSyncJob 1분 + MarketIndexDailyCloseJob 16:30) + raw fetch inline KDoc + KisCallPriority.INGESTION_LOW 합류 + Flyway V47·V48 (2026-05-24 amend — V40·V41 shift) | L | api adapter / batch job |
| | backend-core | T-MARKET Phase 2 — market-trend-engine 모듈 신설 + TrendCalculator pure math + TrendPreference 도메인 + TrendPreferenceRepository JPA + TrendPreferenceService + REST 6 endpoint Swagger 4단 + Flyway V51 (user_preference · 2026-05-24 amend — V44 shift) + STOMP 3 topic | L | domain · market-trend-engine · api service/controller |
| | ai-engineer | T-AI Phase 1 — Anthropic·Gemini·OpenAI 3 adapter + AiCommentProvider interface + AiCommentDispatcher consumer + 5 prompt template seed (V50 data) + Redis ai:prompt:cache + Resilience4j circuit + Flyway V49 (ai_comment_log) + V50 (prompt_template · 2026-05-24 amend — V42·V43 shift) + `POST /api/ai/explain` controller | L | api ai/ 패키지 + consumer dispatcher |
| **R2 (2 병렬 · R1 머지 후)** | frontend | T-MARKET·T-AI UI — DashboardPage MarketIndexStrip + `/market/trend` 페이지 + 5 진입점 AiCard + SettingsModal "추세 설정" 섹션 + `useTrendPreference`·`useMarketTrend`·`useAiExplain` hook + STOMP subscribe 3 topic + LWC v4 MA overlay | L | frontend 단독 |
| | backend-core | T-MCP Phase 1 — McpController HTTP/SSE + 6 tool (get_candles · run_screener · get_journal · explain_indicator · backtest_strategy · get_market_trend) + JSON Schema 정합 + ApiKeyAuthFilter + Flyway V52 (mcp_audit · 2026-05-24 amend — V45 shift) + explain_indicator 정적 catalog seed | M | api mcp/ 패키지 단독 |
| **R3 (직렬 · R2 머지 후)** | qa | E2E Visual 회귀 — 5 진입점 AI click flow + DashboardPage MarketIndexStrip + `/market/trend` 페이지 (default pref · custom pref 양쪽) + SettingsModal 추세 설정 변경 → 차트 즉시 반영 + MCP curl 6 tool + AI mock mode evidence + KIS Rate Limit 26 req/min 실측 evidence + docker logs 4 모듈 0건 검증 | M | qa 단독 |

### 6-2. 병렬 가능 사유 (modify-shared 0 검증)
- **R1 3 영역 분리**:
  - market-data → api adapter + batch job + Flyway V47·V48 (영속 표는 backend-core 와 별개 V47·V48 vs V51 분리 · 2026-05-24 amend V40·V41 → V47·V48 shift)
  - backend-core → domain + market-trend-engine 신설 + api service/controller + Flyway V51 (user_preference 단독 · 2026-05-24 amend V44 → V51 shift)
  - ai-engineer → api ai/ 패키지 + consumer dispatcher + Flyway V49(ai_comment_log)·V50(prompt_template · 2026-05-24 amend V42·V43 → V49·V50 shift)
  - **충돌 회피**: market-data 가 V47·V48 (market_index · sector_index) 점유 + ai-engineer 가 V49·V50 (ai_comment_log · prompt_template) 재배치. architect R0 dispatch-spec 에서 Flyway 번호 LOCK 분배 의무 (2026-05-24 amend — V40~V45 → V47~V52 shift · main HEAD V46 점유 사실 audit 누락 결함 evidence + ADR 0102 신설로 재발 방지).
- **R2 2 영역 분리**: frontend 단독 vs backend-core T-MCP api mcp/ 패키지 단독. 0 영역 충돌.

### 6-3. CHANGELOG slot anchor (ADR 0050 정합)
- R0 architect 가 CHANGELOG.md "## [Unreleased]" 섹션에 anchor 6건 사전 발행
  - `<!-- pr-N-slot-architect -->`
  - `<!-- pr-N-slot-market-data -->`
  - `<!-- pr-N-slot-backend-core-trend -->`
  - `<!-- pr-N-slot-ai-engineer -->`
  - `<!-- pr-N-slot-frontend -->`
  - `<!-- pr-N-slot-backend-core-mcp -->`
- 각 sub-agent 가 자기 slot 에만 row 1건 추가 (hunk 분리 → 머지 충돌 0)

---

## §7. ADR 신설·갱신 행렬

| ADR | 액션 | 사유 | 발행 round |
|---|---|---|---|
| **0100 (신규)** | Proposed → Accepted | T-MARKET 트랙 본문 + KIS endpoint 5 raw 인용 + 모듈 신설 + cron 정책 | R0 |
| **0101 (신규)** | Proposed → Accepted | TrendPreference 도메인 분리 + user_preference 표 + extra JSONB 확장 슬롯 (사용자 설정 단일 진입점) | R0 |
| 0026 (AI Option) | Accepted 유지 (status 확인) + §2-2 진입점 4 → 5 갱신 | T-AI 본 트랙 진입 + MARKET 신규 진입점 | R0 |
| 0027 (MCP) | Accepted 유지 + §2-2 tool 5 → 6 갱신 (get_market_trend) | T-MCP 본 트랙 진입 | R0 |
| 0004 (모듈 경계) | 모듈 매트릭스 6 → 7 갱신 (market-trend-engine) + diagram 보강 | T-MARKET 모듈 신설 | R0 |
| 0037 (KIS Rate Limit) | §X amend (KisCallPriority `INGESTION_LOW` enum 추가) | T-MARKET 우선순위 분리 (USER_PATH 의 burst 영향 0) | R0 |
| 0094 (운영 단계) | §X amend (T-AI 비용 통제 + AI 호출 KillSwitch 정책 명시) | 운영 단계 진입 후 AI 비용 risk | R0 |
| 0095 (alert routing) | alert 임계 추가 (AI 월 비용 $10 초과 · MCP 분당 호출 100 초과 · 지수 fetch 실패 5건 연속) | 신규 트랙 운영 visibility | R0 |

---

## §8. 비범위 · post-MVP backlog

본 사이클 LOCK 밖 명시 (architect 가 backlog § 신설 의무):
- MA type SMA → EMA·WMA·DEMA 선택 (1차 SMA LOCK · `extra.maType`)
- RS period user-settable UI 노출 (1차 60 LOCK · API 는 settable)
- crossPair UI 명시 지정 (1차 auto · API 는 settable)
- indicator 추가 (RSI·MACD 추세 라벨)
- 차트 default timeframe pref
- cross 발생 시 알림 push (Slack · STOMP)
- multi-tenant team_preference 표
- sentiment fusion (T-NEWS news.collected.v1 sentiment → T-AI prompt 입력)
- 자연어 → DSL 번역 (T-AI 진입점 6 추가)
- MCP write tool (주문 발행 — 안전성 검증 별도 ADR)
- MCP OAuth2 인증 (외부 client 노출 시)
- 테마/테마주 그룹 (증권사마다 다른 분류 권위 부재 — backlog)
- AI provider failover (Anthropic 실패 시 Gemini 자동 fallback)
- prompt A/B 테스트 (prompt_template.version 활용)

---

## §9. 검증 · 릴리즈 게이트

### 9-1. R3 qa Visual 회귀 케이스 (Playwright + 스크린샷)
- **케이스 1 — DashboardPage MarketIndexStrip 4 카드 노출 + 추세 라벨 표시** (default pref + 사용자 pref [10,30,90] 양쪽)
- **케이스 2 — `/market/trend` 페이지 진입 + 차트 MA overlay user pref 색상 분리 + 섹터 ranking todayTop·longTop 탭 전환**
- **케이스 3 — SettingsModal 추세 설정 → MA periods [3,7,21] 저장 → 다른 탭 STOMP push 자동 sync + 차트 즉시 재계산**
- **케이스 4 — 5 AI 진입점 click → "AI 응답 없음" mock mode + MAGICJAR_AI_ENABLED=true + Anthropic key 시 실제 응답**
- **케이스 5 — MCP curl 6 tool 200 응답 + get_market_trend periods 생략 시 user pref echo**
- **케이스 6 — 잘못된 periods 입력 (0개 · 6개 · 중복 · 253) → Bean Validation 400 응답 한국어**
- **케이스 7 — KIS Rate Limit 실측 — MarketIndexSyncJob 1분 cron 60회 운영 후 KisRateLimiterPort 통계 26 req/min ± 5% · EGW00201 0건**

### 9-2. docker logs 4 모듈 0건 검증 (cut 직전 의무)
- `feedback-docker-log-verify-before-cut.md` 정합
- api · batch · consumer · frontend 4 모듈 docker logs grep "ERROR\|FATAL" 0건 확인 후 v3.9.0 cut

### 9-3. Codex 검증 만점 (사용자 명시 게이트)
- `feedback-codex-validation-gate.md` 정합
- 4 lens (세심 · 영향도 · 연결 · 확장성) 모두 sub-agent 산출에서 충족 확인
- Codex 외부 review 통과 = patch 완료 권위
- 만점 미달 시 orchestrator 재dispatch (architect 검토 + 재산출)

### 9-4. v3.9.0 release 게이트 순서 (ADR 0052 §X LOCK)
1. R3 qa 케이스 7건 GREEN
2. `./gradlew check` GREEN + `scripts/smoke.sh` 통과
3. docker logs 4 모듈 0건
4. **Codex 외부 review 통과**
5. `/baseline init v3.9.0` — 4 sub-agent 병렬 → 7 md + 8 html 풀세트
6. baseline 8 PR 머지 완료 후 → `git tag v3.9.0` + `release/v3.9.0` 브랜치 push (ADR 0031 immutable)
7. `scripts/release.sh v3.9.0` — release notes + `gh release create` + README badge sync

---

## §10. 다음 단계 (브레인스토밍 종착)

본 design 문서는 brainstorming 통합 결과. 사용자 승인 완료. 다음 액션:

1. **본 문서 git commit** (chore branch · bot+orchestrator author)
2. **magicjar-orchestrator 스킬 발동** — R0 architect 단독 dispatch
3. architect 가 본 문서 §1~§9 정합으로 4 spec 발행 (design 동기화 + dispatch-spec + conflict-matrix + CHANGELOG slot) + ADR 0100·0101 신설 + ADR 0026·0027·0004·0037·0094·0095 amend
4. architect R0 머지 후 R1 3 병렬 → R2 2 병렬 → R3 qa 직렬
5. v3.9.0 cut 직전 Codex 검증 게이트

---

**문서 끝**
