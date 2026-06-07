# 07 — magicJar v3.12.0 정책 진화 (운영 헌법 + ADR 진화 누적)

> **작성자**: architect (baseline v3.12.0)
> **시점**: 2026-06-07 v3.12.0 태그 컷 직후
> **권위**: `_workspace/arch/agent-charter.md`(운영 헌법) + `docs/architecture/policies/`(Living Policy) + ADR 0109~0114 + v3.12.0 코드/운영 evidence

본 문서는 v3.9.0 → v3.12.0 사이 magicJar의 **정책 변화**를 어필한다. 이번 사이클의 정책 진화 본체는 "실매매(REAL canary) 진입을 위해 자본 path가 무엇을 보장해야 하는가"의 코드화다.

---

## §1. 주문 집행 정책 — "먼저 오는 쪽이 이긴다" (ADR 0114 §3)

### 1-1. 정책 전환: MARKET 고정 → marketable limit

v3.9.0까지 자동매매 진입은 **시장가 고정**이었다(`OrderRouterKafkaListener.kt:949-952` — ADR 0067 publisher가 priceType 미발행 → default MARKET). 슬리피지·스프레드 보호 0. 사용자 질문 ① "시장가로만 사고 있지 않나?"가 사실로 확인됐다.

v3.12.0은 진입을 **marketable limit**으로 전환한다:

- **체결 노리되 상한 보호** — 매도1호가(`ask1`)에 LIMIT을 걸되, 상한 `cap = priceAtSignal × (1 + maxEntrySlippctPct/100)`(default 0.2%)을 초과하면 **진입 스킵**. "먼저 오는 쪽이 이긴다" — marketable limit은 매도1호가에 걸어 즉시 체결을 노리되, 가격이 상한 위로 달아나면 따라가지 않는다.
- **시장가 fallback 금지** — 어떤 Skip 사유(가격 상한 초과·스프레드 과대·호가 미가용)에서도 MARKET 발행 0. 시장가 전환은 슬리피지 캡 자기 부정.
- **추격 금지** — 미체결 `entryTimeoutSec`(15s) 후 취소(시장가 전환 금지). 재시도 0회. 재시도 허용 시에도 기준가는 원 신호 가격 고정(현재가 재산정 = 추격).
- **손절 MARKET / 익절 LIMIT** — 손절은 체결 확실성 우선(KIS에 스탑지정가 없음 — §3), 익절은 가격 보호 우위(트리거 가격 기준 LIMIT).

이 정책은 `EntryPricePolicy`(strategy-engine 순수 계산기)로 live·백테스트·리플레이가 단일 함수를 호출한다 — "리플레이에선 스킵됐는데 라이브는 진입" 의심 원천 차단(ScaleInExitCalculator 선례).

### 1-2. 정책 근거: 슬리피지 보호 vs 체결률 trade-off

진입 스킵 신설은 체결 건수를 줄일 수 있다(슬리피지 보호 trade-off). 그래서 default를 보수 시작(0.2%·15s·0.3%)으로 두되, 체결률/스킵률을 신규 관측(`SIGNAL_SKIPPED_ENTRY_PRICE_CAP`·`_SPREAD`·`_NO_QUOTE` audit count)해 운영 데이터로 완화하는 경로를 둔다. 전략별 override(RiskPlan JSONB add-only)도 가능.

---

## §2. 포지션 청산 "항상" 보장 정책 (ADR 0114 §4 — P1~P4 게이트 분화)

### 2-1. 핵심 불변식: fail-closed는 BUY에만

사용자 질문 ② "포지션 보유 중 시스템이 긴장 상태인가?"에 대한 정책 답:

> **fail-closed는 "신규 자본 투입(BUY) 차단"에만 적용한다. 이미 투입된 자본의 회수(SELL)는 막지 않는다.**

v3.9.0까지 readiness fail-closed(ADR 0105)는 진입/청산 구분 없이 `!ready`면 일괄 return했다 — 손절가 도달해도 평가 skip(P1 Critical). v3.12.0은 게이트를 BUY/SELL 분화한다:

| 게이트 | BUY(진입) | SELL(보유 청산) | ADR |
|---|---|---|---|
| readiness(P1) | READY만(fail-closed 유지) | RECONCILING/STALE 진행 · FAILED는 현재가 가용 시 진행 | 0114 §4-1 |
| 전략 status(P2) | ACTIVE만 진입 | position-driven — 전략 status 무관(DISABLED 보유분도 청산) | 0114 §4-2 |
| 보유 종목 구독(P3) | — | HOLDING owner evict 절대 금지(청산 재료 유지) | 0114 §4-3 |
| market-hours(P4) | 정규장만(NXT 진입 금지) | sell-enabled env(NXT SELL 허용 가능 — default true 보수) | 0114 §4-4 |

