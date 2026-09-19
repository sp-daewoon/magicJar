# MOCK/REAL 경계 정합 감사 (2026-09-19)

> 사용자 지시: "모의투자용이랑 실거래용 2개로 나뉘는데 그 경계가 모호하다.
> 모의투자로 실거래랑 동일하게 처리할 수 있게 전반적으로 싹 고쳐야 하고,
> 그 시발점은 온보딩이 되어야 한다."

## 1. 문제 정의

magicJar는 **동일한 자동매매 파이프라인**(온보딩 → credential → WS 실시간 시세 →
시그널 → 리스크 게이트 → 주문 → 체결 통보 → 포지션/PnL)을 **MOCK(모의투자)**와
**REAL(실거래)** 두 모드로 운영해야 한다. 목표는 "MOCK에서도 REAL과 **완전히 동일한
경험/처리**"다 — 유일한 차이는 KIS endpoint(vps vs prod)와 실제 체결 여부여야 한다.

현재는 그 경계가 **세 개의 서로 다른 축**으로 흩어져 있고, 대부분 **정적 env**에
묶여 있어 **동적 온보딩(DB)**과 단절돼 있다. 이 단절이 "키 넣었는데 실시간 시세/주문이
기대대로 안 됨"의 근원이다.

## 2. 세 개의 뒤섞인 모드 축

| 축 | 결정 소스 | 성질 | 사용처 |
|---|---|---|---|
| ① 시스템 운영 모드 | `MAGICJAR_MODE` env | 정적(재부팅 필요) | `TradingModeGate.resolveMode` → 주문 REAL/MOCK 결정 |
| ② ALLOW_REAL 게이트 | `MAGICJAR_ALLOW_REAL` env | 정적 | 실주문 이중 잠금 |
| ③ 계좌 타입 | `AccountType.REAL/MOCK` (DB credential) | 동적(온보딩) | 어느 KIS 키/host를 쓸지 |
| ④ WS credential owner | `MAGICJAR_KIS_WS_CREDENTIAL_OWNER_ID` env | 정적 | 시세 approval_key 발급 대상 |

핵심 결함: **①②④는 env(정적), ③만 DB(동적).** 온보딩은 ③만 채우고 ①②④는 안 건드린다.
→ 온보딩으로 계좌를 바꿔도 운영 모드/시세 owner는 그대로 → 불일치.

## 3. 계층별 분기 실측 (코드 근거)

- `TradingModeGateImpl.resolveMode` — `MAGICJAR_MODE`가 REAL 아니면 **모든 주문 MOCK 강제**
  (paperStrategy는 항상 MOCK). REAL은 env+ALLOW_REAL+동의 3중 게이트.
- `KisWebSocketBridge` — `RuntimeModeParser.isRealMode(magicjarMode)`로 체결통보 trId 결정
  (`H0STCNI0` REAL / `H0STCNI9` MOCK). 시세 approval_key는 `wsTransport.resolvedCredentialOwnerId`
  (env) + `AccountType.REAL` 고정 → **MOCK 운영에도 REAL credential을 요구**.
- `KisApprovalAdapter.issueApprovalKey` — credential **exact match만 허용**(fallback 금지,
  자본·보안 안전장치). owner-id가 DB credential과 안 맞으면 발급 실패.
- 주문/잔고/체결 어댑터 다수(`KisOrderHttpAdapter`, `KisBalanceAdapter`,
  `ConsumerKisBalanceQueryAdapter`, `KisExecutionWsListener` 등) — `AccountType`으로 host·trId 분기.
- 전체 `MOCK|REAL` 분기: **130 파일 / 590 매치** (테스트 포함).

## 4. "모호함"의 3대 증상

1. **온보딩↔운영 단절**: 온보딩은 DB credential(③)만 채우고 운영 모드(①)·WS owner(④)는
   env라 안 바뀜 → 재부팅 없이는 반영 불가.
2. **MOCK 시세 불가**: WS 시세가 `AccountType.REAL` + env owner 고정 → MOCK 전용 사용자/모드는
   실시간 시세를 못 받음(REAL 키가 있어야만 동작).
