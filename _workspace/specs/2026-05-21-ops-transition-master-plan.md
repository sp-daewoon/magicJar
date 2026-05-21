# 운영 단계 transition master plan — audit + agent reshuffle + transition

> **위치**: `_workspace/specs/2026-05-21-ops-transition-master-plan.md`
> **연관 ADR**: `_workspace/arch/adr/0094-ops-stage-transition.md` Proposed (본 spec의 결정 본문)
> **작성**: architect (worktree `magic_ch1_architect_ops_transition`, branch `agent/architect/ops-transition-plan`)
> **결재 의무**: 사용자(최고 관리자) — ADR 0094 §6 결재 카드 D-1 ~ D-6 응답 후 Accepted 격상
> **사용자 명시 evidence** (2026-05-21 KST):
> > "전영역 코드베이스로 검증 절차에 돌입해야해. 이거 하고 나서 에이전트 전격적으로 해고 절차와 투입하는걸 다시 만들 거야. 이제 개발의 전반은 다 되어가기때문에 시스템 운영 단계 및 부분 고도화로 갈 준비를 해야해"

본 spec은 ADR 0094의 §3·§4·§5·§6 본문을 그대로 참조한다. 본 문서는 spec/plan PR navigation용 색인 + ADR이 흡수하지 못한 운영 절차 보강만 보유.

---

## §0. ADR 0094 § 매핑 (단일 진실원)

| 산출물 | ADR 0094 §위치 |
|--------|---------------|
| 전영역 audit 결과 (Fitness Gap P0/P1/P2 매트릭스) | §3 |
| sub-agent reshuffle (8 → 6 hierarchy + 페르소나 매트릭스) | §2 + §4-6 |
| 운영 단계 transition plan (mindset + 헌법 §9 + release + dispatch 매트릭스) | §4 |
| 사용자 결재 의무 항목 | §6 |
| 트레이드오프 + 거부 옵션 | §5 |
| 측정 가능 결과 (운영 헌법 §9-5 첫 적용) | §7-3 |

본 spec 본문은 위 § 정독 후 navigation용으로만 사용. 결정 권위는 ADR 0094 본문.

---

## §1. 산출물 1 — 전영역 코드베이스 audit 보고서 (요약)

### 1-1. 정량 evidence (직접 측정 — 2026-05-21 main commit `28745ed3`)

```
JVM 모듈 (settings.gradle.kts):
  domain · pattern-engine · indicator-impl · strategy-engine
  persistence-shared · api · batch · consumer (총 8 — 도메인+라이브러리 3 + Boot 4)

Kotlin LOC:
  prod 65009 (file 806)
  test 26819
  module 분포: api 251 / domain 199 / consumer 149 / batch 41 / persistence-shared 13

Test class 수:
  api 77 / consumer 64 / batch 5 / domain 4 / persistence-shared 2 — 총 152

Frontend (TS/TSX):
  246 file / 33489 LOC

Flyway migration:
  43+ (API 모듈) — V5 instrument_kis_first_expansion ~ V9 strategy 외 18+ 추가

ADR:
  93 Proposed/Accepted + README (0001 ~ 0093 + 본 0094 추가)

운영 헌법:
  7 § + 5 정책(Living Policy)

git log past 7 weeks (2026-04-01~):
  total 1129 commit
  bot+architect 295 / bot+backend-core 227 / bot+orchestrator 173 / bot+frontend 145
  bot+market-data 129 / sp.daewoon 44 / bot+strategy 34 / bot+chart-curator 32
  sp-daewoon 26 / bot+ai 19 / seonjupark94 4 / bot+qa 1

운영 관측 stack:
  Actuator 의존: api·batch·consumer 추가됨
  application.yml management.endpoints 노출: 0
  Prometheus·Micrometer registry 의존: 0
  logback*.xml: 0 (Spring Boot 기본)
  docs/operations/: 2 file (initial-setup·kis-api-reference)
```

### 1-2. fitness gap lens (사용자 메모리 `feedback_v3_skepticism_no_regression` 정합)

각 영역 평가는 **운영 단계 기준** lens — "자동매매에 필요한가? v1/v2 잔재 아닌가?" + "운영 단계에서 위험인가?":