핵심: readiness가 아니라 "청산 트리거에 쓸 현재가(tick/Redis current candle)가 있는가"가 SELL 평가의 진짜 게이트다.

### 2-2. 정책 근거: KIS에 서버 예약형 스탑로스가 없다 (KIS-first raw fetch 정합)

ADR 0114 §1-2가 KIS open-trading-api 공식 스펙으로 확정: **KIS 국내주식 OpenAPI에 가격 트리거 서버 스탑로스가 없다.**

| 후보 | 실체 | 가격 트리거? |
|---|---|---|
| 주식예약주문 `CTSC0008U` | 시간 예약(15:40~익영업일 07:30 접수) | ❌ |
| 조건부지정가 `ORD_DVSN=02` | 장중 지정가 → 15:20 시장가 전환 | ❌ |
| HTS/MTS 자동감시주문 | 단말 전용 | ❌ OpenAPI 미노출 |

→ **서버에 스탑로스를 맡길 방법이 없다.** magicJar(consumer) 자체가 스탑로스 장치이고, consumer가 멈추면 손절도 멈춘다. 따라서 청산 게이트 분화(P1~P4)는 부가 기능이 아니라 **자본 안전의 본체**다. 이것이 헌법 §3 KIS-first raw fetch가 자체 추론을 금지하는 정책의 직접 적용 사례 — "스탑로스 주문이 있겠지" 추론 대신 공식 스펙 확인으로 "없다"를 확정하고 그 위에 정책을 세웠다.

---

## §3. canary hard cap — 운영 안전선 vs 운용 정책 분리 (ADR 0109)

v3.12.0은 **운영 안전선**과 **운용 정책**을 분리한다:

- **운용 정책** — RiskConfig·POSITION_LIMIT(전략·사용자 단위 budget 게이트). "이 전략은 이 종목에 얼마까지".
- **운영 안전선** — canary hard cap 3 ENV(`MAGICJAR_CANARY_MAX_ORDER_QTY`·`_MAX_ORDER_NOTIONAL_KRW`·`_MAX_ORDERS_PER_DAY`). "canary 1회 동안 실수로라도 이 이상은 절대 안 나간다".

둘은 독립이며 **더 엄격한 쪽이 적용**된다(둘 다 통과해야 주문). hard cap은 RiskConfig와 독립이라 운영 cap 오염 0 + canary 종료 후 비활성 용이 + Flyway 불요(ENV add-only). 일일 횟수는 `order_request` 당일 `mode='REAL'` count — DB 단일 권위(Redis 카운터 미사용 — drift 회피).

정책 본질: canary는 "실수해도 손실이 작게 캡됨". RiskGateway가 정책 오류를 못 막아도 hard cap이 마지막 운영 안전선으로 강제 차단한다.

---

## §4. 전략별 포지션 귀속 정책 — 포지션이 청산의 1차 권위 (ADR 0110)

v3.9.0까지 `position`(user+code 합산)이 유일 권위였다. v3.12.0은 **계층화**한다:

- `position`(user+code 합산) — **KIS 잔고 권위 유지**(부트스트랩·SELL 하드 게이트). KIS는 전략을 모른다.
- `strategy_position`(user+strategy+code lot ledger) — 전략별 평단/진행도 추적. 익절/손절의 평단 기준.

정책 불변식: drift 시 **KIS 잔고(position)가 상위 권위** — 내부 ledger 버그가 실잔고 초과 SELL로 번지지 않는다(fail-safe). ADR 0114 P2는 이를 더 밀어 "전략이 살아 있어야 청산된다" → "포지션이 있으면 청산된다"로 청산 평가 진입점을 전환한다(position-driven). 전략이 DISABLED/삭제돼도 보유분은 무조건 청산 평가 대상.

---

## §5. AI 4종 Claude CLI bridge 통일 정책 (ADR 0112)

### 5-1. 정책 전환: AI API key → claude -p CLI

v3.9.0(T-AI)은 Anthropic API + OpenAI provider 추상화였다. v3.12.0은 사용자 명시("AI API 사용 안 할 거고 `claude -p`를 이용")로 **AI provider를 Claude CLI bridge로 통일**한다:

