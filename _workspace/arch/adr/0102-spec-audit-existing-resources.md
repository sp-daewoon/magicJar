# ADR 0102: spec/plan 발행 시 main 영속 자원 audit 의무 (5단계 절차 LOCK)

- 상태: **Accepted (2026-05-24 신설 · 2026-05-24 W4 P1 amend — §5-4 영역 확장 후보 cross-ref 신규 1건)**
- 작성일: 2026-05-24
- 결정자: architect (opus 4.7 1M) — orchestrator R0 결함 fix dispatch 정합 (사용자 메모리 `feedback-codex-validation-gate` 4 lens 정합)
- master §위치: `docs/architecture/master-architecture.md` v2 §10 (ADR 인덱스) · 헌법 §3 (KIS-first raw fetch) 보강 본문
- 트랙: cross-cutting (architect 영역 governance)
- 선행: 헌법 §3 (KIS-first raw fetch — "자체 추론 0 정책") · ADR 0030 (SoT 정합) · ADR 0036 (task cycle) · ADR 0050 (CHANGELOG slot)
- 본 ADR 발생 trigger: 2026-05-24 R0 PR #674 (commit 1411d392) Flyway V40~V45 LOCK 발행 후 main HEAD V40~V46 점유 사실 발견 결함 (헌법 §7 escalation 1건 발생)

---

## 1. 맥락

### 1-1. 결함 evidence

2026-05-24 architect R0 PR #674 (commit 1411d392)는 W4 통합 사이클 (T-MARKET·T-AI·T-MCP 3 트랙)의 spec/ADR/dispatch 매트릭스를 발행했다. 본 PR은 Flyway V40~V45 6건을 sub-agent별 LOCK으로 사전 분배했다.

```
| version | 표 | 담당 sub-agent |
|---|---|---|
| V40 | market_index_snapshot | market-data |
| V41 | sector_index_snapshot | market-data |
| V42 | ai_comment_log | ai-engineer |
| V43 | prompt_template | ai-engineer |
| V44 | user_preference | backend-core (T-MARKET) |
| V45 | mcp_audit | backend-core (T-MCP) |
```

그러나 ai-engineer R1 dispatch가 main HEAD `api/src/main/resources/db/migration/` 디렉토리를 audit한 결과 **V40~V46 모두 이미 점유** 사실이 발견되었다:

```
V40__order_event_outbox.sql
V41__stomp_event.sql
V42__instrument_status_history.sql
V43__order_request_add_strategy_id.sql
V44__system_event.sql
V45__order_request_backfill_manual_source.sql
V46__backtest_run_strategy_id.sql
```

R0 spec V40~V45 LOCK 전체 무효 → R1 3 sub-agent + R2 1 sub-agent 모두 동시 영향 → 헌법 §7 escalation 가동 → orchestrator가 R1 dispatched 2 sub-agent (market-data + backend-core T-MARKET) 즉시 kill + ai-engineer는 자체 escalation으로 작업 0 발행.

본 결함의 본질은 **architect spec 발행 시 main의 영속 자원 실태 audit 누락**이다. 헌법 §3 (KIS-first raw fetch — "자체 추론 0 정책 · 공식 출처 raw fetch 의무")의 lens가 외부 데이터 규격에만 적용되었고, **내부 영속 자원**에는 동등한 audit 절차가 부재했다.

### 1-2. 영향 lens (사용자 메모리 `feedback-codex-validation-gate` 4 lens 정합)

- **세심함**: spec/plan 발행 직전 main 영속 자원 audit grep 1회면 회피 가능했던 결함
- **영향도**: R1·R2 sub-agent 5명 동시 무효화 + orchestrator dispatch 비용 손실 + 사용자 신뢰 손실
- **연결**: 헌법 §3 (KIS-first raw fetch — 외부) ↔ 본 ADR (내부 영속 자원 audit) — 동일 lens "자체 추론 0"의 양면
- **확장성**: 본 결함은 Flyway 단일이 아닌, 모든 영속 자원 (Kafka topic·Redis key·STOMP topic·REST endpoint·ENV 변수) 공통 risk. 본 ADR로 전 영역 5단계 절차 LOCK 의무화

### 1-3. 운영 단계 lens 정합 (ADR 0094)

