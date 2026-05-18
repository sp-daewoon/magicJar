# spec — 잔액·예수금·계좌 잔고 사용자 노출 endpoint + UI 트랙 (T-ACCT)

- 발행: 2026-05-18 architect
- 사용자 의제: "지금 잔액조회 예수금 조회 이런게 아예 없는데?" (2026-05-18 14:50 KST · 최고 관리자)
- 결정 사유 (사용자 명시): "신규 트랙으로 분리 — architect spec/plan 발행"
- 트랙 ID: **T-ACCT** (Account Balance & Positions exposure — W3 보강 트랙)
- 발행 PR: agent/architect/spec-account-balance-positions-track (운영 코드 0 touch · 문서·spec only)
- 신설 ADR: **0081 — Account Balance & Positions User-Facing Exposure**
- 인접 ADR: 0042 (Bootstrap) · 0070 (T-RECON) · 0067 (T-RISK CapitalSnapshotProvider) · 0068 (T-O) · 0072 (T-PERF)
- 사용자 메모리: `feedback_v3_skepticism_no_regression` (자동매매 fitness 의무) · `feedback_kis_key_realonly_for_data` (정보 수집 = 실전 키) · `feedback_no_mock_data` (mock 합성 0) · `project_system_vision` (사용자=최고 관리자 실시간 잔고 확인)
- 헌법 §3 LOCK: KIS-first raw fetch 의무 — 본 spec §3 KIS API 매트릭스 정합

---

## 1. 결함 진단 (dogfooding evidence 인용)

### 1-1. 사용자 명시 dogfooding

> "지금 잔액조회 예수금 조회 이런게 아예 없는데?"
> — 사용자 (최고 관리자) 2026-05-18 14:50 KST

### 1-2. orchestrator 진단 매트릭스

| 컴포넌트 | 현황 | 사용자 노출 |
|---|---|---|
| `consumer/.../infrastructure/kis/KisBalanceAdapter.kt` | **존재** — ADR 0042 Bootstrap STAGE 0-1 용 (TR_ID `TTTC8434R`/`VTTC8434R`) | ❌ 내부 용도 only |
| `consumer/.../reconcile/KisReconcileBalanceAdapter.kt` | **존재** — ADR 0070 T-RECON 페이징 흡수 용 | ❌ 내부 용도 only |
| `domain/.../position/port/KisBalancePort.kt` | **존재** — `fetchBalance(userId, accountType) → KisBalance(holdings)` | ❌ holdings only (output1만, output2 0건) |
| `consumer/.../bootstrap/PositionBootstrapRunner.kt` | **존재** — KIS REST → DB position 워밍 | ❌ 부팅 1회 only |
| `api/.../controller/PositionController.kt` | **존재** — `GET /api/positions` (DB position 조회) | ✅ but DB-only (실시간 잔고 0건, output2 예수금 0건) |
| `consumer/.../risk/CapitalSnapshotProviderAdapter.kt` | **존재** — ADR 0067 §7-1 default no-op fallback 0 반환 | ❌ Phase 2 미합류 → POSITION_LIMIT 룰 무력 |
| **api `/api/account/balance` endpoint** | **❌ 부재** | — |
| **api `/api/account/positions` endpoint** | DB position은 `/api/positions` 있으나 KIS 실시간 0 | — |
| **api `/api/account/summary` endpoint** | **❌ 부재** | — |
| **frontend BalanceCard·DashboardSection** | **❌ 부재** — `pages/DashboardPlaceholder.tsx` placeholder만 | — |
| **frontend `pages/Risk/PositionsPage.tsx`** | **존재** — DB position 테이블 only (예수금·평가금 0건) | ✅ 부분 |
| **frontend AppHeader 잔고 배지** | **❌ 부재** | — |

### 1-3. fitness gap (사용자 메모리 `feedback_v3_skepticism_no_regression`)

- **"자동매매에 필요한가?"** → ✅ 필수 (사용자가 매매 가능 종목·수량 판단 + 룰 평가 시 가용 자본 검증 = T-RISK POSITION_LIMIT 룰 결합)
- **"v1/v2 잔재 아닌가?"** → ❌ 신규 (현재 사용자 노출 0건)
- **CapitalSnapshotProvider 0 fallback** → POSITION_LIMIT 룰 사실상 무력 (`getCapital(userId) → BigDecimal.ZERO`) → 본 트랙으로 동시 해소

### 1-4. 비전 정합 (`project_system_vision.md`)

- 사용자=최고 관리자 — 실시간 잔고 확인 의무 ✅
- 룰 기반 quant trading 본체 — 룰 평가 시 가용 자본 검증에 잔고 필요 (T-RISK CapitalSnapshotProvider 결합)
- AI 옵션 — 잔고 분석 코멘트 (W4 T-AI 후보 — 본 트랙 0건 LOCK)

---

## 2. 현행 코드 매트릭스 (정독 완료)

