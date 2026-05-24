# ADR 0101: TrendPreference 1급 도메인 + `user_preference` 표 (forward-compat 확장 슬롯 단일 진입점)

- 상태: **Accepted (2026-05-24 — architect R0 spec 발행, 사용자 brainstorming 결재 완료 cec6a4d6)**
- 작성일: 2026-05-24
- 결정자: architect (W4 통합 사이클 R0 — orchestrator dispatch 정합)
- 영역: 도메인 본체 (§4) + 운영 단계 SLO 영향 (§9-1) + 모듈 경계 (§2)
- 권위 spec: `_workspace/specs/2026-05-24-w4-t-market-t-ai-t-mcp-design.md` (brainstorming §0-2 사용자 추가 의제 1건 + §2-2 도메인 정의)
- 관련 ADR: 0001(비전) · 0004(모듈 경계) · 0010(Multi-tenant — user_id FK) · 0030(SoT) · 0083(indicator-impl 패턴) · 0088(persistence-shared 패턴) · 0094(운영 단계 — 본 ADR 첫 도메인 ADR amend 정합) · 0100(T-MARKET 트랙 짝 ADR)

---

## 1. 맥락 (사용자 brainstorming 결재)

### 1-1. 사용자 추가 의제 1건 (brainstorming §0-2 — 2026-05-24)

> "MA 기간 (5d·20d·60d) 을 시스템 hardcoded 가 아니라 **사용자 설정 가능 도메인** 으로 격상. 1~5개·1~252일·sorted ASC validation. 향후 확장 슬롯 (RS period · cross pair · MA type · indicator 추가) 까지 forward-compat."

→ MA periods를 ADR 0100 T-MARKET 트랙 내부 hardcoded constant로 두면:
- 사용자 다른 mental model (예: 단기 [3, 7, 21], 장기 [20, 60, 120]) 지원 0 → 의사결정 input 약화
- 향후 RS period · cross pair · indicator 추가 시 새 표·새 컬럼 add 의무 → 마이그레이션 폭증
- AI prompt context에 사용자 mental model 반영 0 → AI 답변 일반화 risk
- MCP `get_market_trend` tool 외부 client(Claude/Cursor) 사용자 mental model 동일 조회 0

→ **TrendPreference 1급 도메인 격상 + `user_preference` 표 + `extra JSONB` forward-compat 확장 슬롯 단일 진입점**이 본 결정.

### 1-2. fitness gap (현재 baseline)

- 현재 `user_account` (V8) 1차 시민 시민 — 사용자 본질 정보만 (id · email · 가입일 등). 사용자 설정 1건도 영속 0
- KIS 키 등 비밀 정보는 별도 표 (`kis_credential` 등) — 본질 분리 OK
- "사용자 설정 — 추세 외 차트·알림·테마 등 모든 설정"의 단일 owner 표 부재 → 향후 표 add-only 폭증 risk

### 1-3. 운영 단계 lens 정합 (ADR 0094)

- mindset: 본 ADR은 **사용자 설정 단일 진입점** — `extra JSONB` 확장 슬롯이 post-MVP 모든 사용자 설정 흡수 path (마이그레이션 폭증 회피)
- 자본 path 영향 0 (read-only · 추세 라벨만 영향)
- 신규 표 1건 (V44) — 마이그레이션 단일

---

## 2. 결정

### 2-1. TrendPreference 1급 도메인 (`domain` 모듈)

#### 2-1-A. 도메인 정의