운영 단계 진입 후 spec/plan 결함은 직접 sub-agent dispatch 손실 + 사이클 risk → **5단계 절차 명문화**로 architect 자율성 보장 + 의무 절차 LOCK.

---

## 2. 결정

### 2-1. spec/plan 발행 직전 5단계 audit 절차 (architect 의무 LOCK)

architect가 spec/plan PR (즉 sub-agent dispatch trigger가 되는 모든 architect 영역 md PR)을 발행하기 직전 다음 5단계 audit를 순차 수행 의무:

| # | 단계 | 검증 명령 (예시) | 통과 기준 |
|---|---|---|---|
| 1 | **main HEAD 기준 영속 자원 inventory 작성** | `git fetch origin main && git ls-tree origin/main api/src/main/resources/db/migration/` | 영속 자원 디렉토리 모두 ls 출력 첨부 |
| 2 | **본 spec/plan에서 신설할 자원 매트릭스 작성** | (spec 본문 표 — Flyway version · Kafka topic · Redis key · STOMP topic · REST endpoint · ENV 변수) | 신설 자원 모두 명시 |
| 3 | **inventory와 신설 매트릭스 충돌 검사** | `grep "V40\|V41\|V42" <inventory>` 또는 같은 topic name grep | 충돌 0건 — 충돌 발견 시 다음 단계 진행 0 |
| 4 | **충돌 발견 시 next-available shift 결정** | 다음 자유 번호/이름 매트릭스 작성 (예: V47~V52) | shift 후 다시 §3 충돌 검사 통과 |
| 5 | **spec 본문에 audit evidence 첨부** | "main HEAD audit 결과 V46까지 점유 + V47 비점유 검증" 1줄 첨부 (또는 ls 출력 인용) | spec 본문 또는 동반 ADR 본문에 명시 |

### 2-2. 대상 영속 자원 매트릭스 (5단계 절차 적용 의무 영역)

| 영속 자원 | 위치 | audit 명령 (예시) | 충돌 시 shift 방식 |
|---|---|---|---|
| **Flyway** | `api/src/main/resources/db/migration/` | `git ls-tree origin/main api/src/main/resources/db/migration/ \| grep "^.*V[0-9]"` | next-available version (예: V47, V48...) |
| **Kafka topic** | `api/.../KafkaTopicConfig.kt` + grep | `git grep "TOPIC_" origin/main -- '*.kt'` | 명명 충돌 시 도메인 prefix 추가 (예: `market.index.updated.v1` 이미 사용 → `market.index.snapshot.v2`) |
| **Redis key** | 코드 grep | `git grep "redis" origin/main -- '*.kt' \| grep "key\|prefix"` | key prefix 충돌 시 차별 prefix |
| **STOMP topic** | `WebSocketConfig` + grep | `git grep "/topic/" origin/main -- '*.kt'` | 명명 충돌 시 namespace 분리 |
| **REST endpoint** | controller grep | `git grep "@RequestMapping\|@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping" origin/main -- '*.kt'` | path 충돌 시 별 path |
| **ENV 변수** | `.env.example` + grep | `git grep "MAGICJAR_" origin/main -- '*.kt' '*.yml' '.env*'` | 명명 충돌 시 도메인 prefix 추가 |

### 2-3. 절차 면제 조건 (architect 자율 판단 슬롯)

다음 조건 시 5단계 절차 면제 (수행 권장 + 자율 판단):
- **md only PR** (운영 코드 0 touch · ADR 본문 정합·헌법 §amend·CHANGELOG row 추가) — 영속 자원 신설 0건 본질
- **hotfix PR** — 시급한 결함 fix · 영속 자원 신설 0건 본질
- **이미 LOCK된 자원의 amend 본문 수정** — 새 자원 발행 0건 본질

면제 조건 외 모든 spec/plan PR (신규 트랙·새 모듈·다중 ADR 동반)은 **5단계 절차 LOCK 의무**.

### 2-4. 위반 시 escalation 경로 (헌법 §7 정합)

본 ADR 위반 사례 발생 시 (즉 architect spec/plan 발행 후 main 충돌 사실 R1·R2 dispatch에서 발견 시):

