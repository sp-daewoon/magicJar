# ADR 0081 — Account Balance & Positions User-Facing Exposure

- 상태: Proposed (spec 발행 단계 — Phase 1·2 머지 후 Accepted 격상)
- 작성일: 2026-05-18
- 결정자: architect (사용자 결함 진단 위임 — "지금 잔액조회 예수금 조회 이런게 아예 없는데?" 2026-05-18 14:50 KST)
- 관계:
  - **선행**: ADR 0042 (Position Bootstrap — consumer KisBalanceAdapter holdings only) · ADR 0070 (T-RECON KisReconcileBalanceAdapter) · ADR 0067 (T-RISK §7-1 CapitalSnapshotProvider Phase 2 미합류)
  - **공존**: ADR 0030 (master SoT) · ADR 0037 (KIS 호출 빈도)
  - **후행**: ADR 0067 §7-1 cross-ref add-only (CapitalSnapshotProvider 실제 어댑터 발행)
- spec 본문: `_workspace/specs/2026-05-18-account-balance-positions-spec.md`
- 사용자 메모리: `feedback_v3_skepticism_no_regression` · `feedback_kis_key_realonly_for_data` · `feedback_no_mock_data` · `project_system_vision`

---

## 1. 컨텍스트 — 왜 이 결정이 필요한가

사용자 (최고 관리자, 2026-05-18 14:50 KST) dogfooding 결함:

> "지금 잔액조회 예수금 조회 이런게 아예 없는데?"

orchestrator + architect 진단:
- ADR 0042 Bootstrap용 `KisBalanceAdapter` 존재하나 **사용자 노출 endpoint 0건**
- output1 (보유 종목) 만 파싱 — **output2 (계좌 잔고 합계 = 예수금·평가금·평가손익) 완전 누락**
- frontend `DashboardPlaceholder.tsx` placeholder only — 잔고 UI 0건
- ADR 0067 §7-1 CapitalSnapshotProvider Phase 2 미합류 → `getCapital(userId) → 0` → POSITION_LIMIT 룰 무력
- 사용자 메모리 `feedback_v3_skepticism_no_regression` lens — **자동매매에 필요** + v1/v2 잔재 아닌 신규 fitness gap

**필요한 결정**:
- 사용자 노출 endpoint 3건 신설
- 모듈 경계 (api 직접 KIS REST 호출 vs consumer 경유)
- output2 11 필드 명세 LOCK
- frontend Dashboard UI 격상 정책
- T-RISK CapitalSnapshotProvider 실제 어댑터 합류 정책
- Redis 캐시 TTL 정책

## 2. 결정

### 2-1. 3 endpoint LOCK

| endpoint | DTO | 캐시 |
|---|---|---|
| `GET /api/account/balance?userId=...` | `AccountBalanceResponse` (output2 11 필드) | Redis `account:balance:{userId}` TTL 30s |
| `GET /api/account/positions?userId=...` | `List<AccountHoldingResponse>` (output1 향상판) | 동일 cache 재사용 |
| `GET /api/account/summary?userId=...` | `AccountSummaryResponse` (Dashboard 헤더용) | 동일 cache 재사용 |

### 2-2. 모듈 경계

**채택**: **api 모듈 직접 KIS REST 호출 + 신규 KisBalanceQueryPort + 신규 ApiKisBalanceQueryAdapter**

```
domain/account/port/KisBalanceQueryPort.kt              [신규 port — Spring 의존 0]
domain/account/KisAccountBalance.kt                     [신규 domain — output1·output2 통합]
api/controller/AccountController.kt                     [신규 — 3 endpoint Swagger 4단]
api/controller/AccountDto.kt                            [신규 — DTO 3건]
api/service/AccountQueryService.kt                      [신규 — Redis 캐시 + KIS adapter 호출]
api/infrastructure/kis/ApiKisBalanceQueryAdapter.kt    [신규 — KIS REST inquire-balance 직접 호출]
consumer/risk/KisCapitalSnapshotProviderAdapter.kt     [신규 — Redis cache 읽기 only]
```

**reject 옵션 B (Kafka 경유)** 사유:
- read-only 쿼리에 부적절 (latency·복잡도 증가)
- ADR 0042 Bootstrap도 KIS REST 직접 호출 패턴 동일 → 모듈 경계 정합 0 모순
- T-RISK consumer는 Redis 캐시 읽기만 — KIS 직접 호출 0