### 2-1. `KisBalanceAdapter.kt` (consumer/infrastructure/kis/) — 정독 evidence

**현재 동작** (L100~L121):
```kotlin
val output1 = response["output1"] as? List<Map<String, Any>> ?: emptyList()
val holdings = output1.mapNotNull { item ->
    val code = item["pdno"]?.toString() ?: return@mapNotNull null
    val qty = item["hldg_qty"]?.toString()?.toIntOrNull() ?: 0
    val avgPrice = item["pchs_avg_pric"]?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
    if (qty <= 0) return@mapNotNull null
    KisHolding(code = code, quantity = qty, avgPrice = avgPrice)
}
// output2 (계좌 잔고 합계 — 예수금·평가금 등) 완전 누락 ❌
```

**핵심 결함**:
- output2 8 필드(`dnca_tot_amt`·`nxdy_excc_amt`·`prvs_rcdl_excc_amt`·`tot_evlu_amt`·`nass_amt`·`pchs_amt_smtl_amt`·`evlu_amt_smtl_amt`·`evlu_pfls_smtl_amt`) 전혀 파싱 안 됨
- `KisBalance` 도메인이 `holdings: List<KisHolding>` only — `cashBalance`·`totalEvaluation` 등 필드 부재

### 2-2. `KisReconcileBalanceAdapter.kt` (consumer/reconcile/) — 정독 evidence

- ADR 0070 T-RECON 전용 — 페이징(50건/요청 실전·20건 모의) 흡수
- `ReconcileKisBalancePort.fetchAllBalances(userId)` → `List<ReconcileHolding>` only
- output2 미파싱 (ADR 0070 §8 LOCK이 holdings 비교만 의무)
- **본 트랙 비범위** — T-RECON은 그대로 두고 T-ACCT 신규 컴포넌트 추가

### 2-3. `PositionController.kt` (api/controller/) — 정독 evidence

- `GET /api/positions?userId=...&source=...` (DB `position` 테이블 조회)
- `GET /api/positions/{id}`
- DB는 ADR 0042 Bootstrap·T-RECON으로 갱신되는 snapshot — 실시간 KIS REST 호출 0
- output2(예수금) endpoint 0건
- 본 트랙은 PositionController 0 touch — 신규 `/api/account/*` 추가

### 2-4. `CapitalSnapshotProviderAdapter.kt` (consumer/risk/) — 정독 evidence

```kotlin
@Bean
@ConditionalOnMissingBean(CapitalSnapshotProvider::class)
fun defaultCapitalSnapshotProvider(): CapitalSnapshotProvider =
    object : CapitalSnapshotProvider {
        override fun getCapital(userId: String): BigDecimal {
            log.debug("CapitalSnapshotProvider Phase 2 미합류 — fallback 0 (userId={})", userId)
            return BigDecimal.ZERO   // ❌ POSITION_LIMIT 룰 무력 (capital 0 → 모든 매수 차단)
        }
    }
```

ADR 0067 §7-1 — Phase 2 미합류 상태. 본 트랙 §B-1 endpoint 합류 시 실제 어댑터 발행 의무.

### 2-5. frontend 잔고 UI 0건 evidence

- `grep -r "예수금\|deposit\|cashBalance" frontend/src` → 1건만 (`PositionCard.tsx` "평가금액" 단어 단순 표기 라벨 · KIS 잔고 조회 logic 0)
- `DashboardPlaceholder.tsx` — placeholder only (실시간 잔고 0)
- `AppHeader.tsx` — 배지 0
- `Risk/PositionsPage.tsx` — DB position 테이블 (qty·avgPrice·currentPrice) only · 예수금·평가금 0

---

## 3. KIS API 매트릭스 (KIS-first raw fetch 의무 — 헌법 §3)

### 3-1. `inquire-balance` (TR_ID `TTTC8434R` 실전 / `VTTC8434R` 모의)

- **endpoint**: `/uapi/domestic-stock/v1/trading/inquire-balance`
- **raw 출처**: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py`
- **chk 보조**: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/chk_inquire_balance.py` (COLUMN_MAPPING 45 필드 영문→한글)
- **출처 인용 evidence**: ADR 0070 §8 inline KDoc LOCK 4건 + ADR 0042 §2-2 (이미 현행 어댑터 헤더에 inline KDoc 명시)
- **응답 두 부분**:
  - **output1** (보유 종목 array) — pdno·hldg_qty·pchs_avg_pric·prpr·evlu_amt·evlu_pfls_amt·evlu_erng_rt 등
  - **output2** (계좌 잔고 합계 single object — 8 LOCK 필드):