- **종목발굴**(ADR 0112) · **프리셋 추천** · **전략 생성** · **종목상세 차트해석/코멘트**(2026-06-08 지시) 4종이 단일 Claude CLI bridge 재사용.
- provider 분기 — host bootRun: `ProcessBuilder(command,"-p",prompt)` 직접 실행(`claude-cli`) / Docker dev: `http://host.docker.internal:8787/discover` bridge(`claude-cli-bridge`).
- 활성 게이트 — `MAGICJAR_AI_ENABLED=true` + `MAGICJAR_AI_PROVIDER=claude-cli`(default false — 사용자 옵션).
- 코드 — `consumer/.../ai/ClaudeCliCommentAdapter.kt`(코멘트) + `api/.../strategyresearch/StrategyResearchAiTextClient.kt`(발굴) + `scripts/claude-discovery-bridge.mjs`(bridge).

### 5-2. 정책 불변식 보존: AI 매매 결정권 0

provider가 바뀌어도 ADR 0001·0023·0026 불변식은 보존된다:

- 자동매매 본체(시그널 → risk → 주문 → 청산) AI 호출 0 LOCK.
- AI 종목발굴은 "전략 리서치로 검증할 후보를 찾는 사용자 트리거 옵션" — matched 후보는 사용자가 검증·저장·스크리닝하는 출발점일 뿐.
- 주문 adapter 호출 0 · `order.intent.v1` 발행 0 · 전략 자동 저장 0 · watchlist 자동 등록 0(direction-guard 정합).

---

## §6. 잔익절 방어 정책 (V73 min_hold_bars)

분할 익절·scale-in이 들어오면서 진입 직후 노이즈 SELL 시그널이 평단을 오염시킬 risk가 생겼다. v3.12.0은 **잔익절 방어 B안**으로 `min_hold_bars`(진입 체결 후 N캔들 동안 DSL 매도 시그널만 무시)를 add-only 추가한다:

- **안전망 우선** — DSL SELL만 무시하고 **손절·익절 라인은 항상 작동**(min_hold_bars는 노이즈 가드일 뿐 손절 차단 아님).
- null=비활성(현행 — DSL SELL 즉시 평가). 하위호환 LOCK.
- 구현 노트(V73 헤더): RiskPlan 단일 JSONB blob이 아니라 평면 컬럼(`take_profit_pct`·`fixed_stop_loss_pct`) + 전용 JSONB(`scale_in_config`·`partial_exits`·`entry_policy`) 구조 → min_hold_bars는 의미상 exit-noise 가드(평면 INT)이며 평면 risk 필드 패턴을 따라 전용 컬럼으로 추가(가장 정합·단순).

---

## §7. backup/DR 가동 — 설계 → 실증 (ADR 0096 → v3.12.0 실태)

ADR 0096(P0-OPS-4 DR/backup)은 v3.8.0에 설계됐으나, 2026-06-06 실태 감사에서 **cron 미등록 + 백업 0회 실행** 상태(ADR 자체로는 backup 활성 0)가 드러났다. 이는 ADR 0096 §1-3 baseline이 경고한 "cron wiring 0건" risk가 그대로 잔존한 것이다.

v3.12.0 사이클은 이를 **실증으로 전환**했다(2026-06-06):

- **첫 수동 백업 실행 + 검증 완료** — pg dump 56MB(custom format `-Fc`, TABLE DATA 56건, flyway V70 포함) + redis snapshot 71K.
- **복구 실증** — `scripts/backup/pg-restore.sh`(신규 — dump 짝)로 임시 DB `magicjar_restore_test`에 복원해 **테이블 56건·flyway V70·candle 254만 row·user_kis_credential 2건이 운영 DB와 row-level 일치**함을 실증.
- **산출물** — `scripts/backup/pg-restore.sh`(복원 + dry-run + 검증) + `docs/operations/new-pc-migration-runbook.md`(새 PC 완전 복원 3계층 런북) + `docs/operations/backup-and-restore-guide.html`(시각 가이드) + `docs/operations/backup-setup.md` 갱신.

스크립트 매트릭스:

| 스크립트 | 주기 | 산출물 | retention |
|---|---|---|---|
| `pg-dump-daily.sh` | 매일 03:00 KST | `_workspace/backups/postgres/daily/YYYY-MM-DD.dump` | 7일 |
| `redis-snapshot.sh` | 매일 03:30 KST | `_workspace/backups/redis/snapshots/dump-*.rdb` | 3일 |
| `pg-restore.sh` | on-demand(DR·새 PC·검증) | dump 복원 + dry-run + 복원 후 검증 | — |