### 2-3. consumer KisBalanceAdapter 0 touch

- ADR 0042 Bootstrap 전용으로 유지 (부팅 1회 + holdings only)
- 본 트랙 ApiKisBalanceQueryAdapter는 사용자 요청 트리거 + 캐시 + output2 포함
- **관심사 분리** — 책임 다른 두 adapter (Bootstrap 영속 vs 실시간 쿼리)
- ADR 0042 §2-1 본문 변경 0건

### 2-4. T-RECON KisReconcileBalanceAdapter 0 touch

- ADR 0070 §8 LOCK 정합 — holdings 비교만 의무
- T-RECON은 cron 기반 + 페이징 흡수 + diff 검출 전용
- 본 트랙과 책임 영역 분리

### 2-5. CapitalSnapshotProvider 실제 어댑터 발행

ADR 0067 §7-1 default no-op (fallback 0) → `KisCapitalSnapshotProviderAdapter` (Redis 캐시 읽기) 교체:

```kotlin
@Component
class KisCapitalSnapshotProviderAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : CapitalSnapshotProvider {
    override fun getCapital(userId: String): BigDecimal {
        // Redis account:balance:{userId} → AccountBalanceResponse → netAssetValue
        val json = redisTemplate.opsForValue().get("account:balance:$userId") ?: return BigDecimal.ZERO
        return objectMapper.readValue<AccountBalanceResponse>(json).netAssetValue
    }
}
```

**효과**: ADR 0067 §5-5 POSITION_LIMIT 룰 활성화 — `getCapital > 0` → 매수 차단 0건. T-RISK Phase 2 완성.

**`@ConditionalOnMissingBean` 정합**: 기존 default no-op는 `@ConditionalOnMissingBean(CapitalSnapshotProvider::class)` — 본 어댑터가 등록되면 default 자동 비활성. revert 시 default 자동 fallback (graceful).

### 2-6. output2 11 필드 LOCK (KIS-first raw fetch 의무)

`inquire-balance` (TR_ID `TTTC8434R` 실전 / `VTTC8434R` 모의) output2 응답:

| 영문 key | 한글 의미 | DTO 필드 |
|---|---|---|
| `dnca_tot_amt` | 예수금 총금액 | cashBalance |
| `nxdy_excc_amt` | D+1 예수금 | cashD1 |
| `prvs_rcdl_excc_amt` | D+2 예수금 (실가용) | cashD2 |
| `cma_evlu_amt` | CMA 평가금액 | (옵션) |
| `tot_evlu_amt` | 총 평가금액 (자산) | totalEvaluation |
| `nass_amt` | 순자산금액 | netAssetValue |
| `pchs_amt_smtl_amt` | 매입금액 합계 | totalPurchaseAmount |
| `evlu_amt_smtl_amt` | 평가금액 합계 | totalHoldingEvaluation |
| `evlu_pfls_smtl_amt` | 평가손익 합계 | totalProfitLoss |
| `asst_icdc_amt` | 자산증감액 (전일 대비) | assetIncreaseDecrease (옵션) |
| `asst_icdc_erng_rt` | 자산증감 수익률 (%) | assetIncreaseDecreaseRate (옵션) |

raw 출처:
- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py`
- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/chk_inquire_balance.py` (COLUMN_MAPPING 45 필드)
- ADR 0070 §8 inline KDoc LOCK cross-ref (현행 어댑터 KDoc 정합)

**sub-agent dispatch 시 LOCK**: backend-core Phase 1B `ApiKisBalanceQueryAdapter.kt` 클래스 헤더 KDoc에 raw URL 4건 inline 의무.

### 2-7. mock 합성 0 LOCK (사용자 메모리 `feedback_no_mock_data`)

- KIS API 실패 시 → 502 BAD_GATEWAY (mock 합성 0)
- KIS API 성공 + output2 null → 400 또는 명시적 0 값 + `fetchedAt=now` (frontend "데이터 없음" 한국어 표시)
- 단위 테스트는 fixture KIS response (테스트 fixture는 `feedback_no_mock_data` 면제)

### 2-8. AI 호출 0 LOCK (룰 5)