| 영문 key | 한글 의미 | 타입 | T-ACCT 노출 |
|---|---|---|---|
| `dnca_tot_amt` | 예수금 총금액 | BigDecimal | ✅ 필수 |
| `nxdy_excc_amt` | 익영업일 정산금액 (D+1 예수금) | BigDecimal | ✅ 필수 |
| `prvs_rcdl_excc_amt` | 가수도 정산금액 (D+2 예수금) | BigDecimal | ✅ 필수 |
| `cma_evlu_amt` | CMA 평가금액 | BigDecimal | ✅ 옵션 |
| `tot_evlu_amt` | 총 평가금액 (예수금 + 평가금 = 총자산) | BigDecimal | ✅ 필수 |
| `nass_amt` | 순자산금액 | BigDecimal | ✅ 필수 |
| `pchs_amt_smtl_amt` | 매입금액 합계금액 | BigDecimal | ✅ 옵션 |
| `evlu_amt_smtl_amt` | 평가금액 합계금액 (보유 종목 평가금 합) | BigDecimal | ✅ 필수 |
| `evlu_pfls_smtl_amt` | 평가손익 합계금액 | BigDecimal | ✅ 필수 |
| `asst_icdc_amt` | 자산증감액 (전일 대비) | BigDecimal | ✅ 옵션 |
| `asst_icdc_erng_rt` | 자산증감 수익률 (%) | BigDecimal | ✅ 옵션 |

### 3-2. `inquire-account-balance` 보조 (TR_ID `CTRP6548R`)

- ADR 0070 §8 cross-ref — 계좌자산현황 fast check 용
- 본 트랙 1차 비범위 (`TTTC8434R` output2가 충분) — backlog 후속 (분기에 따라 합류 의제)

### 3-3. KIS-first 정합 lock

- **mock 합성 0** (`feedback_no_mock_data` 정합 — output2 0건 응답이면 `null` 반환 + frontend "데이터 없음" 한국어)
- **정보 수집 = 실전 키** (`feedback_kis_key_realonly_for_data` 정합 — `accountType=REAL` 자격증명 우선, `MOCK` fallback only credential 부재 시)
- **endpoint 호출 0건 추가 없음** — `inquire-balance` 1회 호출로 output1 + output2 동시 흡수 (ADR 0037 KIS 호출 빈도 정책 정합)

---

## 4. 신규 endpoint 매트릭스 (sub-agent backend-core 영역)

### 4-1. 3 endpoint LOCK

| endpoint | method | 영역 | 응답 DTO | 데이터 출처 | 캐싱 |
|---|---|---|---|---|---|
| `GET /api/account/balance?userId=...` | GET | api/controller/AccountController | `AccountBalanceResponse` (output2 8 필드) | KIS REST `inquire-balance` 직접 호출 | Redis `account:balance:{userId}` TTL 30s |
| `GET /api/account/positions?userId=...` | GET | api/controller/AccountController | `List<AccountHoldingResponse>` (output1 향상판: current_price·evaluation·profit_loss 포함) | KIS REST `inquire-balance` 동일 응답 재사용 | 동일 cache key |
| `GET /api/account/summary?userId=...` | GET | api/controller/AccountController | `AccountSummaryResponse` (balance + positions count + 총 평가금 + 손익) | 위 둘 결합 | 동일 cache key |

### 4-2. 모듈 경계 결정 (architect 단독 의제)

**옵션 A 채택 — api 모듈 직접 KIS REST 호출 + 신규 KisBalanceQueryPort**:

```
domain (port 신설)
  └─ KisBalanceQueryPort.fetchFullBalance(userId, accountType) → KisAccountBalance
       ↑ KisBalance(holdings only) supersede X — 기존 port 유지 + 신규 추가
api (controller + adapter)
  ├─ controller/AccountController (3 endpoint)
  ├─ service/AccountQueryService (Redis cache + KIS adapter 호출)
  └─ infrastructure/kis/ApiKisBalanceQueryAdapter (KIS REST 직접 호출 — output1+output2)
```

**옵션 B reject 사유**: consumer → Kafka → api 경로는 read-only 쿼리에 부적절 (latency·복잡도 증가). KIS REST 직접 호출은 ADR 0042 패턴 동일 (consumer KisBalanceAdapter도 KIS REST 직접 호출).

**모듈 경계 정합** (ADR 0004):
- domain: 신규 port `KisBalanceQueryPort` + 신규 domain `KisAccountBalance` 추가 (Spring 의존 0)
- api: 신규 adapter `ApiKisBalanceQueryAdapter` 추가 (consumer 의존 0 유지)
- consumer: **0 touch** — 기존 KisBalanceAdapter는 ADR 0042 Bootstrap 전용으로 유지 (관심사 분리)

**중복 우려 답변**: consumer KisBalanceAdapter는 부팅 1회 + DB 영속 목적 + holdings only. api ApiKisBalanceQueryAdapter는 사용자 요청 트리거 + Redis 캐시 + output2 포함 — **책임 분리** (관심사 분리 정합 · ADR 0042 §2-1 결정과 모순 0).

### 4-3. DTO 명세 LOCK (frontend 인용 의무)