| 단계 | 절차 |
|---|---|
| 1 | sub-agent가 헌법 §7 escalation 가동 (작업 0 발행 + orchestrator에 보고) |
| 2 | orchestrator가 R1·R2 dispatched sub-agent 즉시 kill |
| 3 | orchestrator가 architect R0 결함 fix amend PR dispatch (본 결함 evidence + shift 매트릭스 LOCK) |
| 4 | architect amend PR이 R0 PR 시점 자원 LOCK + 본 ADR 0102 정합 의무 인용 (재발 방지 evidence 본문) |
| 5 | amend 머지 후 R1·R2 sub-agent 재 dispatch (헌법 §2 정리 4단계 완료 후) |

### 2-5. 본 ADR 발생 evidence 본문 (재발 방지 grep 의무)

2026-05-24 R0 PR #674 시점 V40~V45 LOCK 발행은 본 ADR §2-1 5단계 절차 부재로 발생한 결함이다. 다음은 결함 evidence:

```bash
# main HEAD audit (본 amend PR 직전 실행)
$ git fetch origin main && ls api/src/main/resources/db/migration/ | grep "^V4" | sort
V40__order_event_outbox.sql
V41__stomp_event.sql
V42__instrument_status_history.sql
V43__order_request_add_strategy_id.sql
V44__system_event.sql
V45__order_request_backfill_manual_source.sql
V46__backtest_run_strategy_id.sql
```

V40~V46 모두 점유 사실 확인 → R0 spec V40~V45 LOCK 전체 무효 → architect R0 amend PR (본 PR)이 V47~V52 재분배 LOCK 발행.

향후 architect spec/plan 발행 시 본 ADR §2-1 5단계 절차 LOCK 수행 의무. 위반 시 §2-4 escalation 경로 가동.

---

## 3. 영향도 매트릭스

### 3-1. 기존 ADR · 헌법 영향

| 기존 ADR/헌법 | 영향 | 대응 |
|---|---|---|
| 헌법 §3 (KIS-first raw fetch) | 0 (외부 데이터 규격 영역 — 본 ADR은 내부 영속 자원 영역으로 보완) | 본 ADR이 §3 lens "자체 추론 0"의 내부 영역 적용 — 동일 lens 양면 |
| ADR 0030 (SoT 정합) | 0 | 본 ADR은 architect governance 본문 — SoT 영역 침범 0 |
| ADR 0036 (task cycle) | LOW | architect task 발행 직전 5단계 절차 LOCK 의무 추가 — 본 ADR §2-1 정합 |
| ADR 0050 (CHANGELOG slot) | 0 | slot anchor sub-agent 분배는 별 의제 — 본 ADR은 영속 자원 audit 영역 |
| ADR 0094 (운영 단계) | LOW | 운영 단계 lens 정합 — spec 결함은 직접 sub-agent dispatch 손실 |

### 3-2. 영역 침범 매트릭스 (architect governance 단독)

| 영역 | 침범 | 회피 |
|---|---|---|
| architect 영역 | 본 ADR이 spec/plan 발행 절차 LOCK 의무 추가 | architect 자율 — 면제 조건 §2-3 명시로 hotfix·md only·amend 자율성 보장 |
| sub-agent 영역 | 0 | sub-agent는 본 ADR 직접 대상 0 (architect spec 정합으로만 영향) |
| orchestrator 영역 | LOW | escalation 경로 §2-4 명시 — orchestrator가 결함 fix dispatch trigger 자율 |
| 운영 코드 | 0 (본 ADR md only) | Kotlin/TS/SQL 0 변경 |

### 3-3. 연결 매트릭스 (신규 → 기존 reuse)

| 신규 절차 | 활용 기존 |
|---|---|
| §2-1 5단계 audit | `git ls-tree origin/main`·`git grep` 표준 명령 reuse |
| §2-2 대상 매트릭스 | 기존 영속 자원 위치 권위 (ADR 0007 PostgreSQL · ADR 0019 Kafka · ADR 0006 Redis · ADR 0028 STOMP) reuse |
| §2-4 escalation 경로 | 헌법 §7 escalation 절차 reuse + R0 결함 fix amend PR 패턴 신설 |
| §2-5 evidence 본문 | spec/ADR 본문 audit evidence 인용 패턴 신설 — 헌법 §3 raw fetch evidence 패턴 1:1 |

---

## 4. 결과

### 4-1. 포지티브