- T-ACCT는 룰 기반 정보 노출 — LLM provider import 0건
- AI 잔고 분석은 W4 T-AI 후보 backlog

## 3. 대안 검토

### 3-1. 옵션 B — consumer → Kafka → api (Reject)

- 사유: read-only 쿼리에 부적절 (latency + 복잡도 증가)
- consumer KisBalanceAdapter는 부팅 1회 only — 사용자 요청 트리거에 부적합
- Kafka publish 경유 시 응답 latency 증가 (수십 ms vs ms 단위)

### 3-2. 옵션 C — consumer KisBalanceAdapter 확장 + api는 Redis 읽기만 (Reject)

- 사유: consumer는 부팅·cron·체결통보 전용 (사용자 요청 트리거 책임 외)
- 모듈 경계 흐림 — api endpoint가 consumer Redis 읽기에 의존하면 consumer 부팅 전 사용자 요청 시 cache miss → 명시적 에러 필요 → 복잡도 증가
- 옵션 A는 api가 자체 KIS 호출 + Redis 캐시 → 독립적 운영 가능

### 3-3. 옵션 D — `KisBalancePort` 확장 (output2 추가) (Reject)

- 사유: ADR 0042 Bootstrap 의존 — `KisBalance(holdings only)` 단순 인터페이스 유지가 부팅 흐름 정합
- 신규 port `KisBalanceQueryPort`로 분리 → 책임 명확화 (Bootstrap vs Query)
- 기존 port 호출자(PositionBootstrapRunner) 영향 0

## 4. 모듈 경계 정합

| 모듈 | 변경 | 영향 |
|---|---|---|
| domain | 신규 `account/` 패키지 + port + domain class | Spring 의존 0 유지 ✅ |
| api | 신규 controller + service + adapter | consumer 의존 0 유지 ✅ |
| consumer | 신규 risk adapter (Redis 읽기) | api 의존 0 유지 ✅ (Redis 직접 access — 모듈 경계 0 침범) |
| batch | 0 touch | — |
| frontend | 신규 api client + Dashboard 격상 | — |

ADR 0004 모듈 경계 LOCK 정합.

## 5. KIS endpoint 호출 정책

- 사용자 요청 / 30s = KIS `inquire-balance` 1회 (Redis TTL 30s)
- frontend auto-refresh 30s = 동일
- T-RISK consumer = Redis 읽기 only (KIS 미호출)
- **추가 KIS endpoint 0** (`inquire-balance` 1건만 사용)
- ADR 0037 KIS 호출 빈도 정책 정합

## 6. T-RISK 결합 (ADR 0067 §7-1 cross-ref)

- 본 ADR 합류 시 ADR 0067 §7-1 본문에 cross-ref 1줄 추가:
  > "ADR 0081 §2-5에서 `KisCapitalSnapshotProviderAdapter` 실제 어댑터 발행 — default no-op `@ConditionalOnMissingBean`으로 자동 supersede."
- ADR 0067 본문 결정 변경 0 (단순 cross-ref add-only)

## 7. 후속

| 의제 | backlog 위치 | 결정 시점 |
|---|---|---|
| `inquire-account-balance` (CTRP6548R) 보조 합류 | §47 cross-ref | 본 트랙 마감 후 |
| AI 잔고 변동 분석 prompt | §62 cross-ref (W4 T-AI) | W4 진입 후 |
| Account history 영속 (시계열 P&L) | backlog 신규 | T-PERF 결합 의제 |
| AppHeader 잔고 배지 (옵션) | spec §5-2 | Phase 4 머지 후 |

## 8. rollback

- 본 ADR rollback = ADR status `Proposed → Rejected` + 본 spec PR revert
- 실제 코드 합류 후 rollback = 영역별 PR revert (backend-core 2 PR + frontend 1 PR 각각 분리 가능)
- CapitalSnapshotProvider 교체 revert 시 default no-op 자동 fallback (`@ConditionalOnMissingBean`) — 자본 영향 0

## 9. 변경 이력

| 날짜 | 변경 | PR |
|---|---|---|
| 2026-05-18 | 본 ADR 신설 (Proposed) — 사용자 결함 진단 spec 발행 PR | (agent/architect/spec-account-balance-positions-track) |