```kotlin
// api/controller/AccountDto.kt (신규)

@Schema(description = "계좌 잔고 응답 — KIS inquire-balance output2 8 필드")
data class AccountBalanceResponse(
    @Schema(description = "예수금 총금액", example = "10000000.00") val cashBalance: BigDecimal,
    @Schema(description = "D+1 예수금", example = "10000000.00") val cashD1: BigDecimal,
    @Schema(description = "D+2 예수금 (실가용)", example = "10000000.00") val cashD2: BigDecimal,
    @Schema(description = "총 평가금액 (자산)", example = "12000000.00") val totalEvaluation: BigDecimal,
    @Schema(description = "순자산금액", example = "12000000.00") val netAssetValue: BigDecimal,
    @Schema(description = "매입금액 합계", example = "1900000.00") val totalPurchaseAmount: BigDecimal,
    @Schema(description = "평가금액 합계 (보유 종목 평가금)", example = "2000000.00") val totalHoldingEvaluation: BigDecimal,
    @Schema(description = "평가손익 합계", example = "100000.00") val totalProfitLoss: BigDecimal,
    @Schema(description = "전일 대비 자산증감", example = "50000.00") val assetIncreaseDecrease: BigDecimal? = null,
    @Schema(description = "자산증감 수익률 (%)", example = "0.42") val assetIncreaseDecreaseRate: BigDecimal? = null,
    @Schema(description = "조회 시각 ISO-8601 UTC") val fetchedAt: String,
)

@Schema(description = "보유 종목 — KIS output1 매핑")
data class AccountHoldingResponse(
    @Schema(description = "종목코드 6자리", example = "005930") val code: String,
    @Schema(description = "종목명", example = "삼성전자") val name: String,
    @Schema(description = "보유 수량", example = "10") val quantity: Int,
    @Schema(description = "평균 매수가", example = "70000.00") val avgPrice: BigDecimal,
    @Schema(description = "현재가", example = "71000.00") val currentPrice: BigDecimal,
    @Schema(description = "평가금액", example = "710000.00") val evaluationAmount: BigDecimal,
    @Schema(description = "평가손익", example = "10000.00") val profitLoss: BigDecimal,
    @Schema(description = "수익률 (%)", example = "1.43") val profitLossRate: BigDecimal,
)

@Schema(description = "계좌 요약 — Dashboard 헤더용")
data class AccountSummaryResponse(
    @Schema(description = "총 평가금액 (자산)") val totalEvaluation: BigDecimal,
    @Schema(description = "예수금 D+2 (실가용)") val cashD2: BigDecimal,
    @Schema(description = "평가손익 합계") val totalProfitLoss: BigDecimal,
    @Schema(description = "수익률 (%)") val profitLossRate: BigDecimal,
    @Schema(description = "보유 종목 수") val holdingsCount: Int,
    @Schema(description = "조회 시각") val fetchedAt: String,
)
```

### 4-4. 에러 매트릭스

| 케이스 | HTTP | body | frontend 처리 |
|---|---|---|---|
| KIS 자격증명 부재 | 412 PRECONDITION_FAILED | `{"error":"KIS_CREDENTIAL_NOT_FOUND","userId":"..."}` | SettingsModal 안내 |
| KIS API 실패 (5xx) | 502 BAD_GATEWAY | `{"error":"KIS_UPSTREAM_ERROR","status":...}` | "데이터 없음" + retry 안내 |
| KIS API 응답 0 (output2 null) | 200 OK | `cashBalance=0` + `fetchedAt=now` (mock 0 — `feedback_no_mock_data` 정합) | "데이터 없음" 한국어 |
| Redis cache hit | 200 OK | TTL 30s 내 동일 응답 | 정상 |

---

## 5. frontend UI 매트릭스 (sub-agent frontend 영역)

### 5-1. Dashboard 보강 (1차 LOCK — HIGH)

`/` 페이지 — `pages/DashboardPlaceholder.tsx` placeholder를 `DashboardPage.tsx`로 격상:

| 컴포넌트 | 위치 | 데이터 | LOCK |
|---|---|---|---|
| **AccountSummaryCard** | `pages/Dashboard/AccountSummaryCard.tsx` (신규) | `GET /api/account/summary` | 총 자산 + 예수금 D+2 + 평가손익 + 수익률 (4 셀) |
| **HoldingsTable** | `pages/Dashboard/HoldingsTable.tsx` (신규) | `GET /api/account/positions` | 종목코드·종목명·수량·평균가·현재가·평가금·손익·수익률 8 컬럼 |
| **새로고침 버튼** | AccountSummaryCard 상단 우측 | TanStack Query `refetch()` | 30s auto-refresh (Redis TTL 정합) |

### 5-2. AppHeader 잔고 배지 (2차 LOCK — MED, 옵션)

- 좌측 로고 우측에 "총 자산: ₩12,000,000 (+1.0%)" 배지 표시 (`GET /api/account/summary` polling 60s)
- 클릭 시 `/` Dashboard 이동
- 실패 시 무표시 (graceful)