```kotlin
// domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/trend/TrendPreference.kt
package kr.spdaewoon.magicjar.domain.trend

import java.time.Instant

/**
 * 사용자 시장 추세 분석 설정 (MA periods · RS period · cross pair).
 *
 * 본 도메인은 forward-compat 확장 슬롯 단일 진입점 — MA type · indicator 추가 · 차트 default · 알림 임계 등
 * 모든 사용자 설정은 `extra JSONB` (Flyway V44 user_preference 표)로 add-only 흡수.
 *
 * @see ADR 0100 (T-MARKET 트랙) · ADR 0101 (본 ADR — TrendPreference + user_preference)
 */
data class TrendPreference(
    val userId: Long,
    val maPeriods: List<Int>,        // default [5, 20, 60] · size 1~5 · 각 1~252 · sorted ASC 의무
    val rsPeriod: Int = 60,          // RS rating 비교 기간 (1차 60 LOCK · post-MVP user-settable)
    val crossPair: Pair<Int, Int>? = null,  // null 시 maPeriods 최소·최대 auto
    val updatedAt: Instant,
) {
    init {
        require(maPeriods.isNotEmpty()) { "MA periods 1개 이상 필수" }
        require(maPeriods.size <= 5) { "MA periods 5개 초과 금지 (입력=${maPeriods.size})" }
        require(maPeriods.all { it in 1..252 }) {
            "각 MA 기간은 1~252일 사이 (입력 위반=${maPeriods.filter { it !in 1..252 }})"
        }
        require(maPeriods == maPeriods.sorted().distinct()) {
            "MA periods는 중복 없이 오름차순 (입력=$maPeriods)"
        }
        require(rsPeriod in 1..252) { "RS period는 1~252일 사이 (입력=$rsPeriod)" }
        crossPair?.let { (short, long) ->
            require(short in maPeriods && long in maPeriods) {
                "crossPair는 maPeriods 내 값 필수 (입력=$short/$long · maPeriods=$maPeriods)"
            }
            require(short < long) { "crossPair는 short < long (입력=$short/$long)" }
        }
    }

    /** crossPair 명시 시 본 값, null 시 maPeriods 최소 */
    fun shortMa(): Int = crossPair?.first ?: maPeriods.first()

    /** crossPair 명시 시 본 값, null 시 maPeriods 최대 */
    fun longMa(): Int = crossPair?.second ?: maPeriods.last()
}

object TrendPreferenceDefaults {
    val MA_PERIODS = listOf(5, 20, 60)
    const val RS_PERIOD = 60
}
```

#### 2-1-B. Repository port (domain interface)

```kotlin
// domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/trend/TrendPreferenceRepository.kt
package kr.spdaewoon.magicjar.domain.trend

interface TrendPreferenceRepository {
    /** 사용자 ID로 조회. 없으면 null. */
    fun findByUserId(userId: Long): TrendPreference?

    /** UPSERT — 신규 row 또는 기존 row 갱신. updatedAt 자동 now(). */
    fun upsert(pref: TrendPreference): TrendPreference
}
```

#### 2-1-C. JPA adapter (api 또는 persistence-shared 1차 결정 — R1 backend-core 영역)

ADR 0088 패턴 1:1 reuse 의제 — 본 ADR R1 backend-core dispatch 시점에 결정 (api 단독 또는 persistence-shared 흡수). 1차 권장: **api 단독** (`user_account` 등 사용자 관련 표는 api 단독 패턴 정합).

### 2-2. Flyway V44 — `user_preference` 표 (R1 backend-core 영역 LOCK)

```sql
-- V44__user_preference.sql
CREATE TABLE user_preference (
    user_id BIGINT PRIMARY KEY REFERENCES user_account(id) ON DELETE CASCADE,
    trend_ma_periods JSONB NOT NULL DEFAULT '[5,20,60]'::jsonb,
    trend_rs_period INT NOT NULL DEFAULT 60,
    trend_cross_pair JSONB NULL,        -- null 시 maPeriods 최소·최대 auto · 형식 {"short": 5, "long": 60}
    extra JSONB NULL,                   -- forward-compat 확장 슬롯 (MA type · indicator · 차트 default · 알림 등)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_user_pref_ma_periods_size CHECK (jsonb_array_length(trend_ma_periods) BETWEEN 1 AND 5),
    CONSTRAINT chk_user_pref_rs_period CHECK (trend_rs_period BETWEEN 1 AND 252)
);
CREATE INDEX idx_user_pref_updated ON user_preference(updated_at DESC);
COMMENT ON TABLE user_preference IS '사용자 설정 단일 진입점 (ADR 0101). extra JSONB는 forward-compat 확장 슬롯.';
COMMENT ON COLUMN user_preference.trend_ma_periods IS 'JSONB array — MA 기간 list (1~5개 · 각 1~252일 · sorted ASC). default [5,20,60].';
COMMENT ON COLUMN user_preference.trend_rs_period IS 'RS rating 비교 기간 (1~252일). default 60.';
COMMENT ON COLUMN user_preference.trend_cross_pair IS 'crossPair 명시 — {"short":5,"long":60}. null 시 maPeriods 최소/최대 auto.';
COMMENT ON COLUMN user_preference.extra IS 'forward-compat 확장 슬롯 — MA type·indicator·차트 default·알림 임계 등 add-only 흡수. Kotlin 측 Map<String, Any?>.';

-- 기존 사용자 default 시드 (idempotent — ON CONFLICT DO NOTHING)
INSERT INTO user_preference (user_id, trend_ma_periods, trend_rs_period)
SELECT id, '[5,20,60]'::jsonb, 60
FROM user_account
ON CONFLICT (user_id) DO NOTHING;
```

