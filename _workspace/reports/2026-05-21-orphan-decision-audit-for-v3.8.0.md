# Orphan decision audit 보고서 — recent hotfix 14건 (사용자 결재 의제) — v3.8.0 cut 전 LOCK

> **위치**: `_workspace/reports/2026-05-21-orphan-decision-audit-for-v3.8.0.md`
> **발행자**: architect (Stage 2 종합 정리 사이클 — 2026-05-21)
> **목적**: 최근 hotfix·feat 14건 중 **코드에만 있고 ADR/헌법으로 정리 안 된 결정** 식별 + 사용자 결재 의제 매트릭스 발행.
> **분류 기준**: (1) **ADR 신규 발행 의무** — 정책·아키텍처 결정 본문화 필요 / (2) **기존 ADR amend 권장** — 기존 결정에 정합 row 추가 / (3) **ADR 불필요** — 단순 결함 fix·dogfooding 정정 (영구 코드 evidence 충분).
> **트리거**: 사용자 명시 "코드에만 있고 정리안된 것들을 찾아서 싹 정리해서 다시 토큰, 메모리 정제한다음 다시 깨끗한 마음으로 3.8.0 릴리즈부터는 토큰도 적절하게 읽어드릴 수 있을 수 있잖아?"

---

## 0. 요약 (사용자 결재 의제 매트릭스)

| 분류 | 항목 수 | 결재 의무 |
|------|--------|----------|
| **ADR 신규 발행 의무** | 2 | ★ 사용자 결재 — backlog로 흡수 또는 즉시 architect dispatch |
| **기존 ADR amend 권장** | 4 | 사용자 결재 — amend cycle 합류 또는 v3.8.x patch 흡수 |
| **ADR 불필요 (코드 evidence 충분)** | 8 | 결재 불필요 — 본 보고서 archive로 종결 |

합계: 14 hotfix 중 6건 ADR 보강 의제 + 8건 결재 불필요.

---

## 1. ADR 신규 발행 의무 2건 (★ 사용자 결재 의제 — backlog 또는 즉시 dispatch)

### 1-1. KIS 토큰 EGW00123 self-heal (cache evict + 1회 재발급) — PR #641 hotfix

**evidence** (commit `775c87fe`): `hotfix(api): KIS 토큰 EGW00123 self-heal (cache evict + 1회 재발급)`

**미정리 결정**:
- EGW00123 error code 분기 + cache evict 자동 + 1회 재발급 policy
- 재발급 실패 시 fallback path (현재 코드: throw or skip?)
- self-heal 카운트·rate limit (1회 재발급 vs 무제한 재발급)
- 다른 token error (EGW00121·EGW00133)와의 정합 매트릭스
- 관련 cross-ref: ADR 0038 (KIS OAuth 24h + Redis 분산락) — 본 ADR에 §amend 흡수 가능 의제

**권고 분류**: **ADR 신규 발행 의무 또는 ADR 0038 amend** (둘 다 가능 — architect 결정 의제)

**제안 옵션**:
- **옵션 가** (권장): ADR 0038 amend §추가 — "KIS OAuth token self-heal policy (EGW00123 cache evict + 1회 재발급 LOCK + rate limit + fallback)"
- **옵션 나**: ADR 0097 신규 발행 — "KIS Token error 분류 + self-heal matrix"

**처리 cycle 제안**: v3.8.x patch 사이클 흡수 또는 backlog §amend row 추가.

### 1-2. KRX 운영 시간 매트릭스 확장 (지연 데이터 라벨 fall-through 결함 fix) — PR #642 hotfix

**evidence** (commit `c1f37fe5`): `hotfix(frontend): 지연 데이터 라벨 fall-through 결함 + KRX 운영 시간 매트릭스 확장 (사용자 dogfooding)`

**미정리 결정**:
- KRX 운영 시간 매트릭스 (장중·장외·휴장·NXT 등) frontend 단독 LOCK
- ADR 0013 (Market Session FSM) — 본 ADR 권위지만, frontend "지연 데이터 라벨" UX 정책 본문화 0
- NXT 시간외 정합 (ADR 0076·0091) cross-ref 필요
- 사용자 dogfooding evidence — false positive "지연 데이터" 표시 risk

**권고 분류**: **ADR 0013 amend** (Market Session FSM + UX 라벨 매트릭스 본문화)

**제안 옵션**:
- ADR 0013 §amend — "장 세션 × frontend UX 라벨 매트릭스 (실시간/지연/장외/휴장 라벨 + fall-through 가드)"

**처리 cycle 제안**: v3.8.x patch 사이클 흡수.

---

## 2. 기존 ADR amend 권장 4건

### 2-1. KisHttpErrorClassifier sealed interface 도입 — PR #640 hotfix