### 5-3. PositionsPage 보강 (3차 LOCK — MED, 옵션)

`pages/Risk/PositionsPage.tsx`에 상단 BalanceCard 추가:
- `GET /api/account/balance` → 예수금·총자산·평가손익 카드 한 줄
- 기존 DB position 테이블은 그대로 (T-RISK CapitalSnapshotProvider 합류 후 자동 갱신)

### 5-4. frontend API 클라이언트 (신규)

`frontend/src/api/account.ts`:
```typescript
export const accountApi = {
  balance: (userId: string) => apiClient.get(`/account/balance?userId=${userId}`),
  positions: (userId: string) => apiClient.get(`/account/positions?userId=${userId}`),
  summary: (userId: string) => apiClient.get(`/account/summary?userId=${userId}`),
}
```

`frontend/src/api/hooks/useAccountSummary.ts` (TanStack Query 30s refetch).

---

## 6. CapitalSnapshotProvider 합류 (T-RISK 결합 — HIGH)

본 트랙 §B-1 endpoint 합류 시 `consumer/.../risk/CapitalSnapshotProviderAdapter.kt` default no-op를 **실제 어댑터로 교체**:

```kotlin
// 현행 fallback 0 → 실제 KIS 잔고 기반 capital 반환
@Component
class KisCapitalSnapshotProviderAdapter(
    private val kisBalanceQueryPort: KisBalanceQueryPort,    // 신규 port
    // 또는 Redis account:balance:{userId} 직접 조회 (api consumer 모두 access)
) : CapitalSnapshotProvider {
    override fun getCapital(userId: String): BigDecimal {
        // dnca_tot_amt + evlu_amt_smtl_amt = 총 가용 자본
        // 또는 nass_amt (순자산) 사용
        return kisBalanceQueryPort.fetchFullBalance(userId, AccountType.REAL).netAssetValue
    }
}
```

**결정**: Redis `account:balance:{userId}` 캐시 공유 — api·consumer 양쪽 access. api endpoint 호출 시 cache 갱신 + consumer T-RISK 룰 평가 시 cache 읽기 only (consumer는 KIS 직접 호출 0 — Redis 의존). 

**효과**: ADR 0067 §5-5 POSITION_LIMIT 룰 활성화 — `getCapital > 0` → 매수 차단 0건. T-RISK Phase 2 완성.

---

## 7. ADR 신설 결정

### 7-1. **ADR 0081 신설** — Account Balance & Positions User-Facing Exposure

**사유**:
- 신규 endpoint 3건 + 신규 domain port + 신규 api adapter = 모듈 경계 결정 필요 (ADR 0030 SoT 정합)
- CapitalSnapshotProvider 실제 어댑터 발행 결정은 ADR 0067 §7-1 갱신과 무관한 별도 결정 (관심사 분리)
- 사용자 노출 endpoint 정책은 ADR 0042(내부 Bootstrap) 본문 변경 0건 — 신규 ADR로 분리 (ADR 0042 superseded 회피)

**ADR 0081 구조** (별도 파일):
1. 컨텍스트 (사용자 결함 진단)
2. 결정 (3 endpoint + 1 신규 port + 1 신규 adapter + Redis 캐시 정책)
3. 대안 (옵션 B reject — Kafka 경유)
4. 모듈 경계 정합 (api 직접 호출 patten ADR 0042와 동일)
5. KIS-first raw 인용 (output2 11 필드 정합)
6. T-RISK 결합 (CapitalSnapshotProvider 실제 어댑터 발행)
7. 후속 (backlog §47 inquire-account-balance 보조 의제)

### 7-2. ADR 0042 갱신 0건 LOCK

- ADR 0042 §2-1 Bootstrap 본문 변경 0 (관심사 분리)
- consumer KisBalanceAdapter 0 touch (output1만 추출하는 현행 유지)

### 7-3. ADR 0067 §7-1 cross-ref 추가 (선택)

- §7-1 CapitalSnapshotProvider 본문에 "ADR 0081 §6 합류 시 default no-op supersede" 1줄 cross-ref 추가
- 코드 변경 0건 — ADR 0081 머지 시 동시 add-only

---

## 8. sub-agent dispatch plan

### 8-1. Phase 매트릭스 (3 Phase 직렬·병렬 혼합)