- spec/plan 발행 직전 5단계 절차로 본 결함 (V40~V45 충돌) 재발 0
- 영속 자원 전 영역 (Flyway·Kafka·Redis·STOMP·REST·ENV) 동일 lens 적용 — 외부 데이터 규격 (헌법 §3)과 내부 영속 자원 (본 ADR) 양면 보완
- audit evidence 본문 첨부로 spec/ADR 신뢰성 ↑ + Codex 외부 review 4 lens 정합
- escalation 경로 §2-4 명문화로 사용자 의제화 risk 감소 (사용자 메모리 `feedback_conflict_resolution_autonomy` 정합)

### 4-2. 네거티브 (mitigation 포함)

- architect spec/plan 발행 비용 ↑ (5단계 절차 수행 시간) — **mitigation**: 면제 조건 §2-3 자율 + audit 명령 grep 1회 본질 (수 초)
- spec 본문에 audit evidence 첨부 의무 — **mitigation**: 1줄 ls 출력 인용 충분 (간소)

---

## 5. 운영 ADR 양식 3 슬롯 (헌법 §9-5 정합)

### 5-1. SLO 영향 (ADR 0095 §9-1)

본 ADR은 architect governance 본문 — 시스템 SLO 직접 영향 0. 다만 spec 결함 재발 방지로 sub-agent dispatch 비용 손실 risk 감소 (간접 운영 효율 ↑).

### 5-2. rollback plan

본 ADR 운영 부적합 발견 시 (예: 5단계 절차가 너무 무겁다는 사용자 feedback):

| 단계 | 절차 | 예상 소요 |
|---|---|---|
| 1 | 본 ADR 상태 → Superseded 표시 + 새 ADR 발행 (절차 간소화 amend) | < 30분 |
| 2 | 기존 spec/ADR amend 무필요 (본 ADR은 미래 spec 영향만) | 0 |

### 5-3. 관측 매트릭스

| 측정 | source | 1차 권한 | alert 임계 |
|---|---|---|---|
| spec/plan 결함 발생 빈도 (헌법 §7 escalation count) | sub-agent escalation 보고 | orchestrator 누적 카운트 | (단순 observability — alert 없음 · 사용자 자율 검토) |
| 5단계 절차 수행 비율 | architect PR 본문 audit evidence 인용 grep | 사용자 spot check | (검증 단순 — grep 1회) |

### 5-4. 영역 확장 후보 (post-MVP cross-ref)

본 ADR §2-2 영속 자원 매트릭스는 **6 영역 (Flyway·Kafka·Redis·STOMP·REST·ENV)** 외부 영속 자원 LOCK이 1차. 다음 사례는 6 영역 외 추가 영역의 결함 1회 누적 evidence로, 향후 영역 확장 amend 후보다 (단 본 ADR 영역 LOCK은 6 LOCK 유지 — 누적 evidence 3건+ 시 별 사이클 amend).

| # | 사례 evidence | 결함 본질 | 누적 |
|---|---|---|---|
| 1 | **ADR 0101 §2-2 DDL `user_id BIGINT REFERENCES user_account(id)` 초안 (W4 P1 amend)** | 기존 표 컬럼 타입·PK 자연/surrogate 구분 미인지 (실 `user_account.user_id VARCHAR(20)` 자연 키 · surrogate `id` 컬럼 0건) — R1 backend-core PR #676 audit 결과 발견 → ADR 0101 본문 amend 1건 (R1 코드 V51 마이그레이션은 이미 실 schema 정합 발행) | 1 |

→ **확장 후보 영역**: **내부 도메인 모델 inventory** (기존 표 컬럼 타입 + PK 자연/surrogate 구분 + JPA Entity field 타입 등). 누적 3건 시 ADR 0102 §2-2 7번째 영역 추가 amend 발행 의제.

---

## 6. 트레이드오프

| 의제 | 채택 | 거부 |
|---|---|---|
| 절차 LOCK 위치 | **신규 ADR 0102** (영구 baseline · cross-ref 권위) | 헌법 §3 amend (가벼움 · 빠른 정합) — ADR 격상 권위 정합 + 사용자 메모리 `feedback-codex-validation-gate` lens 정합 |
| 영속 자원 매트릭스 범위 | **6 영역 (Flyway·Kafka·Redis·STOMP·REST·ENV)** | Flyway 단독 (본 결함만 cover · 향후 동종 결함 재발 risk) |
| 면제 조건 | **3 조건 (md only·hotfix·이미 LOCK 자원 amend)** | 면제 조건 0 (architect 자율성 침해 risk + spec 결함과 무관한 PR 비용 ↑) · 면제 조건 5+ (LOCK 우회 risk) |
| escalation 경로 | **5단계 명시 (sub-agent escalation → orchestrator kill → architect amend → 정합 머지 → 재 dispatch)** | 자율 처리 (재발 방지 evidence 부재 risk) |
| evidence 본문 첨부 | **spec/ADR 본문 1줄 인용 의무** | 별 audit 보고서 (spec 본문과 분리 시 정합 risk) |