3. **모드 선택 UX 부재**: 온보딩(SetupWizard)에 "이 시스템을 MOCK로 돌릴지 REAL로 돌릴지"
   명시 선택·기록이 없음. env로만 존재.

## 5. 설계 방향 (사용자 확정 2026-09-19)

> 사용자 제약 확정:
> 1. **시세/차트/지표는 무조건 REAL 고정** — 모드 전환과 완전 분리(변경 없음).
> 2. **주문은 REAL/MOCK 구분이 절대적으로 명확** — 사고 지점. 이중 잠금 유지, fail-closed.
> 3. **모드 전환이 서비스 재기동 없이** 가능해야 함(올리고 내리지 않아도).

목표 원칙: **주문 활성 모드만 DB 단일 권위로** 승격해 재기동 없이 전환. 시세는 REAL 고정 유지.
env(`MAGICJAR_MODE`/`MAGICJAR_ALLOW_REAL`)는 **REAL 상한 잠금**으로만 남긴다.

| 대상 | 모드 의존성 | 처리 |
|---|---|---|
| WS 실시간 시세 / 차트 / 지표 | **모드 무관 — 항상 REAL** | 변경 없음(고정 유지) |
| WS credential owner(시세 approval) | 항상 REAL credential | env 비면 등록된 REAL 자동 연결(전환과 무관) |
| **주문 / 체결통보 / 잔고 / 포지션** | **활성 모드(REAL/MOCK) 엄격 분기** | DB 활성 모드(재기동 없이 전환) |
| REAL 주문 상한 | 이중 잠금 유지 | `MAGICJAR_ALLOW_REAL` env(상한) + 사용자 동의 |

- **A. 활성 주문 모드 DB 권위화**: `system_config.active_trading_mode`(V102, default `MOCK`)
  신설. `ActiveTradingModePort`(domain) + consumer 어댑터. TradingModeGate는 **주문 시점마다**
  DB를 읽어 `min(env 상한, DB 활성모드)` 판정 → 재기동 없이 전환.
- **B. WS 시세 REAL 고정 유지 + owner 자동연결**: approval_key는 계속 `AccountType.REAL`.
  단 `resolvedCredentialOwnerId`가 env로 안 잡히는 문제만 "등록된 REAL 자동 연결"로 해소
  (모드 전환과 무관 — 시세는 항상 REAL).
- **C. 온보딩이 시발점**: SetupWizard가 활성 주문 모드 명시 선택(MOCK 먼저 → REAL 승격) +
  DB 기록. 설정 화면에서도 재기동 없이 전환.
- **D. MOCK = REAL 동형성 보장(주문 경로)**: MOCK 모드에서도 시그널→주문→체결통보→포지션이
  REAL과 동일 경로, endpoint만 vps. 시세는 어차피 공통(REAL).

## 6. 안전 불변식 (절대 훼손 금지)

- 시세는 항상 REAL — MOCK 전환이 시세 소스를 바꾸지 않는다.
- REAL 주문은 여전히 `env 상한(ALLOW_REAL) + 사용자 동의` 이중 잠금 뒤에서만.
  DB 활성 모드는 **env REAL 상한이 열려 있을 때만** REAL이 될 수 있다(상한이 MOCK이면 무조건 MOCK).
- 활성 모드 DB default = `MOCK`(안전 기본값). 판정 실패/불명 시 MOCK fail-closed.
- WS owner 자동연결은 "등록된 REAL 계정이 유일할 때만" — 타 계정 세션 위험 0.
- 이번 작업 중 실주문 발사 0. flag 뒤 zero-regression.

## 7. 단계적 실행 (PR 분리)

1. (진단) 본 문서 + architect 설계 확정.
2. (backend) 활성 모드 DB 권위 도입 + TradingModeGate `min(상한, 활성)` — flag 뒤, default 현행 유지.
3. (backend) WS owner/approval 동적 해소 — 활성 모드 AccountType.
4. (frontend) SetupWizard 모드 선택 + 온보딩이 활성 모드 기록.
5. (verify) MOCK 전 파이프라인 동형성 실증(WS 시세→시그널→MOCK 주문→체결통보→포지션).

각 단계 하네스 준수(브랜치+task.mjs+worktree author+rebase+PR+archive).