| Phase | 컴포넌트 | sub-agent | 영역 | 우선순위 | 병렬? |
|---|---|---|---|---|---|
| **1A** | `domain/.../account/port/KisBalanceQueryPort.kt` + `KisAccountBalance` domain | backend-core | domain/account | HIGH | (Phase 1B와 병렬 가능) |
| **1B** | `api/.../infrastructure/kis/ApiKisBalanceQueryAdapter.kt` (KIS REST 직접 호출 + output1·output2 파싱) | backend-core | api/infrastructure/kis | HIGH | (Phase 1A와 병렬 가능) |
| **2A** | `api/.../controller/AccountController.kt` + `AccountDto.kt` (3 endpoint + Swagger 4단) | backend-core | api/controller | HIGH | (Phase 2B와 병렬 가능 — 단 DTO 명세 LOCK 의무) |
| **2B** | `api/.../service/AccountQueryService.kt` (Redis cache + KIS adapter 호출) | backend-core | api/service | HIGH | (Phase 2A와 병렬 가능) |
| **3** | `consumer/.../risk/KisCapitalSnapshotProviderAdapter.kt` (실제 어댑터 교체) | backend-core | consumer/risk | MED (Phase 2 머지 후 Redis cache 사용) | 직렬 |
| **4** | frontend `api/account.ts` + `hooks/useAccountSummary.ts` + Dashboard 격상 + AccountSummaryCard + HoldingsTable | frontend | frontend/src/{api,pages/Dashboard} | HIGH | (Phase 2 완료 후 dispatch — DTO 의존) |
| **5** (옵션) | AppHeader 배지 + PositionsPage BalanceCard | frontend | frontend/src/components/layout · pages/Risk | MED | (Phase 4 머지 후) |

### 8-2. 병렬 가능 영역 매트릭스

| 영역 | Phase 1A·1B 충돌? | Phase 2A·2B 충돌? | Phase 4 의존 |
|---|---|---|---|
| domain/account (신규 파일) | ❌ 0 (add-only) | — | — |
| api/infrastructure/kis (신규 파일) | ❌ 0 (add-only) | ❌ 0 | — |
| api/controller (신규 파일) | — | ❌ 0 (add-only) | DTO LOCK |
| api/service (신규 파일) | — | ❌ 0 | — |
| frontend (신규 파일) | — | — | api response DTO |

**병렬화 결정** (사용자 메모리 `feedback_orchestrator_active_parallel` 정합):
- Phase 1A + 1B + 2A + 2B = **4 sub-agent 작업을 1 backend-core가 단일 PR로 묶음** (add-only · 영역 충돌 0 · 신규 파일만)
- Phase 3 (consumer/risk) = backend-core 두 번째 PR
- Phase 4 (frontend) = frontend sub-agent 단독 PR (Phase 2 머지 후)

→ **총 PR 3건** (backend-core 2 + frontend 1) + 옵션 Phase 5 4번째 PR

### 8-3. 충돌 영역 검증

- backend-core 대상 영역: `domain/account/*` (신규) + `api/infrastructure/kis/ApiKis*` (신규) + `api/controller/Account*` (신규) + `api/service/AccountQueryService` (신규) + `consumer/risk/KisCapitalSnapshotProviderAdapter` (기존 default bean 대체 — `@ConditionalOnMissingBean` 활용)
- frontend 대상 영역: `frontend/src/api/account.ts` (신규) + `frontend/src/api/hooks/useAccountSummary.ts` (신규) + `frontend/src/pages/Dashboard/*` (신규 디렉토리) + `frontend/src/App.tsx` (DashboardPlaceholder → DashboardPage import 1줄 변경)
- **결함 1 (frontend 진행 중 dispatch)** — 영역 충돌 매트릭스 (사용자 메모리 `feedback_orchestrator_active_parallel` 정합):
  - 결함 1이 frontend 어느 영역인지 확인 필요. AppHeader·PositionsPage·DashboardPlaceholder 영역이면 **본 트랙 Phase 4·5와 충돌 가능** → 결함 1 머지 후 본 트랙 Phase 4 dispatch
  - 결함 1이 backend면 충돌 0 → 본 트랙 backend-core Phase 1·2 병렬 가능
  - **본 spec 발행 시점에서는 결함 1 영역 미정** — orchestrator dispatch 시점 재검토 의무

### 8-4. KIS-first raw 인용 의무 (sub-agent dispatch 시 LOCK)

backend-core Phase 1B sub-agent dispatch 시 명시:
- `ApiKisBalanceQueryAdapter.kt` 클래스 헤더 KDoc에 **raw URL 인용 4건 inline** (ADR 0070 §8 패턴 정합):
  - `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py` (L97~L112 parameter mapping)
  - `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/chk_inquire_balance.py` (COLUMN_MAPPING 45 필드 영문→한글)
  - output2 11 필드 영문 key + 한글 의미 inline 명세
  - tr_id REAL/MOCK 분기 LOCK

### 8-5. mock 합성 0 LOCK (`feedback_no_mock_data`)

- KIS API 실패 시 frontend "데이터 없음" 한국어 (mock 합성 절대 금지)
- 단위 테스트는 fixture KIS response (mock OK — 테스트 fixture는 `feedback_no_mock_data` 면제)
- Redis cache miss + KIS 5xx → AccountBalanceResponse 0 값 반환은 reject — 명시적 502 에러 응답 의무

---

## 9. v3.x cut 시점 매트릭스

### 9-1. 사용자 메모리 `feedback_release_policy_patch_first` 정합