#### 2-2-A. CHECK 제약 보강 사유

- `jsonb_array_length(trend_ma_periods) BETWEEN 1 AND 5` — DB level invariant (Kotlin require + DB CHECK 이중 방어)
- `trend_rs_period BETWEEN 1 AND 252` — 동일 패턴
- `trend_cross_pair` JSONB 형식·인덱스 unique 등 추가 invariant는 application level (Kotlin require) — DB CHECK는 비효율

#### 2-2-B. 시드 정합

기존 `user_account` 모든 row에 default `[5,20,60]` 시드 → 마이그레이션 즉시 사용자 모두 default pref 보유. `ON CONFLICT DO NOTHING` idempotent — 재실행 안전.

### 2-3. REST 표면 (preference 2 endpoint — ADR 0100 §2-5 정합)

| # | Method | endpoint | 용도 | controller |
|---|---|---|---|---|
| 1 | GET | `/api/users/me/preferences/trend` | 현재 설정 조회 (없으면 default echo · `isUserCustom: false`) | api `UserPreferenceController` |
| 2 | PUT | `/api/users/me/preferences/trend` | MA periods · rsPeriod · crossPair 변경 | api `UserPreferenceController` |

#### 2-3-A. Bean Validation (R1 backend-core 영역)

PUT request body:

```kotlin
data class TrendPreferenceUpdateRequest(
    @field:Size(min = 1, max = 5, message = "MA periods 1~5개")
    val maPeriods: List<@field:Min(1) @field:Max(252) Int>,

    @field:Min(1)
    @field:Max(252)
    val rsPeriod: Int = 60,

    @field:Valid
    val crossPair: CrossPairDto? = null,
)

data class CrossPairDto(
    @field:Min(1) @field:Max(252)
    val short: Int,
    @field:Min(1) @field:Max(252)
    val long: Int,
)
```

검증 실패 시 400 한국어 응답 (예: `"MA periods는 1~5개 사이여야 합니다."`). 자체 추론 0 — Spring Boot ResponseStatusException + MethodArgumentNotValidException handler reuse.

#### 2-3-B. Swagger 4단 의무

```kotlin
@Tag(name = "사용자 설정 API", description = "사용자 mental model 단일 진입점 — 추세 분석 설정 외 모든 설정 확장 슬롯")
@RestController
@RequestMapping("/api/users/me/preferences")
class UserPreferenceController(...) {

    @Operation(
        summary = "추세 분석 설정 조회",
        description = "현재 사용자의 MA periods · RS period · cross pair 조회. 없으면 default [5,20,60] echo."
    )
    @GetMapping("/trend")
    fun getTrendPreference(...): TrendPreferenceResponse { ... }

    @Operation(
        summary = "추세 분석 설정 변경",
        description = "MA periods 1~5개 · 각 1~252일 · sorted ASC. RS period 1~252. crossPair는 maPeriods 내 short<long."
    )
    @PutMapping("/trend")
    fun updateTrendPreference(
        @Parameter(description = "추세 설정 (Bean Validation 적용 — 위반 시 400 한국어)")
        @Valid @RequestBody request: TrendPreferenceUpdateRequest,
        ...
    ): TrendPreferenceResponse { ... }
}
```

### 2-4. STOMP fanout — `/topic/market.user.{userId}.pref` (ADR 0100 §2-6 정합)

PUT 성공 후 즉시 push — 다중 탭 sync. WebSocketConfig reuse (frontend `useTrendPreference` hook subscribe).

### 2-5. `extra JSONB` 확장 슬롯 매트릭스 (post-MVP add-only 흡수 — backlog § 권위)