> 정책 잔여: cron 등록은 **사용자 1회 작업**이 필요(macOS 단독 운영자). `backup-setup.md` §2 절차로 등록해야 자동 백업 활성. "설계는 실증으로만 완성된다"(사용자 메모리 `feedback_verification_strict` 정합).

---

## §8. ADR 진화 누적 (v3.9.0 → v3.12.0)

| ADR | 상태 | v3.12.0 정합 |
|---|---|---|
| 0001·0023·0026 | Accepted | AI 매매 결정권 0 + 옵션 보조 불변식 — ADR 0112 Claude CLI bridge 전환에도 보존 |
| 0043·0067·0068 | Accepted | KillSwitch + RiskGateway + Order Execution chain — v3.12.0 canary cap(0109)·SELL strategy_position 분기(0110)·marketable limit(0114)로 정밀화 |
| 0062 | Accepted | 1m 수집 → 백엔드 합산 source 전략 — ADR 0113 재확인 유지 |
| 0096 | Accepted | DR/backup — v3.12.0 첫 실증(cron 미등록 잔여) |
| 0102 | Accepted | spec 발행 시 main 영속 자원 audit 5단계 — ADR 0114 §10 적용(Flyway V71까지 audit) + V64/V65 번호 당김 정합 |
| 0105 | Accepted | readiness gate — ADR 0114 §4-1이 BUY 한정 fail-closed로 분화(amend 불요 — 0114가 SELL 분화 권위) |
| 0106 | Accepted | RiskPlan 필수화 — ADR 0111이 scale-in/평단 exit로 add-only 확장(amend) |
| 0107 | Accepted | Scenario Replay Sandbox — EntryPricePolicy·ScaleInExitCalculator 순수 함수 공유로 live≠리플레이 의심 차단 |
| **0109** | **Accepted (2026-06-05)** | **REAL canary hard cap ENV 3종 — 운영 안전선(RiskConfig 독립) + fail-open + DB 일일 카운트 권위** |
| **0110** | **Accepted (2026-06-05)** | **strategy_position 1급 도메인(V64) — position(KIS 잔고 권위) 유지 + 전략별 lot ledger + SELL 2룰 전략별 분기** |
| **0111** | **Accepted (2026-06-05, 0106 amend)** | **RiskPlan scale-in/평단 exit(V65) — ScaleInExitCalculator 순수 함수 + stop-loss exit + 분할 익절 + 하위호환** |
| **0112** | **Accepted (2026-06-06)** | **AI 종목발굴 Claude CLI bridge(V67·V70·V71·V74) — API key 없이 claude -p + matched 후보(매매 결정권 0)** |
| **0113** | **Proposed (2026-06-06, 결정 보류)** | **캔들 retention 계층화 케이스 분석 — V69 중복 인덱스 drop만 즉시 + source 전략 재확인** |
| **0114** | **Proposed (2026-06-07, 코드 머지)** | **주문 집행 marketable limit + 청산 "항상" 보장(V72·V73) — P1~P4 게이트 분화 + EntryPricePolicy + HOLDING owner** |

---

## §9. 운영 ADR 양식 3 슬롯 적용 (헌법 §9-5 — v3.12.0 사이클)

ADR 0109~0114는 모두 운영 ADR 양식 3 슬롯(SLO 영향·rollback plan·관측 매트릭스)을 적용했다.

| ADR | SLO 영향 | rollback | 관측 |
|---|---|---|---|
| 0109 | S-5(KillSwitch) 인접 — cap 초과 reject count 신규 | ENV ≤0이면 즉시 비활성 | canary cap reject count·일일 REAL 주문 count |
| 0110 | S-1(시그널→주문) 영향 0(upsert 1곳 추가) | strategy_position 미사용 시 position 합산 복귀 | STRATEGY_POSITION_DRIFT SystemEvent count |
| 0111 | S-1 영향 0(평가 로직 add-only) | scale_in null=현행 단일 진입 | scale-in 회차 체결·분할 익절 발화 count |
| 0114 | 진입 스킵으로 체결 감소 가능 + 손절 지연 단축(P1·P3) | entry_policy null=MARKET 진입 복귀(단 P1·P2는 안전 fix — rollback은 자본 보호 약화) | 진입 체결률/스킵률·손절 신호→체결 지연·HOLDING evict 0건·청산 미평가 0건 |

---

본 07-policy-evolution은 v3.12.0 시점 정책 변화 어필이다. 결정 근거 본문은 `_workspace/arch/adr/0109~0114`, 결정→코드 추적은 06-history-traceability, 시스템 그림은 01-architecture, 운영 헌법 전문은 `_workspace/arch/agent-charter.md`를 참조한다.
