# Spec — magicjar-autopilot: 자율 증대(self-augmentation) 무한 루프 시스템

- 상태: **Draft (2026-06-22)** — 사용자(최고 관리자) 결재 후 구현. 자본 경로 **최고 위험** (완전 자율 REAL 트레이딩).
- 작성: orchestrator (사용자 위임)
- 베이스라인/롤백 앵커: **v3.14.0** (tag·release/v3.14.0 immutable)
- 실행 주체: **Claude(나) 직접** — Codex 핸드오프 폐기(2026-06-22 사용자 명시). 스킬 = 나의 자율 운영 매뉴얼.

## 0. 사용자 결재 (LOCKED — 2026-06-22)

| # | 결정 | 값 |
|---|------|-----|
| 1 | 경기장(arena) | **REAL + 하드 칭** (실주문, 소액 캡) |
| 2 | 손실 envelope | **매우 타이트 + 누적 손절 자동중단(kill switch)** |
| 3 | 자율 경계 | **완전 자율, 체크포인트 0** (생성·검증·페이퍼·튜닝·REAL 활성 전부 자율) |
| 4 | 실행 주체 | **Claude 직접** (Codex 미사용) |
| 5 | 롤백 | v3.14.0 복귀 준비 필수 — 코드 + 돈(포지션) 양면 |

## 1. 1원칙 — 코드 강제 > 프롬프트

체크포인트가 0이므로 사람이 막아주지 않는다. 따라서 **자율 루프의 판단(LLM 추론)은 신뢰 대상이 아니다.** 안전은 전적으로 **코드로 강제되는 회로차단기**가 책임진다. 프롬프트·스킬 지시로 "칭을 지켜라" 하는 것은 안전장치로 인정하지 않는다 — LLM은 hallucinate·drift 한다. 모든 한도는 `RiskGatewayEvaluator`/`RiskBudgetCalculator` 계열 코드 경로에 박는다.

## 2. 2-Phase — 하니스가 먼저

**Phase 0(안전 하니스)가 orchestrator(나)에 의해 실증되기 전엔 단 1주도 REAL 자율 주문하지 않는다.** 칭 작동·kill switch·롤백을 내가 직접 재현 검증한 뒤에야 Phase 1 루프가 REAL을 만진다.

### Phase 0 — 안전 하니스 (코드 · 구현 후 내가 검증)

#### 2-0-A. `AutonomousTradingGuard` (신규 회로차단기)
루프가 만든(=`owner=AUTOPILOT`) 전략의 모든 REAL 활성화·매수는 이 게이트를 **반드시** 통과. 강제 항목(전부 config, 보수적 default):

| 한도 | 기본값(config) | ENV |
|------|----------------|-----|
| 루프 전용 자본 풀 상한 | 계좌 순자산의 **5%** | `MAGICJAR_AUTOPILOT_POOL_PCT` |
| 건당 진입 캡 | 풀의 **20%** | `MAGICJAR_AUTOPILOT_PER_TRADE_PCT` |
| 동시 보유 종목수 | **3** | `MAGICJAR_AUTOPILOT_MAX_POSITIONS` |
| 일일 손실 한도 → 당일 halt | 풀의 **30%** | `MAGICJAR_AUTOPILOT_DAILY_LOSS_PCT` |
| 누적 손실 한도 → kill switch | 풀의 **50%** | `MAGICJAR_AUTOPILOT_CUM_LOSS_PCT` |

- 기존 `RiskBudgetCalculator`(계좌 전체 공유 풀) **위에 더 좁게** 겹쳐 적용 — 둘 중 더 보수적인 값이 이긴다.
- 풀 상한은 사용자 수동 전략·후성 오버나잇이 쓰는 자본과 **분리** 계산(루프 노출만 합산).
- 한도 위반 시 진입 거부 + SystemEvent + Slack 알림.