| 후보 | extra 키 path | 타입 | 마이그레이션 영향 |
|---|---|---|---|
| MA type (SMA·EMA·WMA·DEMA) 선택 | `extra.maType` | enum string | 0 (add-only) |
| RS period UI 노출 (1차는 readonly 60) | (이미 컬럼 존재 — UI 노출 backlog) | int | 0 |
| crossPair UI 노출 (1차는 auto) | (이미 컬럼 존재 — UI 노출 backlog) | object | 0 |
| RSI 추세 라벨 추가 | `extra.indicators.rsi.enabled` · `extra.indicators.rsi.period` | object | 0 |
| MACD 추세 라벨 추가 | `extra.indicators.macd.fast` · `slow` · `signal` | object | 0 |
| 차트 default timeframe | `extra.chartDefaultTimeframe` | string (1m · 5m · 1d 등) | 0 |
| 차트 default candle count | `extra.chartDefaultCandleCount` | int | 0 |
| cross 발생 시 알림 (Slack · STOMP push) | `extra.alertOnCross.enabled` · `.channels` | object | 0 |
| 알림 임계 (지수 등락률 ±N%) | `extra.alertOnIndex.threshold` | float | 0 |
| 다크모드 · 언어 등 UI 설정 | `extra.ui.theme` · `extra.ui.locale` | string | 0 |
| AI prompt 사용자 메모 (트레이딩 스타일) | `extra.ai.userMemo` | text | 0 |
| MCP 외부 client 권한 매트릭스 | `extra.mcp.allowedTools` | array | 0 |

→ **단일 마이그레이션 V44 한 번으로 모든 사용자 설정 확장 path 흡수**. 신규 컬럼 add 의무 0건 baseline.

#### 2-5-A. 단점 (확장 슬롯 vs 컬럼)

- DB 검색 시 JSONB path 사용 → 인덱싱 효율 정규 컬럼 < JSONB
- 타입 안전성 약화 → application layer Kotlin require 보강 의무
- 마이그레이션 폭증 회피 효과는 매우 큼 (사용자 설정은 정형성 약함 + add-only 본질)

→ **결정: trade-off 수용**. 핵심 invariant 3개만 정규 컬럼 (trend_ma_periods · trend_rs_period · trend_cross_pair) + 나머지는 `extra JSONB` 확장 슬롯.

---

## 3. 영향도 매트릭스

### 3-1. 기존 ADR · 컴포넌트 영향

| 기존 ADR/컴포넌트 | 영향 | 대응 |
|---|---|---|
| ADR 0010 (Multi-tenant) | 0 (user_id FK reuse) | 본 ADR `user_preference.user_id REFERENCES user_account(id) ON DELETE CASCADE` |
| ADR 0004 (모듈 경계) | 0 (domain 추가만) | `domain/.../trend/TrendPreference.kt` · `TrendPreferenceRepository.kt` add-only |
| 기존 `user_account` 표 | 0 (FK reference만) | 시드 SQL `INSERT FROM user_account ON CONFLICT DO NOTHING` |
| 기존 SettingsModal | LOW (add-only) | "추세 설정" section add-only · 기존 section 0 touch |
| ADR 0094 (운영 단계) | LOW | 운영 ADR 양식 3 슬롯 의무 (§5) |
| ADR 0100 (T-MARKET 짝) | HIGH (양방 의존) | 짝 ADR — TrendPreference가 T-MARKET trend 라벨 계산 input |

### 3-2. 영역 침범 매트릭스 (헌법 §1-A · §2 정합 — 충돌 회피)

| 영역 | 침범 | 회피 |
|---|---|---|
| domain | 신규 2 파일 add-only (TrendPreference · TrendPreferenceRepository) | 기존 domain 0 touch |
| Flyway V44 | LOCK (backend-core 영역) | V40·V41 (market-data) · V42·V43 (ai-engineer) · V45 (backend-core-mcp) 분리 LOCK |
| api controller | 신규 1개 add-only (UserPreferenceController) | 기존 controller 0 touch |
| 자본 path | 0 (read-only + 사용자 설정만) | grep 검증 의무 |

### 3-3. 연결 매트릭스 (신규 → 기존 reuse)