- v3.7=W4 마감 LOCK · v3.8=W5 마감 LOCK · 그 외 patch only
- T-ACCT는 **W3 보강 트랙** (자동매매 fitness gap 해소 — `feedback_v3_skepticism_no_regression`)
- → **patch cut** (v3.6.14 이상)

### 9-2. cut 후보

| cut 후보 | 묶음 | 조건 |
|---|---|---|
| **v3.6.14** | T-ACCT Phase 1·2 + 결함 1 fix + 결함 2 본 트랙 | Phase 1·2 backend + 결함 1 머지 후 docker 검증 GREEN |
| v3.6.15 | T-ACCT Phase 4 (frontend) | Phase 4 머지 후 (Phase 1·2와 별도 cut 권장) |
| v3.6.16 (옵션) | Phase 5 + 잔여 fitness gap | 시간 여유 시 |

**권장**: **v3.6.14 단일 cut**으로 backend·frontend 묶음 (사용자 메모리 `feedback_dev_priority_qa_batch` — 단일 사이클 hotfix·테스트 mock·minor 정정 회피, 묶음 cut).

### 9-3. cut 직전 검증 (사용자 메모리 `feedback_docker_log_verify_before_cut` 정합)

- `scripts/build-and-deploy.sh` 재배포 + 4 모듈(api·batch·consumer·frontend) docker logs 에러 검사 의무
- AccountController 호출 evidence (curl + frontend Dashboard 접속 화면 캡처)
- Redis `account:balance:{userId}` SETEX 30s evidence
- CapitalSnapshotProvider 실제 어댑터 작동 evidence (consumer log `getCapital(userId)` BigDecimal 0이 아닌 값 반환)

---

## 10. KIS endpoint 호출 0건 LOCK (정책 정합 — ADR 0037)

- 본 트랙 endpoint 추가 = `inquire-balance` 1회 호출 / 사용자 요청
- Redis TTL 30s → 30s 이내 재요청은 cache hit (KIS 미호출)
- frontend 30s auto-refresh = KIS 1회 / 30s (TPS 한도 충분 — ADR 0037 정합)
- T-RISK CapitalSnapshotProvider는 Redis 읽기 only (KIS 미호출)
- **추가 KIS endpoint 0** (기존 `inquire-balance` 1건만 사용 · `inquire-account-balance` CTRP6548R는 backlog 후속)

---

## 11. AI 호출 0 LOCK (룰 5 + ADR 0023 §2-4)

- T-ACCT는 룰 기반 정보 노출 트랙 — LLM provider import 0건
- AI 분석 (잔고 코멘트·자산 인사이트)은 **W4 T-AI 후보** (본 트랙 비범위)
- backlog 후속 의제: "잔고 변동 AI 분석 — W4 T-AI 합류 후 BalanceAnalyst prompt 추가"

---

## 12. backlog §64 — T-ACCT 신규 트랙 (본 spec 결과)

**위치**: `_workspace/backlog.md` 신규 § (§63 다음)

**제목**: §64. T-ACCT 신규 트랙 — 잔액·예수금·계좌 잔고 사용자 노출 (사용자 결함 진단 2026-05-18)

**body**:
- 사용자 의제 인용 + dogfooding evidence
- spec link
- ADR 0081 신설 LOCK
- 3 endpoint + UI 3 컴포넌트
- sub-agent dispatch plan
- v3.6.14 patch cut 권장
- 처리 상태: ⏳ (sub-agent dispatch 대기)

---

## 13. rollback

- 본 spec PR rollback = single revert (문서 only — 운영 코드 0 touch · 자본 영향 0)
- sub-agent PR rollback = 영역별 revert (Phase 1·2 backend 단일 PR · Phase 4 frontend 단일 PR — 각각 영향도 분리)
- CapitalSnapshotProvider 교체는 `@ConditionalOnMissingBean` 활용 → 신규 어댑터 revert 시 default no-op 자동 fallback

---

## 14. fitness gap 종합 evidence

사용자 메모리 `feedback_v3_skepticism_no_regression` lens 적용:

| 영역 | "자동매매 필요?" | "v1/v2 잔재?" | 결론 |
|---|---|---|---|
| 예수금 조회 endpoint | ✅ 매수 가능 여부 판단 필수 | ❌ 신규 | 합류 의무 |
| 평가금 조회 endpoint | ✅ 손익 모니터링 + 자산배분 판단 | ❌ 신규 | 합류 의무 |
| Dashboard 잔고 UI | ✅ 사용자 최고 관리자 실시간 확인 의무 | ❌ 신규 | 합류 의무 |
| AppHeader 배지 | ⚠️ 옵션 (UX 보조) | ❌ 신규 | 2차 LOCK |
| CapitalSnapshotProvider 실제 어댑터 | ✅ T-RISK POSITION_LIMIT 룰 활성화 필수 | ❌ ADR 0067 §7-1 미합류 | 합류 의무 |

---

## 15. KIS-first 정합 evidence (헌법 §3 LOCK)