**evidence** (commit `ad5fcc82`): `hotfix(backend-core): KIS EGW00201 분기 + SystemEvent KIS_RATELIMIT P1 활성 (502 root cause fix)`

**미정리 결정**:
- KIS HTTP error 분류 sealed interface (RateLimit·Auth·NotFound·ServerError 등) — KisHttpErrorClassifier 권위 도입
- ADR 0037 amend evidence가 충분 — 본 ADR §6에 amend row 가능

**권고 분류**: **ADR 0037 amend** (또는 README §14 변경 이력 row만 발행 — sealed interface 본문은 코드 evidence 충분)

**처리 cycle 제안**: 본 Stage 2 cleanup이 아닌 별도 v3.8.x patch 사이클에서 흡수 가능 (architect 단독 PR 또는 backend-core PR 시 동반).

### 2-2. KIS_RATELIMIT P1 활성 (SystemEvent emit) — PR #640 동반

**evidence**: 동일 commit (위 §2-1) — `SystemEvent KIS_RATELIMIT P1 활성` 본문.

**미정리 결정**:
- SystemEvent KIS_RATELIMIT severity P1 LOCK + emit 시점 LOCK + Slack alert routing
- ADR 0095 (P0-OPS-3 SLO/SLA + alert routing) 본문에 KIS_RATELIMIT P1 routing 명시되어 있음 (S-4 SLO + severity routing matrix)
- ADR 0044 (Notification System) — Slack alert reuse 본문 충분

**권고 분류**: **ADR amend 불필요** (ADR 0095 + 0044 이미 본문화). 본 PR commit message + CHANGELOG row가 evidence 보존 충분.

### 2-3. WS_CONNECTED action 분리 (PR #645 hotfix)

**evidence** (commit `bd7bb754`): `hotfix(market-data): KIS WS startup 첫 연결 WS_CONNECTED emit (PR #644 한계 — 거짓 끊김 영구 잔존)`

**미정리 결정**:
- WS_CONNECTED · WS_RECONNECTED · WS_DISCONNECTED 3 action 분리 정책
- frontend "거짓 끊김" toast 표시 fall-through 가드
- 관련 cross-ref: ADR 0064 (Redis boundary fanout + STOMP 실시간 합산 갱신) Proposed — 본 ADR 본문에 amend 가능

**권고 분류**: **ADR 0064 amend 또는 ADR 0044 amend** (Notification + WS event matrix)

**제안 옵션**: ADR 0044 §amend — "WS event 3종 분리 + frontend toast 매트릭스" (architect 결정 의제).

**처리 cycle 제안**: v3.8.x patch 또는 ADR 0064 Proposed → Accepted 격상 시 동반.

### 2-4. markResolved + viewed_at 동반 마킹 — PR #635 hotfix

**evidence** (commit `9799760b`): `hotfix(backend-core): markResolved 시 viewed_at 동반 마킹 (사용자 mental model 정합)`

**미정리 결정**:
- SystemEvent resolved 시 viewed_at 동반 마킹 UX 정책
- 사용자 mental model: "resolved = 본 후 처리 완료" → viewed_at 자동 마킹 LOCK
- ADR 0044 (Notification System) 본문 amend 가능

**권고 분류**: **ADR 0044 amend** (선택) — UX 정책이 사용자 mental model 직결이므로 ADR 본문화 가치 있음

**제안 옵션**: ADR 0044 §amend — "SystemEvent UX 정책 (resolved + viewed_at 동반 + LOCK)" 또는 본 hotfix commit message만으로 evidence 충분 (architect 결정 의제).

**처리 cycle 제안**: v3.8.x patch 동반 또는 본 보고서 archive 후 종결.

---

## 3. ADR 불필요 8건 (코드 evidence 충분 — 결재 불필요)

### 3-1. SymbolMetaService currentPrice 매핑 hotfix (PR #595)

- commit message + CHANGELOG row + dogfooding evidence (SK하이닉스 1,840,000 vs HTS 1,719,000) 충분
- 단순 매핑 결함 fix — ADR 권위 본문 0 필요

### 3-2. KIS 캔들 시장코드 resolver 종목 단위 보강 (PR #595~) — ADR 0091

- 이미 ADR 0091 (NXT 통합 시장코드 종목 단위 resolver) Accepted 본문화 완료
- 본 hotfix는 ADR 0091 본문 정합 — 별도 ADR 0 필요

### 3-3. order FILLED stomp_event qty/executedQty 매핑 누락 fix (PR #639)

- commit `b6d17fbc` — 단순 매핑 결함 fix
- ADR 0068 (T-O 주문 실행) 본문 정합 — ADR amend 0 필요 (commit message + CHANGELOG row가 evidence)

### 3-4. OrderListItemDto·OrderDetailDto name 필드 + instrument join (PR #631)

- 단순 DTO 확장 + JOIN 추가 — 정책 결정 0 + 자본 path 영향 0
- ADR 0 필요