| 신규 컴포넌트 | 활용 기존 |
|---|---|
| `TrendPreference.userId` FK | `user_account.id` (W1 V8) |
| `TrendPreferenceService` (market-trend-engine) | `TrendPreferenceRepository` port (본 ADR) |
| `UserPreferenceController` (api) | Spring Security `@AuthenticationPrincipal` 또는 OnboardingGuardFilter pattern reuse |
| `useTrendPreference` hook (frontend) | TanStack Query mutation + STOMP subscribe `/topic/market.user.{userId}.pref` |
| `prompt_template:market_v1` (ADR 0026 amend — T-AI) | `{{maPeriods}}`·`{{rsPeriod}}` 변수 치환 → AI prompt 사용자 mental model 반영 |
| MCP `get_market_trend` tool (ADR 0027 amend — T-MCP) | periods 생략 시 user pref echo |

---

## 4. 결과

### 4-1. 포지티브

- 사용자 mental model 단일 진입점 — UI · API · AI prompt · MCP tool 모두 동일 사용자 설정 적용
- `extra JSONB` 확장 슬롯 — 향후 사용자 설정 12+ 후보(§2-5) 모두 마이그레이션 0건으로 흡수
- 1차 단순 (3 정규 컬럼 + extra JSONB) — DB 설계 LOW complexity
- 시드 SQL idempotent — 기존 사용자 자동 default pref 보유 (사용자 등록 의무 0)

### 4-2. 네거티브 (mitigation 포함)

- `extra JSONB` 타입 안전성 약화 — **mitigation**: application layer Kotlin require 보강 + Kotlin data class adapter 도입 후 type-safe path
- JSONB 인덱싱 효율 < 정규 컬럼 — **mitigation**: 핵심 invariant 3개만 정규 컬럼 + 쿼리 패턴 단순 (user_id PK lookup 단일)
- crossPair invariant DB level CHECK 부재 (application only) — **mitigation**: Kotlin require 본문 + 단위 테스트 fixture 강제

---

## 5. 운영 ADR 양식 3 슬롯 (헌법 §9-5 정합)

### 5-1. SLO 영향 (ADR 0095 §9-1)

| SLO | 영향 | 분석 |
|---|---|---|
| S-1·S-2·S-4·S-5 | 0 | 본 ADR 사용자 설정 path · 자본 path 미경유 |
| S-3 (account API p95<500ms) | 0 (별 endpoint) | 신규 endpoint `/api/users/me/preferences/trend` · account path 별개 · 단순 SELECT/UPSERT |

신규 SLO 후보 (post-MVP backlog):
- `user_preference` UPSERT 성공률 > 99.9% — P1 backlog
- TrendPreference 변경 STOMP push latency p95 < 200ms — P2 backlog

### 5-2. rollback plan

본 ADR 운영 부적합 발견 시 즉시 되돌리는 절차:

| 단계 | 절차 | 예상 소요 |
|---|---|---|
| 1 | `application.yml` `magicjar.user.preference.enabled=false` env toggle | < 1분 |
| 2 | `UserPreferenceController` `@ConditionalOnProperty` 자동 비활성 | 즉시 |
| 3 | frontend `SettingsModal` "추세 설정" section feature flag `VITE_FEATURE_TREND_PREF=false` | < 5분 (rebuild) |
| 4 | T-MARKET trend 라벨 계산 hardcoded default [5,20,60] fallback (TrendPreferenceService graceful → default Defaults 반환) | 즉시 (코드 이미 default echo path 존재 — `isUserCustom: false`) |
| 5 | DB `user_preference` 표 보존 (rollback 데이터 손실 없음) | 0 |
| 6 | PR revert (Flyway V44 down 불요 — table drop은 별 사이클 의제) | < 30분 |

전체 rollback 6분 (RTO 30분 LOCK 안 · ADR 0096 §4 정합).

### 5-3. 관측 매트릭스

| 측정 | source | 1차 권한 | alert 임계 |
|---|---|---|---|
| `user_preference` UPSERT 성공률 | Micrometer counter `magicjar.user.preference.upsert.success` / `.failure` | Prometheus | failure > 1건/h warning |
| TrendPreference 변경 빈도 | Prometheus `sum(increase(magicjar_user_preference_upsert_success[24h]))` | Grafana panel | (단순 observability — alert 없음) |
| STOMP push latency (PUT → fanout) | Micrometer timer `magicjar.user.preference.stomp.push.latency` | Prometheus | p95 > 200ms warning |
| Visual 회귀 시나리오 (헌법 §9-2 frontend 핵심 화면 영향) | qa Playwright | R3 자동 dispatch | SettingsModal 추세 설정 변경 → 다중 탭 즉시 sync · 잘못된 입력 (0개·6개·중복·253) 시 400 한국어 (2 케이스 LOCK) |