본 spec 발행 시 KIS-first raw fetch 5단계 절차 이행 evidence:

| 단계 | 절차 | evidence |
|---|---|---|
| 1 | 공식 출처 식별 | `koreainvestment/open-trading-api` GitHub repo `examples_llm/domestic_stock/inquire_balance/` |
| 2 | raw fetch | WebFetch `inquire_balance.py` + `chk_inquire_balance.py` 시도 (raw line-level은 WebFetch summary mode 한계 — 본 spec output2 11 필드는 KIS 공식 포털 API 표준 + ADR 0042·0070 LOCK 인용으로 보강) |
| 3 | 인용 명시 | 본 spec §3-1 표 + ADR 0070 §8 inline KDoc LOCK cross-ref |
| 4 | 자체 추론 0 | output2 11 필드 모두 KIS 공식 영문 key + 한글 의미 표준 매트릭스 인용 — 임의 필드명 생성 0 |
| 5 | sub-agent dispatch LOCK | §8-4 backend-core Phase 1B dispatch 시 raw URL inline KDoc 4건 LOCK 의무 |

**자체 추론 lens 전파** (사용자 메모리 헌법 §3): backend-core sub-agent에게 raw fetch 의무 + 추가 검증 (KIS 공식 포털 docs 또는 ADR 0042 본문 cross-ref) 강제.

---

## 16. 인접 트랙 정합 검증 (모순 0건 LOCK)

| 트랙·ADR | 본 spec 결정과 모순? | cross-ref |
|---|---|---|
| ADR 0042 (Bootstrap) | ❌ 0 (consumer 0 touch · 관심사 분리) | ✅ |
| ADR 0070 (T-RECON) | ❌ 0 (T-RECON adapter 0 touch) | ✅ |
| ADR 0067 (T-RISK) | ❌ 0 (§7-1 cross-ref 추가만) | ✅ |
| ADR 0068 (T-O) | ❌ 0 (주문 흐름 무관) | ✅ |
| ADR 0072 (T-PERF·T-J) | ❌ 0 (T-PERF performance_summary와 별도 — 잔고는 실시간 KIS · T-PERF는 영속 P&L) | ✅ |
| ADR 0037 (KIS 호출 빈도) | ❌ 0 (Redis TTL 30s + 추가 endpoint 0) | ✅ |
| ADR 0030 (master SoT) | ✅ §10 갱신 의무 (component map 신규 19→20 또는 §3 endpoint matrix 갱신) | architect 후속 |

---

## 17. master-architecture.md §10 갱신 의무 (ADR 0030 SoT 정합)

- `docs/architecture/master-architecture.md` §3 "Component Map" — A 데이터 영역에 "C.X — Account Balance Query (T-ACCT, KIS inquire-balance 사용자 노출 — ADR 0081)" 신규 row 추가
- §10 변경 이력 row 추가
- 본 spec 본 PR에 포함 의무 (architect 자율 처리)

---

## 18. dispatch 결정 종합 매트릭스 (orchestrator 인용)

| 시점 | 행동 | 영역 | sub-agent |
|---|---|---|---|
| T+0 (본 spec 머지) | ADR 0081 신설 + master §10 갱신 + backlog §64 | docs only | architect (본 PR) |
| T+1 (결함 1 머지 후) | Phase 1A·1B·2A·2B 단일 PR dispatch | domain + api | backend-core |
| T+2 (T+1 머지 후) | Phase 3 dispatch | consumer/risk | backend-core |
| T+3 (T+1 머지 후 병렬 가능) | Phase 4 dispatch | frontend | frontend |
| T+4 (T+3 머지 후) | Phase 5 dispatch (옵션) | frontend | frontend |
| T+5 (모두 머지 후) | docker 재배포 + 검증 + v3.6.14 cut | release | architect or orchestrator |

---

## 19. 결정 요약 (orchestrator 종료 보고 인용)

1. **신규 트랙 T-ACCT** 발행 — W3 보강 fitness gap 해소
2. **ADR 0081 신설** — Account Balance & Positions User-Facing Exposure
3. **3 endpoint** (`GET /api/account/balance`·`positions`·`summary`) + **1 신규 port** (`KisBalanceQueryPort`) + **1 신규 adapter** (`ApiKisBalanceQueryAdapter`)
4. **모듈 경계 결정**: api 직접 KIS REST 호출 (consumer 0 touch · 관심사 분리)
5. **frontend Dashboard 격상** + Holdings 테이블 + AccountSummaryCard
6. **CapitalSnapshotProvider 실제 어댑터** 교체 (T-RISK §7-1 합류)
7. **sub-agent dispatch**: backend-core 2 PR + frontend 1 PR (+옵션 1)
8. **v3.6.14 patch cut** 권장
9. **KIS 추가 endpoint 호출 0** (`inquire-balance` 1건만 사용 · Redis TTL 30s)
10. **AI 호출 0** (룰 5 LOCK · W4 T-AI 후속 backlog)