- v1/v2 잔재 0 (v3.0.0에서 PoC 풀 정리 완료 — 사용자 메모리 `project_v2_baseline` 정합)
- KIS-only 정책 정합 (사용자 메모리 `feedback_kis_first_policy` — Yahoo @Deprecated 외 외부 fallback 0)
- W3 자동매매 본체 종착 — 룰 본체 + 자본 흐름 path 안정 (PR #631~#644 6 hotfix 누적 evidence)
- 운영 단계 진입 차단 gap 6건 (P0-OPS-1 ~ P0-OPS-6, ADR 0094 §3-2)

### 1-3. P0/P1/P2 매트릭스

ADR 0094 §3 본문 인용 — 본 spec 중복 금지.

---

## §2. 산출물 2 — sub-agent 재편 design

### 2-1. 분류 4건

| 분류 | 대상 | 사유 |
|------|------|------|
| **유지** (4 actor) | architect · backend-core · frontend · market-data · strategy-engine | 운영 단계에서도 핵심 + 책임 흡수로 확대 |
| **흡수** (1 actor) | chart-curator → frontend(LWC) + market-data(캔들 SLA) | 영역 본질 일치 + 페르소나 "안정빌드" 보존 + 운영 부담 ↓ |
| **해고** (1 actor) | qa | past 7 weeks 1 dispatch + "QA 게이트 배제" 룰 정합 (사용자 메모리 `feedback_qa_on_demand`) |
| **보류** (1 actor) | ai-engineer | W4 진입 시 재영입 path 보존 — archive 본문 LOCK |
| **신규** (1 actor) | sre (Site Reliability Engineer) | 운영 단계 1순위 — 관측·SLO·incident·runbook 단일 owner |

**결과**: 8 → 6 hierarchy (architect · backend-core · frontend · market-data · strategy-engine · sre).

ADR 0094 §2-2 매트릭스 + §2-2-A/B/C/D 본문 인용 — 본 spec 중복 금지.

### 2-2. sre 페르소나 본문 초안 (사용자 결재 D-4 후 LOCK)

| 항목 | 값 |
|------|----|
| 역할 | Site Reliability Engineer |
| 경력 | 10년차 (대규모 트래픽 운영 + on-call 다수 경험) |
| 성향 | 방어적, 관측 1순위, alert 1차 차단 lens |
| 별명 | "운영자" (operator) |
| 좌우명 | "측정할 수 없는 것은 개선할 수 없다." |
| 핵심 가치 5건 | 관측성 · 복원력 · 롤백 가능성 · alert noise 최소화 · runbook 갱신 |
| 코드 리뷰 lens 6건 | (1) Prometheus metric 노출 여부 (2) SLO 영향 (3) 롤백 가능성 (4) alert routing (5) timeout/retry/circuit breaker (6) runbook 갱신 필요성 |
| 출력 형식 | `## SRE (운영자) 의견` 헤더 + 운영 영향 매트릭스(SLO·alert·rollback 3 슬롯) + 점검 사항(📊 관측 / 🚨 alert / 🔄 rollback / 📖 runbook) |
| 트리거 | incident · SLO 위반 · 신규 ADR 운영 영향 검토 · 분기 운영 점검 |
| 모델 | opus 4.7 (ADR 0080 정합) |

### 2-3. 페르소나 매트릭스 재배치 (운영 단계 LOCK)

ADR 0094 §2-2-D 본문 인용.

### 2-4. 적용 절차 (ADR Accepted 후)

ADR 0094 §4-6 본문 인용 — 후속 PR diff 매트릭스(`.claude/agents/*.md` · 헌법 §9 · orchestrator SKILL · master §10 · ADR 0080·0085 status amend · CLAUDE.md · CHANGELOG).

---

## §3. 산출물 3 — 운영 단계 transition plan

### 3-1. mindset 전환 (사용자 명시 LOCK)

ADR 0094 §4-1 본문 인용.

### 3-2. 운영 헌법 §9 신설 (architect 후속 PR)

ADR 0094 §4-2 본문 인용 — 5 룰(SLO/SLA·변경 관리·on-call·backup/DR·운영 ADR 양식).

### 3-3. release 정책 갱신

ADR 0094 §4-3 본문 인용 — v3.8.0 운영 단계 baseline + v3.9.0 W4 슬롯 + v4.0.0 실서비스 cut.

### 3-4. 항목별 결정 매트릭스 (W4·W5·backlog)

ADR 0094 §4-4 본문 인용.

### 3-5. 점진 고도화 우선순위 (운영 데이터 기반)

ADR 0094 §4-5 본문 인용 — 5 trigger lens.

### 3-6. orchestrator dispatch 매트릭스 (운영 단계)

ADR 0094 §4-7 본문 인용 — 6 트리거 × 1·2순위 actor 매트릭스.

### 3-7. P0 OPS 6건 1차 dispatch 순서 (사용자 결재 후 sre 가동)

| 순서 | P0 ID | 작업 | 예상 PR |
|------|-------|------|---------|
| 1 | P0-OPS-1 | logback-spring.xml (JSON + MDC traceId/userId/orderId) — 3 모듈 동시 | sre 1 PR |
| 2 | P0-OPS-2 | application.yml `management.endpoints.web.exposure.include` + Micrometer Prometheus 의존 + docker compose Prometheus + Grafana | sre 1 PR |
| 3 | P0-OPS-3 | SLO/SLA 정의 ADR + alert routing matrix ADR 별도 발행 | architect spec + sre 1 PR |
| 4 | P0-OPS-5 | `docs/operations/runbook/` 5 시나리오 | sre 1 PR |
| 5 | P0-OPS-4 | DR/backup 절차 ADR + pg_dump cron + Redis snapshot 정책 | architect spec + sre 1 PR |
| 6 | P0-OPS-6 | persistence-shared outbox drain lag actuator metrics + Grafana panel + Slack alert | sre + backend-core 합작 PR |

**예상 사이클**: 본 ADR Accepted 후 v3.8.0 minor cut 전 6 PR.

---

## §4. 검증 — 사용자 메모리 정합 매트릭스

| 사용자 메모리 | 본 spec 정합 |
|--------------|------------|
| `feedback_v3_skepticism_no_regression` | ADR 0094 §1-2 fitness gap lens 의무 + §3 P0/P1/P2 운영 단계 기준 |
| `feedback_release_policy_patch_first` | ADR 0094 §4-3 v3.8.0 = 운영 단계 baseline minor + v3.7.x patch 누적 후 cut |
| `feedback_kis_first_policy` | 본 spec §1-2 KIS-only 정책 정합 명시 + audit 결과 인용 |
| `project_system_vision` | ADR 0094 §1-1 사용자 결재 evidence 보존 + 룰 본체 + AI 옵션 매트릭스 유지 |
| `feedback_qa_on_demand` | ADR 0094 §2-2-A qa 해고 사유 인용 |
| `feedback_orchestrator_active_parallel` | ADR 0094 §4-7 dispatch 매트릭스 — sre 1순위 + 영역 actor 2순위 |
| `feedback_spec_plan_scope` | 본 spec은 다중 ADR · sub-agent 재편 · 새 § 헌법 → spec 발행 의무 충족 (단순 hotfix 아님) |
| `project_v3_baseline` | v3.7.0 → v3.8.0 운영 단계 1차 baseline 매트릭스 |

### 4-1. 권위 ADR 정합

- ADR 0080 (전체 opus 통일) — 본 ADR 0094가 8 → 6 reshuffle로 amend. 모델은 opus 4.7 통일 LOCK 유지.
- ADR 0085 (chart-curator 영입) — 본 ADR 0094가 책임 이관(frontend·market-data) + 페르소나 보존 amend.
- ADR 0087 (페르소나 매트릭스) — 본 ADR 0094가 운영 단계 재배치 amend (frontend·market-data 보수 흡수 + sre 방어 신규).
- ADR 0036 (Orchestrator Meta Rules) — dispatch 매트릭스 §9 신설로 영향 — orchestrator SKILL.md 정합 후속 PR.
- ADR 0044 (Notification 시스템) — sre alert routing 본 ADR §3-2 reuse — 신규 인프라 0.
- ADR 0049 (GitHub Actions release 한정) — release 정책 갱신 본 ADR 정합 — 자동화 트리거 0 LOCK 유지.

---

## §5. 다음 사이클 권장 path (사용자 결재 후)

### 5-1. Phase A — 본 PR 머지 (사용자 결재 1차)

- 본 spec + ADR 0094 Proposed + master §10 추가 row + CHANGELOG row
- 사용자 결재 카드 D-1 ~ D-6 응답 후 ADR Accepted 격상

### 5-2. Phase B — architect 후속 PR (sub-agent reshuffle 적용)

ADR 0094 §4-6 매트릭스:
- `.claude/agents/qa.md` 삭제 또는 archive
- `.claude/agents/ai-engineer.md` archive (W4 재영입 path 보존)
- `.claude/agents/chart-curator.md` archive (책임 이관 정합)
- `.claude/agents/sre.md` 신규 발행 (페르소나 §2-2)
- `.claude/agents/frontend.md` + `.claude/agents/market-data.md` 페르소나 amend
- `_workspace/arch/agent-charter.md` §9 신설 (ADR 0094 §4-2)
- `.claude/skills/magicjar-orchestrator/SKILL.md` 6 actor 갱신
- `docs/architecture/master-architecture.md` §10 SoT 정합
- ADR 0080·0085 status amend
- `CLAUDE.md` 운영 단계 진입 본문 amend
- `CHANGELOG.md` row

### 5-3. Phase C — sre 가동 (P0 OPS 6건 dispatch)

본 spec §3-7 순서 — 6 PR + 2 ADR. 완료 시 v3.8.0 minor cut.

### 5-4. Phase D — 운영 단계 정상 운영 (v3.8.x)

- 주간 sre 점검 cycle
- 백로그 trim 사이클 (architect 단독)
- P1 OPS 6건 점진 처리
- W4·W5 진입 결재 의제 (사용자 D-6)

---

## §6. 변경 이력

| 날짜 | 변경 | 작성자 |
|------|------|--------|
| 2026-05-21 | spec 발행 (audit + agent reshuffle + transition plan + ADR 0094 발행) | architect |