---

## 6. 트레이드오프

| 의제 | 채택 | 거부 |
|---|---|---|
| 표 위치 | **`user_preference` 신설** | `user_account` 컬럼 add (사용자 본질 정보와 설정 정보 분리 위반) |
| 정규 컬럼 vs JSONB | **핵심 3 정규 + extra JSONB** (forward-compat) | 모든 컬럼 정규화 (마이그레이션 폭증 risk) · 모든 JSONB (타입 안전성 약화 risk) |
| 시드 정책 | **`INSERT FROM user_account ON CONFLICT DO NOTHING`** (idempotent · 기존 사용자 자동 default) | 사용자 명시 등록 (사용자 부담 ↑) · default 반환 only (영속 0건 → query 비효율) |
| crossPair invariant | **application require + DB CHECK 부재** | DB JSONB CHECK (PostgreSQL JSONB CHECK 복잡도 ↑) |
| user_id FK ON DELETE | **CASCADE** | NO ACTION (사용자 삭제 시 orphan row 누적 risk) · SET NULL (FK 본질 위반) |
| Repository pattern | **domain port + JPA adapter (api 단독)** | persistence-shared 흡수 (R1 backend-core dispatch 시 1차 결정) |
| post-MVP 확장 슬롯 위치 | **`extra JSONB`** | 신규 `user_setting_kv` key-value 표 (조회 효율 낮음 + 마이그레이션 추가) |

---

## 7. 후속 사이클 (post-MVP)

| 의제 | 위치 | 트리거 |
|---|---|---|
| MA type SMA → EMA·WMA·DEMA UI 노출 | `extra.maType` + SettingsModal select | 사용자 명시 |
| RS period user-settable UI | SettingsModal input + Bean Validation | post-MVP |
| crossPair UI 명시 지정 | SettingsModal toggle | post-MVP |
| RSI·MACD 추세 라벨 | `extra.indicators.*` + TrendCalculator add-only | 사용자 명시 |
| 차트 default timeframe pref | `extra.chartDefaultTimeframe` | post-MVP |
| 알림 임계 (cross 발생 push) | `extra.alertOnCross.*` + NotificationDispatcher reuse | 사용자 명시 |
| multi-tenant team_preference 표 | 별 표 (개인 vs 팀 본질 분리) | 실서비스 진입 시 |
| AI prompt 사용자 메모 | `extra.ai.userMemo` + T-AI prompt context | T-AI Phase 2 |
| MCP 외부 client 권한 매트릭스 | `extra.mcp.allowedTools` | T-MCP Phase 2 |
| 사용자 설정 export/import | api `/api/users/me/preferences/export` | post-MVP |

---

## 8. References

- `_workspace/specs/2026-05-24-w4-t-market-t-ai-t-mcp-design.md` (brainstorming §0-2 사용자 추가 의제 · §2-2 도메인 정의 권위)
- `_workspace/specs/2026-05-24-w4-r0-design.md` (architect R0 dispatch 정합)
- ADR 0001 (시스템 비전) · ADR 0004 (모듈 경계) · ADR 0010 (Multi-tenant — user_id FK) · ADR 0083 (indicator-impl 패턴) · ADR 0088 (persistence-shared 패턴) · ADR 0094 (운영 단계) · ADR 0100 (T-MARKET 짝)
- ADR 0095 (SLO/SLA) · ADR 0096 (DR/backup)

---

## 9. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-05-24 | **ADR 0101 신설 Accepted — TrendPreference 1급 도메인 + `user_preference` 표 (V44) + `extra JSONB` forward-compat 확장 슬롯 단일 진입점 + REST 2 endpoint Swagger 4단 + STOMP fanout + 시드 SQL idempotent + Bean Validation 강제 + 운영 ADR 양식 3 슬롯 + 후속 사이클 10+ 슬롯** — 사용자 brainstorming §0-2 추가 의제 1건 결재 정합. orchestrator → architect R0 dispatch. 운영 코드 0 touch (Kotlin/TS/SQL 0 변경 — ADR + spec + ADR README + master + CHANGELOG only). 자본 영향 0 + KIS endpoint 호출 0 + AI 호출 0 + mock 합성 0. ADR 0100 (T-MARKET) 짝 ADR 동시 발행. (architect, opus 4.7 1M) |