#### 2-0-B. Kill switch
- 단일 권위 플래그(DB `autopilot_state` 테이블 또는 Redis `autopilot:kill`).
- **자동 ON**: 누적 손실 한도 도달 시 → ①`owner=AUTOPILOT` 전략 전부 PAUSED ②(옵션)보유 포지션 시장가 청산 ③REAL 게이트 freeze ④Slack P0.
- **수동 ON**: `/autopilot stop`.
- kill 상태에선 Phase 1 루프가 REAL 경로 진입 불가(페이퍼·백테스트는 가능).

#### 2-0-C. 루프 태깅 (격리)
- 루프 생성 전략: `strategy.owner=AUTOPILOT`(기존 `strategy.owner` 컬럼 재사용 — V88 정합). 포지션·시그널·주문도 이 전략 FK로 자연 격리.
- 사용자 수동 전략(전략47 등)·후성 보유와 **절대 비간섭**. 롤백·kill은 `owner=AUTOPILOT`만 대상.

#### 2-0-D. 스냅샷
- 루프 시작 전 `node scripts/autopilot-snapshot.mjs` — DB 상태(전략·포지션·리스크 config) + 현재 git SHA(=v3.14.0) JSON 캡처 → `_workspace/autopilot/snapshots/<ts>.json`.

### Phase 1 — 자율 루프 (스킬 `magicjar-autopilot`)

`/loop` 자기 페이싱(ops-monitor 패턴). 매 사이클:

1. **관찰** — `owner=AUTOPILOT` 전략 페이퍼+REAL PnL, 리스크 사용량, kill 상태, ops-monitor 감시 신호.
2. **발굴** — 기존 discovery 레이어(`DaytradeDiscoverService`·스코어카드) TOP N 후보.
3. **생성/튜닝** — AI 생성(`strategyBuilderAiGenerate`)+빌더 DSL로 신규 전략, 또는 직전 사이클 **저널 복습** 후 부진 전략 파라미터 튜닝.
4. **검증** — `JvmBacktestEngine` 백테스트 + 페이퍼(ADR 0122) 가동. 측정: 승률·MDD·기대값·왕복비용 후 실현.
5. **승격** — 기준(예: 페이퍼 N일·승률·MDD) 통과 전략만 `AutonomousTradingGuard` 통과해 REAL 활성.
6. **강등/킬** — 부진 REAL 전략 PAUSED. 누적 손실 → kill switch.
7. **저널** — 사이클 결과(전략 유전·변경 사유·성과)를 `_workspace/autopilot/journal/`에 기록 = 다음 사이클 복습 기반.
8. **반복** — `/loop` 재진입.

## 3. 롤백 (`/autopilot rollback`)

"v3.14.0 복귀" 한 줄 묶음:
1. `git reset --hard v3.14.0` + `scripts/build-and-deploy.sh` 재배포.
2. `owner=AUTOPILOT` 전략 전부 PAUSED→삭제.
3. 루프 보유 포지션 시장가 청산.
4. 스냅샷 DB 복원.

**⚠ 한계**: REAL 체결로 이미 지출된 자본은 되돌릴 수 없다. 그래서 §2-0-A 하드 칭이 최대 손실을 bound 하는 것이 유일한 자본 보호다.

## 3-bis. 폐기 철학 — 2-tier 선택적 롤백 (2026-06-22 사용자 PO 위임)

"v3.14.0 복귀"는 **전량 리셋이 아니다.** autopilot 시도는 쉽게 버리되, 수행 중 **수 회 검증된 진짜 좋은 개선은 살린다.** 2계층:

- **Tier A — autopilot 전용 (쉽게 폐기)**: autopilot 패키지(`**/autopilot/*`)·`MAGICJAR_AUTOPILOT_ENABLED`(default **false**) 게이트·`owner=AUTOPILOT` 데이터. 폐기 = 플래그 off + autopilot 패키지 제거 + owner=AUTOPILOT 데이터 정리. **기존 매매 동작은 v3.14.0 그대로** (autopilot 미설정 시 zero-regression).
- **Tier B — 검증된 개선 (폐기에서 생존)**: autopilot 작업 중 일반적으로도 가치있다고 **반복 검증된** 개선(버그 fix·리팩터·관측 향상)은 autopilot 패키지 **밖**에 두고 `// [AUTOPILOT-PROVEN <date>] <근거·검증횟수>` 주석. 폐기 시 보존·promote.
- **폐기 매니페스트**: `_workspace/autopilot/DISCARD-MANIFEST.md` — Tier A(폐기)/Tier B(보존) 추적. 매 검증 사이클 갱신 → 폐기 시 이 문서가 권위.