---

## 7. 후속 사이클 (post-MVP)

| 의제 | 위치 | 트리거 |
|---|---|---|
| 5단계 절차 자동화 (architect 사전 검증 스크립트) | `scripts/spec-audit.mjs` 신설 | architect 자율 |
| Kafka topic registry 단일 진실원 | 신규 spec — `_workspace/arch/registry/kafka-topics.md` | post-MVP |
| Redis key prefix registry | 신규 spec — `_workspace/arch/registry/redis-keys.md` | post-MVP |
| STOMP topic registry | 신규 spec — `_workspace/arch/registry/stomp-topics.md` | post-MVP |
| ENV 변수 registry | `.env.example` 권위 + 신규 ADR | post-MVP |
| pre-commit hook으로 audit 자동 강제 | `.husky/pre-commit` amend | architect 자율 + 사용자 결재 |

---

## 8. References

- 본 ADR 발생 trigger: 2026-05-24 R0 PR #674 (commit 1411d392) Flyway V40~V45 LOCK 결함
- 본 ADR 발행 PR: architect R0 결함 fix amend PR (V47~V52 shift + 본 ADR 0102 신설)
- 헌법 §3 (KIS-first raw fetch — "자체 추론 0 정책 · 공식 출처 raw fetch 의무")
- ADR 0030 (SoT 정합) · ADR 0036 (task cycle) · ADR 0050 (CHANGELOG slot) · ADR 0094 (운영 단계 — spec 결함 risk lens)
- 사용자 메모리: `feedback-codex-validation-gate` (Codex 외부 review 4 lens 정합 — 세심함·영향도·연결·확장성)
- 사용자 메모리: `feedback_conflict_resolution_autonomy` (충돌 해결 자율 처리)

---

## 9. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-05-24 | **ADR 0102 신설 Accepted — architect spec/plan 발행 직전 main 영속 자원 audit 5단계 절차 LOCK (Flyway·Kafka·Redis·STOMP·REST·ENV 6 영역) + 면제 조건 3건 (md only·hotfix·기존 LOCK amend) + 위반 시 escalation 경로 5단계 + 발생 evidence 본문 (R0 PR #674 V40~V45 결함) + 운영 ADR 양식 3 슬롯 + 후속 사이클 6 슬롯** — orchestrator R0 결함 fix dispatch 정합 (사용자 메모리 `feedback-codex-validation-gate` 4 lens 정합). architect 단독 R0 amend PR (본 결함 fix + 본 ADR 신설 함께). 운영 코드 0 touch (md only). 자본 영향 0 + KIS endpoint 호출 0 + AI 호출 0 + mock 합성 0. ADR 0026·0027·0100·0101 amend 동반 (V47~V52 shift 본문 정합). (architect, opus 4.7 1M) |
| 2026-05-24 | **§5-4 영역 확장 후보 cross-ref 추가 (W4 P1 amend)** — ADR 0101 §2-2 DDL `user_id BIGINT REFERENCES user_account(id)` 초안 결함 (R1 backend-core PR #676 audit 결과 실 `user_account.user_id VARCHAR(20)` 자연 키 미인지 — ADR 0046 §2-1 권위) 1건을 본 ADR §5-4 영역 확장 후보 표에 신규 추가. 본질: 외부 영속 자원 6 영역 LOCK 외 **내부 도메인 모델 inventory** (기존 표 컬럼 타입·PK 자연/surrogate 구분) 영역 결함. 영역 확장 LOCK은 본 amend에서 수행 0 (누적 evidence 3건+ 시 별 사이클 amend) — 본 amend는 cross-link 단독. 운영 코드 0 touch (md only). 본 amend는 ADR 0101 §2-2 amend PR과 동반 (W4 P1 architect 단독). (architect, opus 4.7 1M) |