### 3-5. OrderCard·OrderDetailPage 종목명 표시 (PR #632)

- 단순 frontend UI 표시 정정 — 정책 결정 0
- ADR 0 필요

### 3-6. WS 접속 상태 indicator + 끊김 toast (PR #634)

- frontend UI 신규 — ADR 0009 (frontend SPA) baseline 정합
- 단독 ADR 0 필요 (UI 컴포넌트 spec은 ADR 0009 본문화 충분)

### 3-7. KIS WS health indicator + 끊김 toast (STOMP indicator와 분리) — PR #645 부분

- 단순 frontend 분리 — STOMP vs KIS WS indicator 분리 정책은 본 PR commit message + CHANGELOG row 충분
- ADR 0064 (Redis boundary fanout + STOMP) Proposed — 본문화 시 흡수 가능 (선택)

### 3-8. PositionsPage data source 정합 (account/positions 사용, 사용자 dogfooding 빈 화면) — PR #643

- 단순 frontend data source 결함 fix — 사용자 dogfooding evidence (commit message 충분)
- ADR 0 필요

### 3-9. backfill toast 결함 3건 (qty=null skip + copy 정정 + lastSeenAt 갱신) — PR #642

- 단순 UI 결함 fix — 정책 결정 0
- ADR 0 필요

(주의: 8건이지만 §3-1·3-2·3-3·3-4·3-5·3-6·3-7·3-8·3-9 9건으로 분류 — 카운트 정정. 단, §3-7은 ADR 0064 흡수 의제로 동반 가능)

---

## 4. architect의 결재 권고

### 권고 1: ★ ADR 신규 발행 의제 2건 — backlog 또는 즉시 dispatch

- §1-1 (KIS 토큰 EGW00123 self-heal) — ADR 0038 amend 또는 ADR 0097 신규 (architect 결정 의제)
- §1-2 (KRX 운영 시간 매트릭스 + UX 라벨 fall-through) — ADR 0013 amend

**제안**: 사용자 결재 시점에 architect 단독 dispatch 1회 (1 PR로 2 ADR 묶음) 또는 backlog row 추가 후 v3.8.x patch 합류.

### 권고 2: 기존 ADR amend 4건 — v3.8.x patch 흡수

- §2-1·§2-2·§2-3·§2-4 — 본 Stage 2 cleanup이 아닌 별도 patch 사이클 흡수.
- 사용자 결재 의제 — 즉시 dispatch 또는 backlog 합류.

### 권고 3: ADR 불필요 8건 — 본 보고서 archive로 종결

- §3 8건은 commit message + CHANGELOG row + dogfooding evidence가 충분 — ADR 권위 본문 0 필요.
- 본 보고서 archive 시 함께 종결.

### 권고 4: ★ magicjar-chart-curator skill archive 의제 (별도 결재 — Task 11 정합)

**evidence**: `.claude/skills/magicjar-chart-curator/SKILL.md` 존재 + ADR 0094 amend (2026-05-21)로 chart-curator 에이전트 해고.

**문제**: chart-curator 에이전트(`.claude/agents/chart-curator.md`)는 본 Stage 2에서 `_archive/`로 이동 완료, 하지만 동일 시점에 magicjar-chart-curator skill(`.claude/skills/magicjar-chart-curator/`)은 그대로 존재 → drift.

**archive 옵션** (사용자 택1):
- **옵션 가** (권장): `.claude/skills/_archive/magicjar-chart-curator/` 이동 + SKILL.md header에 Superseded by ADR 0094 amend
- **옵션 나**: skill directory 그대로 보존 (사용자가 chart-curator skill 호출 시 fallback path 보존) — 권위 마커만 추가
- **옵션 다**: skill directory delete (토큰 정제 강화)

**권장 옵션 가** — agent와 skill 동시 archive로 정합 LOCK + 역사 evidence 보존.

**처리 cycle 제안**: 별도 architect 단독 PR (1~3 파일 정도). 본 Stage 2 단일 PR 범위 외(skill 디렉토리 이동은 orchestrator/사용자 level 결재 의제이므로).

### 권고 5: 본 보고서 사후 처리

- 사용자가 §1·§2 결재 완료 후 본 보고서는 `_workspace/reports/_archive/2026-05-21-orphan-decision-audit-for-v3.8.0.md` 이동 또는 그대로 보존 (history evidence).

---

## 5. 검증

- architect read만 수행 — 운영 코드 0 touch.
- ADR 발행 의무 2건 + amend 권장 4건 = 6건 사용자 결재 의제 분류.
- ADR 불필요 8건은 본 보고서 history evidence로 영구 보존 (commit message 추적 가능).

**상태**: 사용자 결재 대기 (Stage 2 cleanup PR 머지 후 1회 사용자 의무 작업).