### 규율 (PO 자율 운영)
- **커밋**: autopilot 변경은 `feat(autopilot):`/`fix(autopilot):` prefix 일관 → `git log --grep autopilot`로 전체 추적·일괄 revert 용이. 커밋 메시지에 무엇을·왜·검증여부 상세.
- **Flyway**: 모든 테이블/데이터 변경은 up SQL과 함께 **되돌림 노트**(rollback SQL 주석) 동봉. autopilot 테이블은 `autopilot_` prefix로 명확 격리.
- **자율 패치**: 빌드·충돌·ktlint·인터페이스 호환은 사용자 의제 아님 — 자율 처리(feedback_conflict_resolution_autonomy 정합).
- **검증 승격**: 페이퍼/실증으로 N회(권장 ≥3) 검증된 개선만 Tier B 승격(주석 + 매니페스트 기록).

## 4. 자원 audit (architect 정합 의무 — feedback_spec_audit)

- **Flyway**: `autopilot_state`(kill·풀·누적손실 추적) 신규 테이블 1 + `strategy.owner` enum에 `AUTOPILOT` 값 추가(V88 재사용). 다음 V번호 확인 의무(현 V90 최신).
- **ENV**: §2-0-A 5건 신규(전부 보수적 default 보유 — 미설정 시 안전측).
- **REST**: `/api/autopilot/*`(상태·kill·rollback) 신규 — 기존 경로 충돌 audit.
- **Kafka/STOMP**: 신규 wire 0 목표(기존 시그널·주문 경로 재사용). autopilot 상태 알림은 기존 Slack/SystemEvent.
- **스킬**: `.claude/skills/magicjar-autopilot/SKILL.md` 신규.

## 5. 검증 게이트 (Phase 0 → Phase 1 진입 조건)

Phase 1 라이브 REAL 자율은 다음을 내가 **실증**한 뒤에만 허용:
1. 칭 위반 진입이 코드에서 거부됨(단위테스트 + MOCK 라이브).
2. 누적 손실 한도 도달 시 kill switch 자동 ON + 전략 PAUSED 실증.
3. `/autopilot rollback`이 owner=AUTOPILOT만 정확히 되감고 사용자 전략·후성 보존 실증.
4. 루프 1 사이클 페이퍼 완주(생성→백테스트→페이퍼→저널) 실증.

## 6. 한계·리스크

- 완전 자율 REAL은 본질적으로 고위험 — 하드 칭이 최대 손실을 bound 하지만 칭 내 손실은 실현됨.
- LLM 전략 생성은 과적합·룩어헤드 바이어스 위험 — 백테스트+페이퍼 게이트로 완화하되 완전 제거 불가.
- 시장 급변(VI·갭) 시 페이퍼 검증과 REAL 괴리 — 기존 VI 정책·tick failsafe(ADR 0127) 재사용.
- kill switch·Guard는 consumer 가동 의존 — consumer 사망 시 자동 재기동(backlog) 미비분 인지.

## 7. 라운드 경계 (구현 순서)

- **R0**: 본 스펙 + ADR(autopilot 결정 기록) — architect.
- **R1 (Phase 0 하니스)**: `AutonomousTradingGuard`·`autopilot_state` Flyway·kill switch·태깅·스냅샷·`/api/autopilot/*` — strategy-engine + backend-core + market-data(청산 KIS). 단위테스트.
- **R2 (검증)**: orchestrator(나)가 §5 4건 실증.
- **R3 (Phase 1 스킬)**: `magicjar-autopilot` SKILL + 루프 절차 + 저널/복습 — orchestrator.
- **R4 (라이브)**: 사용자 트리거로 `/loop` 자율 가동.
