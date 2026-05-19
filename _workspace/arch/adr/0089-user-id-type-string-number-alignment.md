# ADR 0089: userId 타입 정합 — backend/frontend cross-cutting string ↔ number 불일치 영구 차단

- 상태: **Proposed (2026-05-19, Phase 1 backend-core + frontend 합작 PR 후 Accepted 격상 예정)**
- 작성일: 2026-05-19
- 결정자: architect (사용자 위임 — frontend PR #549 P2-F1 evidence 정합)
- 영역: 도메인 본체 + 프론트엔드 cross-cutting (§4 + §3)
- 관련: ADR 0010(Multi-tenant) · ADR 0046(Onboarding & Credential Registration) · ADR 0081(T-ACCT) · ADR 0082(T-MAN) · ADR 0006(PostgreSQL 단일)

## 1. 맥락

### 1-1. frontend PR #549 P2-F1 cross-validation evidence

frontend sub-agent system audit(PR #549) 2026-05-19 발굴 — **`userId` 타입 frontend cross-cutting 불일치 매트릭스**:

| 영역 | userId 타입 | evidence 파일 |
|------|-------------|---------------|
| account | `string` | `frontend/src/features/account/api/*.ts` (`HoldingsTable`·`BalanceCard` props) |
| order (T-MAN) | `string` | `frontend/src/features/order/api/*.ts` (`OrderPreviewModal`·`OrderNewPage`) |
| watchlist | `string` | `frontend/src/features/watchlist/api/*.ts` (관심종목 등록 endpoint) |
| journal (T-J) | `number` | `frontend/src/features/journal/api/*.ts` (`/api/journal?userId=<n>`) |
| performance (T-PERF) | `number` | `frontend/src/features/performance/api/*.ts` |
| screener (T-SC) | `number` | `frontend/src/features/screener/api/*.ts` |
| onboarding store | `number \| null` | `frontend/src/stores/useOnboardingStore.ts` `registeredUserId` |

backend 영역(Kotlin):
- 도메인 — `userId: Long` (`domain/user/UserId.kt` 또는 inline class 단순 Long alias)
- JPA entity — `user_account.id BIGINT PK` (Flyway baseline V1·V10·V14 정합)
- REST DTO — 일부 controller가 `Long`을 JSON 직렬화 시 number로 응답, 일부는 `userId: String` path variable로 받음

### 1-2. cross-cutting risk

1. **frontend store ↔ API 모순** — `useOnboardingStore.registeredUserId: number | null`인데 watchlist API가 `userId: string`을 path/query parameter로 요구 → `String(userId)` 캐스팅 누락 시 ApolloError/Axios 4xx 발생
2. **HTTP 직렬화 모순** — Jackson default Long → number(JS number max safe integer 2^53-1 미만이므로 BIGINT 안전권) vs string path variable mix → DTO 라운드트립 fail risk
3. **테스트 mismatch** — frontend MSW handler가 `userId: 1` (number)로 mock 응답 → 실제 backend가 `"1"` (string) 반환 시 화면 conditional rendering 결함
4. **DB FK 정합** — `user_account.id BIGINT` 권위 → frontend가 string으로 받아도 backend가 String→Long 변환 시 `NumberFormatException` risk (소수점·comma·non-digit)
5. **future MCP 노출** — Claude/Cursor client가 magicJar MCP 호출 시 user_id schema definition 불분명 → MCP spec 분기 risk (ADR 0027)

### 1-3. v1/v2 잔재 부재 검증

`feedback_v3_skepticism_no_regression` lens — v1/v2 코드 잔재 아님. v3.0.0 베이스라인 컷 후 sub-agent 영역별 발행한 모듈이 frontend/backend 일관 정합 표준 없이 자율 결정한 결과로 누적된 신규 결함.

## 2. 결정

### 2-1. 옵션 매트릭스

| 옵션 | 내용 | 채택 여부 |
|------|------|-----------|
| **A** | backend·frontend 모두 **`number`(Long → JS number)** 통일 | ★ **채택** |
| B | backend·frontend 모두 **`string`** 통일 (`@JsonSerialize(using = ToStringSerializer.class)`) | 거부 |
| C | backend Long 유지 + frontend 영역별 자율 유지 (현행) | 거부 (root cause 그대로) |
| D | 추상 ID brand type 도입 (`type UserId = number & { __brand: 'UserId' }`) | 거부 (frontend 학습 부담 ↑ MVP 부적합) |

### 2-2. 옵션 A 채택 사유

1. **DB BIGINT → JS number 자연 정합**: PostgreSQL `BIGINT` (8 byte signed) 최대값 9.22 × 10^18 vs JS `Number.MAX_SAFE_INTEGER` 9.0 × 10^15. magicJar는 단일 운영자 시스템(`user_account` row 수 < 10000 가정 — ADR 0010 멀티테넌트 baseline) → MAX_SAFE_INTEGER 무한 안전권. 미래 row 폭증(>10^15) risk 0.
2. **backend Kotlin `Long` 보존**: Jackson default 직렬화가 Long → number → frontend `number` 라운드트립 GREEN. 코드 변경 frontend 단독 (`string` 5+ 파일 → `number`).
3. **string serialization 부담 제거**: 옵션 B(string 통일) 채택 시 `@JsonSerialize(using = ToStringSerializer.class)` 모든 Long 필드에 annotation 추가 의무 + DB 조회 시 `String → Long` 변환 layer 추가 (불필요한 우회).
4. **MCP future spec 명확**: ADR 0027 MCP server 노출 시 JSON schema `"user_id": { "type": "integer", "format": "int64" }` 표준 정합(JSON Schema int64 = JS number).

### 2-3. 변경 매트릭스 — frontend 5+ 파일 type 정정

| 파일 | 변경 |
|------|------|
| `frontend/src/features/account/api/*.ts` | `userId: string` → `userId: number` (5 함수 signature) |
| `frontend/src/features/account/components/HoldingsTable.tsx` | props `userId: string` → `number` |
| `frontend/src/features/account/components/BalanceCard.tsx` | 동일 |
| `frontend/src/features/order/api/*.ts` (T-MAN) | `userId: string` → `userId: number` (`POST /api/orders/manual` payload) |
| `frontend/src/features/order/components/OrderPreviewModal.tsx` | props 정정 |
| `frontend/src/features/order/pages/OrderNewPage.tsx` | 동일 |
| `frontend/src/features/watchlist/api/*.ts` | `userId: string` → `number` (path variable encode 시 `${userId}` 그대로 number→string 변환 자연) |
| `frontend/src/stores/useOnboardingStore.ts` | `registeredUserId: number \| null` 유지 (이미 number — 정합 anchor) |
| MSW handler — `frontend/mocks/handlers/*.ts` | `userId: 1` (number mock) 유지 |

### 2-4. 변경 매트릭스 — backend DTO 검증

backend DTO 1:1 정합 검증(Phase 1 backend-core 책임):
- `AccountSummaryResponseDto.userId: Long` LOCK (이미 Long)
- `OrderManualRequestDto.userId: Long` LOCK
- REST controller path variable `@PathVariable userId: Long` 채택 — `@PathVariable userId: String` 발견 시 즉시 정정 의무
- Jackson `@JsonProperty("userId")` annotation 정합 검증 (Long → JSON number)

### 2-5. cross-validation 검증 절차

1. frontend Phase: `git grep -r "userId: string" frontend/src/` 0건 LOCK (단 path variable encoding `${userId}` template literal context는 number→string 자동 변환이므로 사용 가능 — type 선언만 number)
2. backend Phase: `git grep -r "@PathVariable.*userId.*String" api/` 0건 LOCK
3. 통합 검증: 매수 시그널 1건 → backend `/api/orders/manual` POST { userId: 1 (number) } → response { userId: 1, orderId: 123 } GREEN
4. DB 검증: `user_account.id` 1 row → frontend `useOnboardingStore.registeredUserId` 1 (number) → API 호출 매트릭스 5+ endpoint 라운드트립 GREEN

## 3. 결과

### 3-1. 긍정

- frontend cross-cutting drift 영구 차단 — userId 5+ 영역 단일 type
- backend DTO 1:1 정합 검증 path 명문화 (future ADR 0046 onboarding flow + ADR 0081 T-ACCT + ADR 0082 T-MAN 모두 영향)
- MCP future spec(ADR 0027) JSON schema int64 baseline 확보
- DB BIGINT FK 정합 일관 → `NumberFormatException` runtime risk 0

### 3-2. 부정

- frontend 5+ 파일 type signature 정정 사이클 1회 발생 (~50~80 LOC edit · type only · logic 0 변경)
- 미래 row 폭증 (>10^15 user 수) 시 string 통일 재논의 필요 — 그러나 magicJar 멀티테넌트 baseline(ADR 0010)이 단일 운영자 가정이므로 발동 risk 0

### 3-3. 후속

본 ADR 머지 후:
1. **Phase 1 backend-core 단일 PR** — backend DTO 1:1 정합 검증(`@PathVariable userId: Long` 강제 + Jackson 직렬화 검증 unit test 4건) ~50 LOC + 검증 test
2. **Phase 2 frontend 단일 PR** — frontend 5+ 파일 `userId: string` → `number` 정정 (logic 0 변경 · type only)
3. **Phase 3 (옵션)** — backend-core/frontend cross-review 사이클 1회 (chart-curator 보수 페르소나 lens + backend-core 진보 페르소나 lens)
4. 본 ADR Proposed → Accepted 격상(Phase 1·2 머지 evidence 누적 후)

## 4. 대안 검토

### 4-1. 대안 B: string 통일

`@JsonSerialize(using = ToStringSerializer.class)` 적용 — backend Long → JSON string → frontend `userId: string` 통일.

**거부 사유**:
- 모든 Long 필드에 annotation 추가 의무 → 보일러플레이트 ↑
- DB 조회 시 `String → Long` 변환 layer 발생 → 자본 path adapter 부담 ↑
- JS BigInt 사용 시 직렬화 부담(JSON.stringify BigInt fail)
- MAX_SAFE_INTEGER 안전권 활용 못 함

### 4-2. 대안 C: 현행 유지(string·number 영역별 자율)

frontend 영역별 sub-agent가 자율 결정 — 그대로.

**거부 사유**:
- root cause 그대로 — 신규 영역(T-AI W4 진입 시) 동일 결함 재발 risk
- frontend cross-cutting 결함이 누적되어 매 사이클 PR 리뷰 시 부담

### 4-3. 대안 D: brand type 도입

TS brand type `type UserId = number & { __brand: 'UserId' }` — type-level invariant.

**거부 사유**:
- frontend 학습 부담 ↑ MVP 부적합
- backend Kotlin inline class 정합 추가 — 모든 controller/DTO에 inline wrap 의무
- 실익 0 (frontend 단일 number type으로 충분)

## 5. 사용자 명시 인용

> "frontend PR #549 evidence — userId 타입 string vs number 영역별 불일치 — backend·frontend cross-cutting risk → architect ADR 결정 의제" (2026-05-19 KST orchestrator 사용자 위임)

## 6. 관련 결정

- ADR 0006 — PostgreSQL 단일 + 스키마 분리 (`user_account.id BIGINT PK` 권위 anchor)
- ADR 0010 — Multi-tenant 구조 (`user_account` 멀티테넌트 baseline)
- ADR 0027 — MCP 서버 미래 노출 (`user_id` JSON schema int64 정합 prereq)
- ADR 0046 — Onboarding & Credential Registration (onboarding flow `userId` 발급 path)
- ADR 0081 — T-ACCT 신규 트랙 (Dashboard userId props 일관성)
- ADR 0082 — T-MAN 수동 주문 (`POST /api/orders/manual` payload userId 정합)

## 7. 변경 이력

| 일자 | 변경 | 사유 |
|------|------|------|
| 2026-05-19 | ADR 0089 신설 — Proposed | frontend PR #549 P2-F1 cross-cutting evidence (userId string vs number 영역별 불일치 5+ 영역) + backend·frontend cross-cutting risk → architect ADR 결정 의제. 옵션 A(backend·frontend 모두 number 통일) 채택. DB BIGINT → JS number 자연 정합 + MAX_SAFE_INTEGER 안전권 + MCP future spec 정합. Phase 1 backend-core DTO 검증 + Phase 2 frontend type 정정 5+ 파일 dispatch. (architect, opus 4.7 1M) |
