# KIS Credential 식별 규약 — 현행 권위

> **권위**: 본 문서. 변경 시 직접 갱신.
> **결정 이력**: ADR-0046 (Onboarding 도입), backlog §23 (REAL/MOCK 불일치 의제), PR #246 (firstOrNull fallback), ADR-0142 (WS owner/HTS ID 분리)
> **마지막 갱신**: 2026-07-16 (ADR 0142)

---

## 현행 규약

`user_kis_credential` 테이블의 `user_id` 컬럼에 들어가는 값이 **REAL/MOCK 다름** (single-user 환경 가정):

| 모드 | `user_id` 컬럼 | `account_type` |
|------|----------------|----------------|
| REAL | **계좌번호** (`request.accountNumber`) | `"REAL"` |
| MOCK | **KIS user_id** (`request.userId`) | `"MOCK"` |

### Lookup 전략 (single-user 가정)

일반 REST adapter는 다음 legacy single-user fallback을 유지한다:

```kotlin
val cred = credentialRepository
    .findAllByAccountType(accountType.name)
    .firstOrNull { it.id.userId == userId }
    ?: creds.firstOrNull()    // single-user fallback
    ?: error("KIS 자격증명 없음 — type=$accountType")
```

→ 일반 REST의 `defaultUserId` 매칭 fail 시 첫 credential을 쓰는 legacy 동작이다.

**예외 — WS market plane:** ADR 0142 이후 `KisApprovalAdapter`와 WS today-candle seed는 configured
credential owner와 정확히 일치하는 REAL row만 허용한다. 다른 REAL row로 fallback하지 않으며,
불일치 또는 credential repository 부재 시 approval은 실패하고 today-candle seed는 REST 호출 전 중단한다.

### WebSocket identity 분리 (ADR 0142)

`user_kis_credential.user_id` lookup owner와 KIS 체결통보 `tr_key`는 같은 개념이 아니다.

| identity | 의미 | 설정 | fallback |
|---|---|---|---|
| credential owner ID | credential row lookup | `MAGICJAR_KIS_WS_CREDENTIAL_OWNER_ID` | legacy `MAGICJAR_KIS_WS_USER_ID` |
| KIS HTS ID | REAL `H0STCNI0` subscribe `tr_key` (`my_htsid`) | `MAGICJAR_KIS_WS_HTS_ID` | **없음** |

두 값이 우연히 같더라도 서로 대체하지 않는다. HTS ID가 명시되지 않으면 REAL execution notice는
`NOT_CONFIGURED`로 fail-closed하며, owner ID를 `tr_key`로 발사하지 않는다. Phase 1은 WS market plane의
cross-account fallback만 제거하고 DB schema와 일반 REST legacy fallback은 변경하지 않는다.

---

## 적용 위치 (코드)

| 위치 | 역할 |
|------|------|
| `api/.../OnboardingService.kt:136` | REAL 키 저장 (user_id=accountNumber) |
| `api/.../OnboardingService.kt:231` | MOCK 키 저장 (user_id=userId) |
| `consumer/.../KisApprovalAdapter.kt` | WS approval_key — configured owner exact-match |
| `consumer/.../KisWebSocketBridge.kt` `resolveSeedUserId` | today-candle seed — configured owner exact-match |
| `consumer/.../KisRestCandleAdapter.kt:71-72` | REST 캔들 — firstOrNull |
| `consumer/.../PositionBootstrapRunner.kt:58` | 모든 active credential 순회 (userId 매칭 X) |

---

## 알려진 한계 + 다음 진화

**현재 single-user 환경 한정**. multi-tenant 진입 시 식별 규약 통일 필요. backlog §23에서 4 옵션 검토:

1. REAL/MOCK 모두 user_id 통일 (user_account.user_id 권위)
2. user_kis_credential.user_id 컬럼 의미 명시 + 주석 + ADR
3. KisWebSocketBridge `defaultUserId` 의미 분기 (REAL=계좌번호, MOCK=user_id)
4. `kis_user_id` 컬럼 별도 추가 (마이그레이션)

**트리거**: W3 T-O (Order) 진입 시 본격 정리 — 사용자별 주문 권한 격리 시점.

---

## 변경 이력

- 2026-07-16: ADR 0142 — WS credential owner exact-match와 KIS HTS ID 설정·fallback 분리
- 2026-05-08: 도입 (ADR-0053, PR #248) — Living Policy로 분리
- 2026-05-08: firstOrNull fallback 추가 (PR #246, backlog §23 신설)
- 2026-05-XX: 도입 시점 (ADR-0046 — Onboarding & Credential Registration)
